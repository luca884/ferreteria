package com.ferreteria.facturacion.service;

import com.ferreteria.facturacion.api.FacturacionParaVenta;
import com.ferreteria.facturacion.config.EjecutorFiscalConfig;
import com.ferreteria.facturacion.model.Alicuota;
import com.ferreteria.facturacion.model.ComprobanteFiscal;
import com.ferreteria.facturacion.model.CondicionIvaReceptor;
import com.ferreteria.facturacion.model.EstadoFiscal;
import com.ferreteria.facturacion.model.ReceptorFiscal;
import com.ferreteria.facturacion.model.RenglonFiscal;
import com.ferreteria.facturacion.model.TipoDocumentoReceptor;
import com.ferreteria.facturacion.motor.AltaFiscal;
import com.ferreteria.facturacion.motor.AmbienteNoDisponibleException;
import com.ferreteria.facturacion.motor.Atencion;
import com.ferreteria.facturacion.motor.MotorFiscal;
import com.ferreteria.facturacion.motor.PasosFiscales;
import com.ferreteria.facturacion.repository.ComprobanteFiscalRepository;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * La factura de la venta (CU-07 pasos 3 y 5; cu-07-venta-facturada S1 b y c, S3 y S4). Traduce los tipos del puerto a los
 * del motor y no conoce a ventas ni a clientes.
 *
 * <p>Dentro de la transacción de la venta ({@code Propagation.MANDATORY}): {@link #decidir} es la primera carga de
 * {@code config_arca} en esa transacción, con {@code FOR SHARE} hasta el commit (advisory A3 de la rama 2: así no queda una
 * instancia vieja en el contexto de persistencia), y {@link #registrar}, el último paso, da de alta el registro con el motor,
 * cuyo {@code FOR SHARE} devuelve esa misma instancia ya tomada. Las dos deciden con la misma regla,
 * {@link PasosFiscales#puedeFacturar()} (la facturación habilitada y los datos de CU-01 completos): lo que {@code decidir}
 * deja facturar, {@code registrar} lo da de alta.
 *
 * <p>Después del commit, sin transacción (advisory A2: la llamada a ARCA nunca va con una conexión tomada),
 * {@link #autorizar} atiende la serie del registro en el ejecutor de {@code facturacion} ({@link EjecutorFiscalConfig}) y la
 * espera un rato acotado ({@code app.facturacion.venta.espera}), medido con el reloj de pared y no con el {@code Clock}
 * (que en los tests está fijo). Mientras otra instancia tiene la serie, reintenta con una pausa corta; vencida la espera, la
 * atención en curso termina sola y no se reintenta más (lo pendiente lo levanta la venta siguiente de la serie). Nunca lanza
 * por ARCA: lo que no se resolvió vuelve en su estado actual. Cada lectura del estado es fresca, en su propia transacción
 * de solo lectura ({@code open-in-view} está apagado).
 */
@Service
public class FacturacionParaVentaImpl implements FacturacionParaVenta {

    private static final Logger log = LoggerFactory.getLogger(FacturacionParaVentaImpl.class);

    /** Lo que guarda el registro fiscal del receptor (V32): lo que sobra se corta. */
    private static final int LARGO_NOMBRE = 120;
    private static final int LARGO_DOMICILIO = 200;

    /** Los estados que no cambian con otra atención: el resultado de ARCA, o una alerta que espera a una persona. */
    private static final Set<EstadoFiscal> RESUELTOS = EnumSet.of(EstadoFiscal.AUTORIZADO, EstadoFiscal.RECHAZADO,
            EstadoFiscal.FUERA_DE_FECHA, EstadoFiscal.CAE_NO_COINCIDE, EstadoFiscal.SERIE_DESINCRONIZADA);

    /**
     * Las atenciones después de las cuales otra, ahora, no cambiaría nada: se atendió todo, ARCA no contestó (sigue la
     * próxima) o la serie tiene una alerta. Las otras dos (OCUPADA y SERIE_PERDIDA) son de otra instancia: se reintenta.
     */
    private static final Set<Atencion> FINALES = EnumSet.of(Atencion.TERMINADA, Atencion.SIN_RESPUESTA, Atencion.TRABADA);

    private final PasosFiscales pasos;
    private final ComprobanteFiscalRepository registros;
    private final MotorFiscal motor;
    private final ThreadPoolTaskExecutor ejecutor;
    private final TransactionTemplate lectura;
    private final BigDecimal topeIdentificacion;
    private final Duration espera;
    private final Duration pausaSerieOcupada;

    /**
     * @param topeIdentificacion el tope de ARCA para la venta sin cliente identificado ({@code Decision})
     * @param espera cuánto espera {@link #autorizar} el resultado, en tiempo de pared
     * @param pausaSerieOcupada la pausa entre atenciones mientras otra instancia tiene la serie
     */
    public FacturacionParaVentaImpl(PasosFiscales pasos, ComprobanteFiscalRepository registros, MotorFiscal motor,
            @Qualifier(EjecutorFiscalConfig.EJECUTOR_FISCAL) ThreadPoolTaskExecutor ejecutor,
            PlatformTransactionManager transacciones,
            @Value("${app.facturacion.tope-identificacion}") BigDecimal topeIdentificacion,
            @Value("${app.facturacion.venta.espera}") Duration espera,
            @Value("${app.facturacion.venta.pausa-serie-ocupada}") Duration pausaSerieOcupada) {
        this.pasos = pasos;
        this.registros = registros;
        this.motor = motor;
        this.ejecutor = ejecutor;
        this.lectura = new TransactionTemplate(transacciones);
        this.lectura.setReadOnly(true);
        this.topeIdentificacion = topeIdentificacion;
        this.espera = espera;
        this.pausaSerieOcupada = pausaSerieOcupada;
    }

    // ---- En la transacción de la venta ----

    /**
     * Factura si la ferretería puede facturar ahora, con la misma regla que el alta ({@link PasosFiscales#puedeFacturar()}:
     * la facturación habilitada con ambiente y punto de venta, y los datos de CU-01 completos). Si no, la venta sale «sin
     * factura», como sin facturación (D5): sin controlar al receptor ni el tope de ARCA.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Decision decidir(ReceptorVenta receptor) {
        Objects.requireNonNull(receptor, "Falta el receptor de la venta.");
        // Siempre con FOR SHARE, también sin fila (lock de hueco): la decisión y el alta ven la misma configuración (L3).
        if (!pasos.puedeFacturar()) {
            return Decision.SIN_FACTURA;
        }
        return new Decision(true, receptorCompleto(receptor), topeIdentificacion);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long registrar(long comprobanteId, ReceptorVenta receptor, List<RenglonParaFacturar> renglones, Instant fecha) {
        Objects.requireNonNull(receptor, "Falta el receptor de la venta.");
        Objects.requireNonNull(renglones, "Faltan los renglones de la venta.");
        Objects.requireNonNull(fecha, "Falta la fecha de la venta.");
        AltaFiscal alta = new AltaFiscal(comprobanteId, receptorFiscal(receptor),
                renglones.stream().map(FacturacionParaVentaImpl::renglonFiscal).toList());
        return motor.registrar(alta, fecha);
    }

    // ---- Después del commit ----

    @Override
    public Optional<EstadoFiscalVenta> autorizar(long comprobanteId) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("El CAE se pide después del commit de la venta, sin transacción: la llamada a "
                    + "ARCA no puede ir con una conexión tomada.");
        }
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        Optional<ComprobanteFiscal> registro = registroDeVenta(comprobanteId);
        if (registro.isEmpty() || RESUELTOS.contains(registro.get().getEstado())) {
            return registro.map(FacturacionParaVentaImpl::estadoDe);
        }

        AtomicBoolean abandonada = new AtomicBoolean();
        Future<?> atencion;
        try {
            atencion = ejecutor.submit(() -> atenderHastaResolver(ferreteriaId, registro.get(), abandonada));
        } catch (TaskRejectedException e) {
            log.warn("Sin lugar en el ejecutor fiscal para pedir el CAE de la venta {} de la ferretería {}: queda en su "
                    + "estado actual.", comprobanteId, ferreteriaId);
            return estado(comprobanteId);
        }
        boolean interrumpido = false;
        try {
            atencion.get(espera.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.info("Venció la espera del CAE de la venta {} de la ferretería {} ({} ms): responde con su estado actual "
                    + "y la atención en curso termina sola.", comprobanteId, ferreteriaId, espera.toMillis());
        } catch (ExecutionException e) {
            log.error("Falló la atención fiscal de la venta {} de la ferretería {}.", comprobanteId, ferreteriaId,
                    e.getCause());
        } catch (InterruptedException e) {
            interrumpido = true;
            log.warn("Se interrumpió la espera del CAE de la venta {} de la ferretería {}.", comprobanteId, ferreteriaId);
        }
        // Desde acá, nada de reintentos: lo que esté en vuelo termina solo, y lo que todavía no arrancó no arranca.
        abandonada.set(true);
        atencion.cancel(false);
        try {
            return estado(comprobanteId);
        } finally {
            if (interrumpido) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public Optional<EstadoFiscalVenta> estado(long comprobanteId) {
        return registroDeVenta(comprobanteId).map(FacturacionParaVentaImpl::estadoDe);
    }

    /**
     * En un hilo del ejecutor, con la ferretería de quien pidió: atiende la serie del registro hasta que el registro se
     * resuelve o la atención termina por algo que otra no cambiaría ahora. Con la serie de otra instancia, reintenta después
     * de una pausa, salvo que quien espera ya se haya ido. Nada sale de acá: lo que falle queda en el log.
     */
    private void atenderHastaResolver(long ferreteriaId, ComprobanteFiscal registro, AtomicBoolean abandonada) {
        TenantContext.setCurrentFerreteriaId(ferreteriaId);
        try {
            while (!abandonada.get()) {
                Atencion atencion = motor.atender(registro.getAmbiente(), registro.getPuntoVenta(), registro.getCbteTipo());
                if (FINALES.contains(atencion) || resuelto(registro.getId())) {
                    return;
                }
                Thread.sleep(pausaSerieOcupada.toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (AmbienteNoDisponibleException e) {
            log.warn("No se pidió el CAE de la venta {} de la ferretería {}: {}", registro.getComprobanteId(), ferreteriaId,
                    e.getMessage());
        } catch (RuntimeException e) {
            log.error("Falló la atención fiscal de la venta {} de la ferretería {}: queda en su estado actual.",
                    registro.getComprobanteId(), ferreteriaId, e);
        } finally {
            TenantContext.clear();
        }
    }

    private boolean resuelto(long registroId) {
        return lectura.execute(s -> registros.findScoped(registroId))
                .map(r -> RESUELTOS.contains(r.getEstado()))
                .orElse(false);
    }

    private Optional<ComprobanteFiscal> registroDeVenta(long comprobanteId) {
        return lectura.execute(s -> registros.deComprobanteScoped(comprobanteId));
    }

    private static EstadoFiscalVenta estadoDe(ComprobanteFiscal r) {
        return new EstadoFiscalVenta(r.getEstado().name(), r.getClase().name(), r.getLetra().name(), r.getPuntoVenta(),
                r.getNumero(), r.getCae(), r.getCaeVencimiento(), r.getAmbiente().name());
    }

    // ---- Traducción del puerto al motor ----

    /** D4: Consumidor Final, o Responsable Inscripto, Monotributista o Exento con CUIT y domicilio. */
    static boolean receptorCompleto(ReceptorVenta receptor) {
        if (receptor.condicion() == CondicionIva.CONSUMIDOR_FINAL) {
            return true;
        }
        return receptor.tipoDocumento() == TipoDocumento.CUIT && !vacio(receptor.numeroDocumento())
                && !vacio(receptor.domicilio());
    }

    /** Sin documento, sin identificar (DocTipo 99, DocNro 0); el nombre y el domicilio, cortados a lo que guarda el registro. */
    static ReceptorFiscal receptorFiscal(ReceptorVenta receptor) {
        CondicionIvaReceptor condicion = switch (receptor.condicion()) {
            case CONSUMIDOR_FINAL -> CondicionIvaReceptor.CONSUMIDOR_FINAL;
            case RESPONSABLE_INSCRIPTO -> CondicionIvaReceptor.RESPONSABLE_INSCRIPTO;
            case MONOTRIBUTISTA -> CondicionIvaReceptor.MONOTRIBUTISTA;
            case EXENTO -> CondicionIvaReceptor.EXENTO;
        };
        String nombre = recortar(receptor.nombre(), LARGO_NOMBRE);
        String domicilio = recortar(receptor.domicilio(), LARGO_DOMICILIO);
        if (!receptor.identificado()) {
            return new ReceptorFiscal(condicion, TipoDocumentoReceptor.SIN_IDENTIFICAR, 0, nombre, domicilio);
        }
        TipoDocumentoReceptor tipo = switch (receptor.tipoDocumento()) {
            case DNI -> TipoDocumentoReceptor.DNI;
            case CUIT -> TipoDocumentoReceptor.CUIT;
            case CUIL -> TipoDocumentoReceptor.CUIL;
        };
        return new ReceptorFiscal(condicion, tipo, Long.parseLong(receptor.numeroDocumento().strip()), nombre, domicilio);
    }

    static RenglonFiscal renglonFiscal(RenglonParaFacturar renglon) {
        return new RenglonFiscal(alicuota(renglon.alicuotaPorcentaje()), renglon.total());
    }

    /** La alícuota de WSFEv1 con ese porcentaje (21.00 y 21 son la misma). */
    static Alicuota alicuota(BigDecimal porcentaje) {
        for (Alicuota alicuota : Alicuota.values()) {
            if (alicuota.porcentaje().compareTo(porcentaje) == 0) {
                return alicuota;
            }
        }
        throw new IllegalArgumentException("ARCA no tiene una alícuota de IVA del " + porcentaje.toPlainString() + " %.");
    }

    /** Sin espacios de más; vacío es null, y lo que pasa del largo (en caracteres, como cuenta MySQL) se corta. */
    private static String recortar(String texto, int largo) {
        if (vacio(texto)) {
            return null;
        }
        String limpio = texto.strip();
        if (limpio.codePointCount(0, limpio.length()) <= largo) {
            return limpio;
        }
        return limpio.substring(0, limpio.offsetByCodePoints(0, largo));
    }

    private static boolean vacio(String texto) {
        return texto == null || texto.isBlank();
    }
}
