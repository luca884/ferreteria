package com.ferreteria.facturacion.motor;

import com.ferreteria.common.exception.ReglaDeNegocioException;
import com.ferreteria.facturacion.model.AlicuotaDesglosada;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.model.ClaseComprobante;
import com.ferreteria.facturacion.model.ComprobanteFiscal;
import com.ferreteria.facturacion.model.ComprobanteFiscalAlicuota;
import com.ferreteria.facturacion.model.CondicionIvaEmisor;
import com.ferreteria.facturacion.model.ConfigArca;
import com.ferreteria.facturacion.model.DesgloseFiscal;
import com.ferreteria.facturacion.model.EmisorFiscal;
import com.ferreteria.facturacion.model.EncuadreFiscal;
import com.ferreteria.facturacion.model.EstadoFiscal;
import com.ferreteria.facturacion.model.EstadoIntento;
import com.ferreteria.facturacion.model.IntentoFiscal;
import com.ferreteria.facturacion.model.SerieFiscal;
import com.ferreteria.facturacion.repository.ComprobanteFiscalAlicuotaRepository;
import com.ferreteria.facturacion.repository.ComprobanteFiscalRepository;
import com.ferreteria.facturacion.repository.ConfigArcaRepository;
import com.ferreteria.facturacion.repository.IntentoFiscalRepository;
import com.ferreteria.facturacion.repository.SerieFiscalRepository;
import com.ferreteria.ferreteria.model.DatosFiscales;
import com.ferreteria.ferreteria.repository.FerreteriaRepository;
import com.ferreteria.tenant.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Los pasos del motor, cada uno en su transacción corta, y el único escritor del registro fiscal, su desglose, sus intentos
 * y la serie (regla de {@code ModulosArchTest}). No conoce el puerto de WSFEv1: ninguna llamada a ARCA ocurre con una
 * transacción o una conexión tomada. Todo es de la ferretería del request ({@code TenantContext}).
 *
 * <p>Cada paso que cambia algo lo hace con UPDATE condicionales sobre el estado de antes y exige que toquen una fila: si
 * no, otra instancia siguió con la serie, y el paso se revierte entero ({@link PasoDescartadoException}). Los locks se
 * toman siempre en el mismo orden (serie → registro → intento), así dos pasos no se esperan en círculo.
 */
@Component
public class PasosFiscales {

    static final String NO_HABILITADA =
            "La facturación electrónica no está habilitada en esta ferretería: la habilita el super admin.";
    static final String FALTAN_DATOS = "Faltan datos de la ferretería para facturar (razón social, condición de IVA, "
            + "domicilio comercial, inicio de actividades o Ingresos Brutos). Los carga el Dueño en «Datos de la ferretería».";

    private final ComprobanteFiscalRepository registros;
    private final ComprobanteFiscalAlicuotaRepository alicuotas;
    private final IntentoFiscalRepository intentos;
    private final SerieFiscalRepository series;
    private final ConfigArcaRepository configs;
    private final FerreteriaRepository ferreterias;

    public PasosFiscales(ComprobanteFiscalRepository registros, ComprobanteFiscalAlicuotaRepository alicuotas,
            IntentoFiscalRepository intentos, SerieFiscalRepository series, ConfigArcaRepository configs,
            FerreteriaRepository ferreterias) {
        this.registros = registros;
        this.alicuotas = alicuotas;
        this.intentos = intentos;
        this.series = series;
        this.configs = configs;
        this.ferreterias = ferreterias;
    }

    // ---- Alta ----

