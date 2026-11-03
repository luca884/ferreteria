package com.ferreteria.facturacion.service;

import com.ferreteria.facturacion.api.FacturaImpedidaException;
import com.ferreteria.facturacion.api.FacturacionParaVenta;
import com.ferreteria.facturacion.config.EjecutorFiscalConfig;
import com.ferreteria.facturacion.documento.BandejaFiscal;
import com.ferreteria.facturacion.documento.HojaFiscal;
import com.ferreteria.facturacion.model.Alicuota;
import com.ferreteria.facturacion.model.ComprobanteFiscal;
import com.ferreteria.facturacion.model.CondicionIvaEmisor;
import com.ferreteria.facturacion.model.CondicionIvaReceptor;
import com.ferreteria.facturacion.model.EncuadreFiscal;
import com.ferreteria.facturacion.model.EstadoFiscal;
import com.ferreteria.facturacion.model.ReceptorFiscal;
import com.ferreteria.facturacion.model.RenglonFiscal;
import com.ferreteria.facturacion.model.TipoDocumentoReceptor;
import com.ferreteria.facturacion.motor.AccionFiscalImpedidaException;
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
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * cuyo {@code FOR SHARE} devuelve esa misma instancia ya tomada. Las dos deciden con la misma regla y el mismo instante, el
 * de la venta, {@link PasosFiscales#puedeFacturar(Instant)} (la facturación habilitada, la credencial vigente en ese instante
 * y los datos de CU-01 completos): lo que {@code decidir} deja facturar, {@code registrar} lo da de alta.
 *
 * <p>Después del commit, sin transacción (advisory A2: la llamada a ARCA nunca va con una conexión tomada),
 * {@link #autorizar} atiende la serie del registro en el ejecutor de {@code facturacion} ({@link EjecutorFiscalConfig}) y la
 * espera un rato acotado ({@code app.facturacion.venta.espera}), medido con el reloj de pared y no con el {@code Clock}
 * (que en los tests está fijo). Mientras otra instancia tiene la serie, reintenta con una pausa corta; vencida la espera, la
 * atención en curso termina sola y no se reintenta más (lo pendiente lo levanta la venta siguiente de la serie o, desde la
 * rama 7, el job de reintento). Nunca lanza por ARCA: lo que no se resolvió vuelve en su estado actual y, si sigue en
 * trámite, la venta sale con el ticket provisorio y su registro queda marcado (S6), para avisar cuando llegue el CAE. Cada
 * lectura del estado es fresca, en su propia transacción ({@code open-in-view} está apagado).
 *
 * <p>La hoja de la factura, el listado y el cobro (cu-07-factura-mostrador C4) son solo lecturas, cada una en una
 * transacción de solo lectura (o en la de quien llama, si tiene una): {@link #documento} lee el registro y su desglose de
 * una vez y deja la apariencia a {@link HojaFiscal}; {@link #paraElMostrador} usa la regla de {@link #decidir} sin lock.
 *
 * <p>La bandeja de Ventas y «llegó el CAE» (cu-07-contingencias C1 y C7) también son lecturas, y lo que muestran lo decide
 * {@link BandejaFiscal}. Las dos acciones del Dueño (C2 y C3) las hace el motor; acá se traducen sus impedimentos a los del
 * puerto ({@link FacturaImpedidaException}), con el mismo texto.
 *
 * <p>La anulación de una venta (CU-08, rama 8) también la hace el motor, en la transacción de quien llama: la vía, la nota
 * de crédito copiada de la factura y la anulación interna. La nota de crédito se autoriza como una factura, con
 * {@link #autorizar} sobre su comprobante.
 */
@Service
public class FacturacionParaVentaImpl implements FacturacionParaVenta {

    private static final Logger log = LoggerFactory.getLogger(FacturacionParaVentaImpl.class);

    /** Lo que guarda el registro fiscal del receptor (V32): lo que sobra se corta. */
    private static final int LARGO_NOMBRE = 120;
    private static final int LARGO_DOMICILIO = 200;

    /**
     * Los estados que no cambian con otra atención: el resultado de ARCA, una alerta que espera a una persona, o la venta
     * que el Dueño dejó sin factura (rama 7).
     */
    private static final Set<EstadoFiscal> RESUELTOS = EnumSet.of(EstadoFiscal.AUTORIZADO, EstadoFiscal.RECHAZADO,
            EstadoFiscal.FUERA_DE_FECHA, EstadoFiscal.CAE_NO_COINCIDE, EstadoFiscal.SERIE_DESINCRONIZADA,
            EstadoFiscal.SIN_FACTURA);

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
     * Factura si la ferretería puede facturar en el instante de la venta, con la misma regla que el alta
     * ({@link PasosFiscales#puedeFacturar(Instant)}: la facturación habilitada con ambiente y punto de venta, la credencial
     * vigente en ese instante y los datos de CU-01 completos). Si no, la venta sale «sin factura», como sin facturación (D5):
     * sin controlar al receptor ni el tope de ARCA.
     */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Decision decidir(ReceptorVenta receptor, Instant instante) {
        Objects.requireNonNull(receptor, "Falta el receptor de la venta.");
        Objects.requireNonNull(instante, "Falta el instante de la venta.");
        // Siempre con FOR SHARE, también sin fila (lock de hueco): la decisión y el alta ven la misma configuración (L3).
        if (!pasos.puedeFacturar(instante)) {
            return Decision.SIN_FACTURA;
        }
        return new Decision(true, receptorCompleto(receptor), topeIdentificacion);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long registrar(long comprobanteId, ReceptorVenta receptor, List<RenglonParaFacturar> renglones, Instant fecha) {
        return registrar(comprobanteId, receptor, renglones, fecha, fecha);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long registrar(long comprobanteId, ReceptorVenta receptor, List<RenglonParaFacturar> renglones, Instant fecha,
            Instant decididaEn) {
        Objects.requireNonNull(receptor, "Falta el receptor de la venta.");
        Objects.requireNonNull(renglones, "Faltan los renglones de la venta.");
        Objects.requireNonNull(fecha, "Falta la fecha de la venta.");
        Objects.requireNonNull(decididaEn, "Falta el instante de la decisión.");
        AltaFiscal alta = new AltaFiscal(comprobanteId, receptorFiscal(receptor),
                renglones.stream().map(FacturacionParaVentaImpl::renglonFiscal).toList());
        return motor.registrar(alta, fecha, decididaEn);
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
            return conTicketProvisorioSiSigueEnTramite(comprobanteId);
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
            return conTicketProvisorioSiSigueEnTramite(comprobanteId);
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
     * El estado con que responde la venta cuando deja de esperar: si su registro sigue en trámite, la venta sale con el
     * ticket provisorio y el registro queda marcado (rama 7, S6), en la misma transacción que la lectura.
     */
    private Optional<EstadoFiscalVenta> conTicketProvisorioSiSigueEnTramite(long comprobanteId) {
        return pasos.salioConTicketProvisorio(comprobanteId).map(FacturacionParaVentaImpl::estadoDe);
    }

    // ---- La hoja, el listado y el cobro (cu-07-factura-mostrador C4): solo lecturas, la apariencia la decide HojaFiscal ----

    /** El registro y su desglose en la misma transacción de solo lectura: el estado y la hoja salen de una sola foto. */
    @Override
    public Optional<DocumentoFiscal> documento(long comprobanteId) {
        return lectura.execute(s -> registros.deComprobanteScoped(comprobanteId)
                .map(r -> HojaFiscal.de(r, pasos.alicuotas(r.getId()), estadoDe(r))));
    }

    @Override
    public Map<Long, ResumenFiscal> resumenes(Collection<Long> comprobanteIds) {
        Objects.requireNonNull(comprobanteIds, "Faltan las ventas.");
        List<ComprobanteFiscal> deLasVentas = lectura.execute(s -> registros.deComprobantesScoped(comprobanteIds));
        Map<Long, ResumenFiscal> resumenes = new HashMap<>();
        for (ComprobanteFiscal registro : deLasVentas) {
            resumenes.put(registro.getComprobanteId(), HojaFiscal.resumen(registro));
        }
        return Map.copyOf(resumenes);
    }

    @Override
    public List<Long> ventasConNumeroFiscal(Integer puntoVenta, long numero) {
        return lectura.execute(s -> registros.comprobantesConNumeroScoped(puntoVenta, numero));
    }

    /** La regla de {@link #decidir} sin lock ({@link PasosFiscales#emisionSinLock()}), y la letra con la función del alta. */
    @Override
    public Optional<FacturacionDelMostrador> paraElMostrador() {
        return pasos.emisionSinLock().map(emision -> new FacturacionDelMostrador(emision.ambiente().name(),
                letras(emision.condicionEmisor())));
    }

    // ---- La bandeja de Ventas, las acciones del Dueño y «llegó el CAE» (cu-07-contingencias C1 a C3 y C7) ----

    @Override
    public List<Long> ventasEnBandeja(Bandeja bandeja) {
        Objects.requireNonNull(bandeja, "Falta la bandeja.");
        return lectura.execute(s -> registros.comprobantesEnEstadosScoped(BandejaFiscal.estados(bandeja)));
    }

    /** El registro, su desglose y la serie en la misma transacción de solo lectura: la hoja y la bandeja, de una sola foto. */
    @Override
    public Optional<FacturaConsultada> consulta(long comprobanteId) {
        return lectura.execute(s -> registros.deComprobanteScoped(comprobanteId)
                .map(r -> BandejaFiscal.consultada(r, HojaFiscal.de(r, pasos.alicuotas(r.getId()), estadoDe(r)),
                        BandejaFiscal.rechazada(r) && !pasos.fechaEntraHoy(r), motivoFueraDeFecha(r))));
    }

    /** El motivo de una FUERA_DE_FECHA (cu-07-advertencias-contingencias S2); null en los demás estados. */
    private String motivoFueraDeFecha(ComprobanteFiscal registro) {
        if (registro.getEstado() != EstadoFiscal.FUERA_DE_FECHA) {
            return null;
        }
        PasosFiscales.FechasDelRegistro f = pasos.fechasDelRegistro(registro);
        return BandejaFiscal.motivoFueraDeFecha(f.fecha(), f.hoy(), f.ultimaAutorizada(), registro.esNotaDeCredito());
    }

    @Override
    public Map<Long, String> provisoriasAutorizadas(Collection<Long> comprobanteIds) {
        Objects.requireNonNull(comprobanteIds, "Faltan las ventas.");
        List<ComprobanteFiscal> provisorias = lectura.execute(s -> registros.provisoriasAutorizadasScoped(comprobanteIds));
        Map<Long, String> facturas = new HashMap<>();
        for (ComprobanteFiscal registro : provisorias) {
            facturas.put(registro.getComprobanteId(), BandejaFiscal.factura(registro));
        }
        return Map.copyOf(facturas);
    }

    @Override
    public void exigirFacturaRechazada(long comprobanteId) {
        try {
            pasos.exigirRechazada(comprobanteId);
        } catch (AccionFiscalImpedidaException e) {
            throw traducida(e);
        }
    }

    /** El receptor y los renglones, traducidos como en el alta de la venta; el reintento lo hace el motor. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long reintentar(long comprobanteId, ReceptorVenta receptor, List<RenglonParaFacturar> renglones,
            boolean fechaDeHoy) {
        Objects.requireNonNull(receptor, "Falta el receptor de la venta.");
        Objects.requireNonNull(renglones, "Faltan los renglones de la venta.");
        AltaFiscal alta = new AltaFiscal(comprobanteId, receptorFiscal(receptor),
                renglones.stream().map(FacturacionParaVentaImpl::renglonFiscal).toList());
        try {
            return motor.reintentar(alta, fechaDeHoy);
        } catch (AccionFiscalImpedidaException e) {
            throw traducida(e);
        }
    }

    @Override
    public void dejarSinFactura(long comprobanteId, String motivo, long usuarioId) {
        try {
            motor.dejarSinFactura(comprobanteId, motivo, usuarioId);
        } catch (AccionFiscalImpedidaException e) {
            throw traducida(e);
        }
    }

    // ---- La anulación de una venta (CU-08, rama 8; C1 de cu-08-nota-de-credito): la hace el motor ----

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public ViaDeAnulacion tomarParaAnular(long ventaId) {
        try {
            return switch (motor.tomarParaAnular(ventaId)) {
                case NOTA_DE_CREDITO -> ViaDeAnulacion.NOTA_DE_CREDITO;
                case ANULACION_INTERNA -> ViaDeAnulacion.ANULACION_INTERNA;
            };
        } catch (AccionFiscalImpedidaException e) {
            throw traducida(e);
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long registrarNotaDeCredito(long notaId, long ventaId) {
        return motor.registrarNotaDeCredito(notaId, ventaId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void cerrarSinCae(long ventaId, String motivo, long usuarioId) {
        motor.cerrarSinCae(ventaId, motivo, usuarioId);
    }

    /** El impedimento del motor, con el mismo código y el mismo texto, como lo conoce el puerto. */
    private static FacturaImpedidaException traducida(AccionFiscalImpedidaException e) {
        FacturaImpedidaException.Impedimento impedimento = switch (e.impedimento()) {
            case SIN_FACTURA_RECHAZADA -> FacturaImpedidaException.Impedimento.SIN_FACTURA_RECHAZADA;
            case FECHA_NUEVA_REQUERIDA -> FacturaImpedidaException.Impedimento.FECHA_NUEVA_REQUERIDA;
            case FACTURACION_FRENADA -> FacturaImpedidaException.Impedimento.FACTURACION_FRENADA;
            case ESPERAR_CAE -> FacturaImpedidaException.Impedimento.ESPERAR_CAE;
            case OTRO_AMBIENTE -> FacturaImpedidaException.Impedimento.OTRO_AMBIENTE;
        };
        return new FacturaImpedidaException(impedimento, e.getMessage());
    }

    /** La letra que saldría para cada condición del cliente, en el orden del puerto, con {@link EncuadreFiscal#de}. */
    static Map<CondicionIva, String> letras(CondicionIvaEmisor emisor) {
        Map<CondicionIva, String> letras = new EnumMap<>(CondicionIva.class);
        for (CondicionIva condicion : CondicionIva.values()) {
            letras.put(condicion, EncuadreFiscal.de(emisor, condicionReceptor(condicion)).letra().name());
        }
        return letras;
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
        CondicionIvaReceptor condicion = condicionReceptor(receptor.condicion());
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

    /** La condición del puerto, como la conoce {@code facturacion}. */
    static CondicionIvaReceptor condicionReceptor(CondicionIva condicion) {
        return switch (condicion) {
            case CONSUMIDOR_FINAL -> CondicionIvaReceptor.CONSUMIDOR_FINAL;
            case RESPONSABLE_INSCRIPTO -> CondicionIvaReceptor.RESPONSABLE_INSCRIPTO;
            case MONOTRIBUTISTA -> CondicionIvaReceptor.MONOTRIBUTISTA;
            case EXENTO -> CondicionIvaReceptor.EXENTO;
        };
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