    /**
     * Da de alta el registro PENDIENTE de una venta y su desglose, con lo que va a ARCA congelado. Se suma a la transacción
     * de quien llama (la de la venta, desde la rama 3) y empieza leyendo {@code config_arca} con {@code FOR SHARE} hasta el
     * commit (L12): mientras tanto el panel no cambia el ambiente. Sin facturación habilitada (o sin ambiente) se niega.
     */
    @Transactional
    public long registrar(AltaFiscal alta, Instant ahora) {
        ConfigArca config = configs.bloquearCompartidaScoped()
                .filter(ConfigArca::puedeFacturar)
                .orElseThrow(() -> new ReglaDeNegocioException(NO_HABILITADA));
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        EmisorFiscal emisor = emisor(ferreteriaId);
        EncuadreFiscal encuadre = EncuadreFiscal.de(emisor.condicionIva(), alta.receptor().condicionIva());
        DesgloseFiscal desglose = DesgloseFiscal.de(encuadre.letra(), alta.renglones());

        ComprobanteFiscal registro = registros.save(ComprobanteFiscal.pendiente(ferreteriaId, alta.comprobanteId(),
                config.getAmbiente(), config.getPuntoVenta(), ClaseComprobante.FACTURA, encuadre, alta.receptor(), emisor,
                desglose, ahora));
        for (AlicuotaDesglosada alicuota : desglose.alicuotas()) {
            alicuotas.save(ComprobanteFiscalAlicuota.de(ferreteriaId, registro.getId(), alicuota));
        }
        return registro.getId();
    }

    /** La copia del emisor: los datos de CU-01 paso 1 de la ferretería, que tienen que estar completos. */
    private EmisorFiscal emisor(Long ferreteriaId) {
        // findById es valido: la ferreteria es la raiz del tenant, no un repositorio de tenant.
        DatosFiscales datos = DatosFiscales.de(ferreterias.findById(ferreteriaId)
                .orElseThrow(() -> new ReglaDeNegocioException(FALTAN_DATOS)));
        if (vacio(datos.razonSocial()) || datos.condicionIva() == null || vacio(datos.domicilioComercial())
                || datos.inicioActividades() == null || vacio(datos.ingresosBrutos())) {
            throw new ReglaDeNegocioException(FALTAN_DATOS);
        }
        return new EmisorFiscal(datos.cuit(), datos.razonSocial(), CondicionIvaEmisor.valueOf(datos.condicionIva().name()),
                datos.domicilioComercial(), datos.ingresosBrutos(), datos.inicioActividades());
    }

    private static boolean vacio(String texto) {
        return texto == null || texto.isBlank();
    }

    // ---- Serie y lease ----

    /** El id de la serie; la crea si no existe. */
    @Transactional
    public long asegurarSerie(Ambiente ambiente, int puntoVenta, int cbteTipo) {
        return series.asegurarScoped(ambiente, puntoVenta, cbteTipo);
    }

    /** Toma la serie si está libre o su toma venció. */
    @Transactional
    public boolean tomar(long serieId, String dueno, Instant ahora, Instant vence) {
        return series.tomarScoped(serieId, dueno, ahora, vence) == 1;
    }

    /** Estira la toma; si se perdió, {@link PasoDescartadoException}. */
    @Transactional
    public void renovar(long serieId, String dueno, Instant ahora, Instant vence) {
        renovarOFallar(serieId, dueno, ahora, vence);
    }

    /** Suelta la toma si sigue siendo propia. */
    @Transactional
    public void soltar(long serieId, String dueno) {
        series.soltarScoped(serieId, dueno);
    }

    /** Lo último autorizado según ARCA, con la toma propia y vigente. */
    @Transactional
    public void sincronizar(long serieId, String dueno, Instant ahora, long ultimo, LocalDate fecha) {
        exigir(series.sincronizarScoped(serieId, dueno, ahora, ultimo, fecha), "la serie ya no es de esta toma");
    }

    // ---- Lecturas (cada una en su transacción de solo lectura; devuelven entidades sueltas) ----

    @Transactional(readOnly = true)
    public SerieFiscal serie(long serieId) {
        return series.findScoped(serieId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public Optional<IntentoFiscal> intentoAbierto(long serieId) {
        return intentos.abiertoDeSerieScoped(serieId);
    }

    @Transactional(readOnly = true)
    public ComprobanteFiscal registro(long registroId) {
        return registros.findScoped(registroId).orElseThrow();
    }

    @Transactional(readOnly = true)
    public Optional<ComprobanteFiscal> siguientePendiente(SerieFiscal serie) {
        return registros.siguientePendienteScoped(serie.getAmbiente(), serie.getPuntoVenta(), serie.getCbteTipo());
    }

    @Transactional(readOnly = true)
    public List<ComprobanteFiscalAlicuota> alicuotas(long registroId) {
        return alicuotas.deRegistroScoped(registroId);
    }

    // ---- Envío ----

    /** La CbteFch no entra: PENDIENTE → FUERA_DE_FECHA, sin número y sin intento. */
    @Transactional
    public void fueraDeFecha(long registroId) {
        exigir(registros.fueraDeFechaScoped(registroId), "el registro ya no está pendiente");
    }

    /**
     * Antes de llamar: con la serie todavía propia (estirada para la llamada), el registro pasa a EN_CURSO con su número y
     * su CbteFch, y se guarda el intento ABIERTO con el pedido tal cual. Devuelve el id del intento.
     */
    @Transactional
    public long abrirIntento(long serieId, String dueno, long registroId, long numero, LocalDate cbteFch, String pedido,
            Instant ahora, Instant vence) {
        renovarOFallar(serieId, dueno, ahora, vence);
        exigir(registros.numerarScoped(registroId, numero, cbteFch), "el registro ya no está pendiente");
        return intentos.save(IntentoFiscal.abierto(TenantContext.requireFerreteriaId(), registroId, serieId, numero, cbteFch,
                pedido, ahora)).getId();
    }

    /** A: el registro AUTORIZADO con su CAE, el intento cerrado y el número como último de la serie. */
    @Transactional
    public void autorizado(long serieId, long registroId, long intentoId, long numero, LocalDate cbteFch, String cae,
            LocalDate vencimiento, String observaciones, String errores, String respuesta, Instant ahora) {
        series.registrarAutorizadoScoped(serieId, numero, cbteFch);
        exigir(registros.autorizarScoped(registroId, numero, EstadoFiscal.EN_CURSO, cae, vencimiento, observaciones,
                errores), "el registro ya no está en curso");
        exigir(intentos.terminarScoped(intentoId, EstadoIntento.AUTORIZADO, "A", respuesta, ahora, ahora),
                "el intento ya no está abierto");
    }

    /** R: el registro RECHAZADO sin número (se libera) y el intento cerrado, con lo que dijo ARCA tal cual. */
    @Transactional
    public void rechazado(long registroId, long intentoId, long numero, String observaciones, String errores,
            String respuesta, Instant ahora) {
        exigir(registros.rechazarScoped(registroId, numero, observaciones, errores), "el registro ya no está en curso");
        exigir(intentos.terminarScoped(intentoId, EstadoIntento.RECHAZADO, "R", respuesta, ahora, ahora),
                "el intento ya no está abierto");
    }

    /** El pedido no llegó: el intento NO_LLEGO y el registro otra vez PENDIENTE, sin número. */
    @Transactional
    public void noLlego(long registroId, long intentoId, long numero, String respuesta, Instant ahora) {
        exigir(registros.liberarScoped(registroId, numero), "el registro ya no está en curso");
        exigir(intentos.terminarScoped(intentoId, EstadoIntento.NO_LLEGO, null, respuesta, ahora, ahora),
                "el intento ya no está abierto");
    }

    /** No se sabe si ARCA lo autorizó: el registro y el intento EN_VERIFICACION, que traban la serie. */
    @Transactional
    public void enVerificacion(long registroId, long intentoId, long numero, String respuesta, Instant ahora) {
        exigir(registros.pasarScoped(registroId, numero, EstadoFiscal.EN_CURSO, EstadoFiscal.EN_VERIFICACION),
                "el registro ya no está en curso");
        exigir(intentos.terminarScoped(intentoId, EstadoIntento.EN_VERIFICACION, null, respuesta, ahora, null),
                "el intento ya no está abierto");
    }

    // ---- Verificación ----

    /** ARCA tiene N con todos los campos enviados: se adopta su CAE. */
    @Transactional
    public void adoptar(long serieId, long registroId, long intentoId, long numero, LocalDate cbteFch, String cae,
            LocalDate vencimiento, String observaciones, String verificacion, Instant ahora) {
        series.registrarAutorizadoScoped(serieId, numero, cbteFch);
        exigir(registros.autorizarScoped(registroId, numero, EstadoFiscal.EN_VERIFICACION, cae, vencimiento,
                observaciones, "[]"), "el registro ya no está en verificación");
        exigir(intentos.resolverScoped(intentoId, EstadoIntento.AUTORIZADO, "A", verificacion, ahora),
                "el intento ya no está en verificación");
    }

    /** Alerta (CAE_NO_COINCIDE o SERIE_DESINCRONIZADA): conserva el número y traba la serie hasta que la vea una persona. */
    @Transactional
    public void alertar(long registroId, long intentoId, long numero, EstadoFiscal alerta, String verificacion) {
        exigir(registros.pasarScoped(registroId, numero, EstadoFiscal.EN_VERIFICACION, alerta),
                "el registro ya no está en verificación");
        exigir(intentos.resolverScoped(intentoId, EstadoIntento.ALERTA, null, verificacion, null),
                "el intento ya no está en verificación");
    }

    /**
     * ARCA va en N−1: el intento viejo se cierra NO_LLEGO y se abre otro con el mismo número, la misma fecha y el mismo
     * pedido, con la serie estirada para la llamada, todo en una transacción. Devuelve el id del intento nuevo.
     */
    @Transactional
    public long reenviar(long serieId, String dueno, long registroId, long intentoViejoId, long numero, LocalDate cbteFch,
            String pedido, String verificacion, Instant ahora, Instant vence) {
        renovarOFallar(serieId, dueno, ahora, vence);
        exigir(registros.pasarScoped(registroId, numero, EstadoFiscal.EN_VERIFICACION, EstadoFiscal.EN_CURSO),
                "el registro ya no está en verificación");
        exigir(intentos.resolverScoped(intentoViejoId, EstadoIntento.NO_LLEGO, null, verificacion, ahora),
                "el intento ya no está en verificación");
        return intentos.save(IntentoFiscal.abierto(TenantContext.requireFerreteriaId(), registroId, serieId, numero, cbteFch,
                pedido, ahora)).getId();
    }

    /**
     * ARCA va en N−1 pero la CbteFch ya no entra en la ventana: no se reenvía. El intento se cierra NO_LLEGO y el registro
     * pasa a FUERA_DE_FECHA sin número, que queda libre para el siguiente.
     */
    @Transactional
    public void fueraDeFechaSinReenvio(long serieId, String dueno, long registroId, long intentoId, long numero,
            String verificacion, Instant ahora, Instant vence) {
        renovarOFallar(serieId, dueno, ahora, vence);
        exigir(registros.vencerSinReenvioScoped(registroId, numero), "el registro ya no está en verificación");
        exigir(intentos.resolverScoped(intentoId, EstadoIntento.NO_LLEGO, null, verificacion, ahora),
                "el intento ya no está en verificación");
    }

    private void renovarOFallar(long serieId, String dueno, Instant ahora, Instant vence) {
        exigir(series.renovarScoped(serieId, dueno, ahora, vence), "la toma de la serie venció o la tiene otra instancia");
    }

    private static void exigir(int filas, String motivo) {
        if (filas != 1) {
            throw new PasoDescartadoException("Paso descartado: " + motivo + ".");
        }
    }
}
