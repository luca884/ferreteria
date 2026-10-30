package com.ferreteria.facturacion.motor;

import com.ferreteria.common.HoraArgentina;
import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.ReglaDeNegocioException;
import com.ferreteria.facturacion.credencial.RevisionDeCredencial;
import com.ferreteria.facturacion.model.AlicuotaDesglosada;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.model.ClaseComprobante;
import com.ferreteria.facturacion.model.ClaveDeSerie;
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
import com.ferreteria.facturacion.model.RegistroVigente;
import com.ferreteria.facturacion.model.SerieFiscal;
import com.ferreteria.facturacion.model.VentanaCbteFch;
import com.ferreteria.facturacion.repository.ComprobanteFiscalAlicuotaRepository;
import com.ferreteria.facturacion.repository.ComprobanteFiscalRepository;
import com.ferreteria.facturacion.repository.ConfigArcaRepository;
import com.ferreteria.facturacion.repository.IntentoFiscalRepository;
import com.ferreteria.facturacion.repository.SerieFiscalRepository;
import com.ferreteria.ferreteria.model.DatosFiscales;
import com.ferreteria.ferreteria.repository.FerreteriaRepository;
import com.ferreteria.tenant.TenantContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
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
    static final String SIN_CREDENCIAL = "La facturación con ARCA está bloqueada: el certificado falta, venció o no se "
            + "puede leer. El Dueño lo renueva en «Facturación».";
    /** WSAA rechazó el certificado (rama 6, C4): la facturación está frenada hasta otro certificado o «Probar conexión». */
    static final String FRENADA = "La facturación con ARCA está frenada: ARCA rechazó el certificado (%s).";
    /**
     * «Reintentar» con la facturación frenada (C2 de cu-07-contingencias): «La facturación está frenada: <motivo>.», con el
     * motivo de la regla del alta en corto.
     */
    static final String FACTURACION_FRENADA = "La facturación está frenada: %s.";
    static final String FRENADA_NO_HABILITADA = "no está habilitada en esta ferretería (la habilita el super admin)";
    static final String FRENADA_FALTAN_DATOS = "faltan datos de la ferretería (los carga el Dueño en «Datos de la "
            + "ferretería»)";
    static final String FRENADA_SIN_CREDENCIAL = "el certificado de ARCA falta, venció o no se puede leer (el Dueño lo "
            + "renueva en «Facturación»)";
    static final String FRENADA_POR_ARCA = "ARCA rechazó el certificado (%s)";
    /** El motivo de «Dejar sin factura» (C3, S12). */
    static final int LARGO_MOTIVO = 200;
    static final String MOTIVO_VACIO = "Contá por qué la venta queda sin factura.";
    static final String MOTIVO_LARGO = "El motivo tiene hasta 200 caracteres.";
    /** El motivo de la anulación interna (CU-08 punto 3, L4 de cu-08-nota-de-credito); el de largo es el mismo. */
    static final String MOTIVO_DE_ANULACION_VACIO = "Contá por qué se anula la venta.";

    /** ARCA trabaja en hora de Buenos Aires: de ahí salen la CbteFch y el día del envío. */
    private static final ZoneId ZONA_ARCA = HoraArgentina.ZONA;

    private final ComprobanteFiscalRepository registros;
    private final ComprobanteFiscalAlicuotaRepository alicuotas;
    private final IntentoFiscalRepository intentos;
    private final SerieFiscalRepository series;
    private final ConfigArcaRepository configs;
    private final FerreteriaRepository ferreterias;
    private final RevisionDeCredencial credencial;
    private final PoliticaDeReintento reintento;
    private final Clock reloj;

    public PasosFiscales(ComprobanteFiscalRepository registros, ComprobanteFiscalAlicuotaRepository alicuotas,
            IntentoFiscalRepository intentos, SerieFiscalRepository series, ConfigArcaRepository configs,
            FerreteriaRepository ferreterias, RevisionDeCredencial credencial, PoliticaDeReintento reintento,
            Clock reloj) {
        this.registros = registros;
        this.alicuotas = alicuotas;
        this.intentos = intentos;
        this.series = series;
        this.configs = configs;
        this.ferreterias = ferreterias;
        this.credencial = credencial;
        this.reintento = reintento;
        this.reloj = reloj;
    }

    // ---- Alta ----

    /**
     * Si la ferretería del request factura en {@code ahora}, el instante de la venta: la regla única que comparten la
     * decisión de la venta ({@code FacturacionParaVenta.decidir}) y el alta del registro ({@link #registrar}), así una venta
     * que se decidió facturable no se cae al darla de alta (D5, B1 de cu-07-venta-facturada). Factura si su configuración de
     * ARCA tiene la facturación habilitada con ambiente y punto de venta, sin el bloqueo de ARCA de C4
     * ({@link ConfigArca#puedeFacturar()}; con el bloqueo, el motivo lleva el código de WSAA); en
     * homologación o producción, si además tiene una credencial activa de ese ambiente, vigente en {@code ahora} y legible
     * ({@link RevisionDeCredencial}, rama 5, S8); y si están completos los datos de CU-01 paso 1 que la factura imprime. Lee
     * {@code config_arca} y la credencial con {@code FOR SHARE} hasta el fin de la transacción de quien llama (también sin
     * fila: lock de hueco), así que exige una abierta. No escribe nada.
     *
     * <p>Las dos llamadas van en la transacción de la venta y ven lo mismo: la configuración, porque el lock compartido no
     * deja que el panel la cambie hasta el commit; los datos de la ferretería, porque la segunda lectura devuelve la
     * instancia que la primera dejó en el contexto de persistencia (y, aun sin ella, la lectura sin lock de REPEATABLE READ
     * devuelve la misma foto); y la vigencia de la credencial, porque las dos la miran en el mismo instante, el de la venta,
     * y no con el {@code Clock}, que avanza entre una y otra (rama 6, S15). Si el Dueño vacía un dato entre las dos, la venta
     * que se decidió facturable se da de alta con los datos que vio al decidir.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean puedeFacturar(Instant ahora) {
        return emision(ahora).impedimento() == null;
    }

    /**
     * Da de alta el registro PENDIENTE de una venta y su desglose, con lo que va a ARCA congelado y {@code registradoEn}, el
     * instante de la venta, como fecha. Se suma a la transacción de quien llama (la de la venta, desde la rama 3) y empieza
     * leyendo {@code config_arca} con {@code FOR SHARE} hasta el commit (L12): mientras tanto el panel no cambia el ambiente.
     * Si la ferretería no factura en {@code revisadaEn}, el instante en que la venta decidió ({@link #puedeFacturar(Instant)}),
     * se niega: sin facturación habilitada (o sin ambiente), sin una credencial vigente en ese instante, o con un dato de
     * CU-01 vacío.
     */
    @Transactional
    public long registrar(AltaFiscal alta, Instant registradoEn, Instant revisadaEn) {
        Instant ahora = registradoEn;
        Emision emision = emision(revisadaEn);
        if (emision.impedimento() != null) {
            throw new ReglaDeNegocioException(emision.impedimento());
        }
        return alta(emision, alta, ahora);
    }

    /**
     * El alta del registro PENDIENTE con lo que va a ARCA congelado: el ambiente y el punto de venta de la configuración,
     * el encuadre (letra y CbteTipo) de la condición del emisor y la del receptor, el desglose de los renglones con esa
     * letra, el receptor y el emisor; y su desglose por alícuota. Lo comparten la venta y el reintento del Dueño.
     */
    private long alta(Emision emision, AltaFiscal alta, Instant registradoEn) {
        ConfigArca config = emision.config();
        EmisorFiscal emisor = emision.emisor();
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        EncuadreFiscal encuadre = EncuadreFiscal.de(emisor.condicionIva(), alta.receptor().condicionIva());
        DesgloseFiscal desglose = DesgloseFiscal.de(encuadre.letra(), alta.renglones());

        ComprobanteFiscal registro = registros.save(ComprobanteFiscal.pendiente(ferreteriaId, alta.comprobanteId(),
                config.getAmbiente(), config.getPuntoVenta(), ClaseComprobante.FACTURA, encuadre, alta.receptor(), emisor,
                desglose, registradoEn));
        for (AlicuotaDesglosada alicuota : desglose.alicuotas()) {
            alicuotas.save(ComprobanteFiscalAlicuota.de(ferreteriaId, registro.getId(), alicuota));
        }
        return registro.getId();
    }

    /**
     * Con qué factura la ferretería del request: su configuración de ARCA y su emisor, o por qué no factura.
     *
     * @param impedimento null si factura; si no, el motivo que da {@link #registrar} (y entonces lo demás es null)
     * @param frenada el mismo motivo en corto, para el mensaje de «Reintentar» ({@link #FACTURACION_FRENADA}); null si
     *     factura
     */
    private record Emision(ConfigArca config, EmisorFiscal emisor, String impedimento, String frenada) {

        static Emision impedida(String impedimento, String frenada) {
            return new Emision(null, null, impedimento, frenada);
        }
    }

    /**
     * Con qué factura hoy la ferretería del request, si factura (cu-07-factura-mostrador C3): la regla de
     * {@link #puedeFacturar(Instant)}, pero leyendo {@code config_arca} sin lock y con la hora actual del {@code Clock},
     * porque no decide nada (lo anticipa el cobro).
     *
     * @param ambiente el ambiente de ARCA de la configuración
     * @param condicionEmisor la condición frente al IVA de la ferretería, de sus datos de CU-01
     */
    public record EmisionVigente(Ambiente ambiente, CondicionIvaEmisor condicionEmisor) {}

    /**
     * Vacío si la venta de ahora saldría sin factura (la misma regla que {@link #puedeFacturar(Instant)}, con la hora del
     * {@code Clock}), sin lock.
     */
    @Transactional(readOnly = true)
    public Optional<EmisionVigente> emisionSinLock() {
        Emision emision = emision(configs.deFerreteriaScoped(), false, Instant.now(reloj));
        if (emision.impedimento() != null) {
            return Optional.empty();
        }
        return Optional.of(new EmisionVigente(emision.config().getAmbiente(), emision.emisor().condicionIva()));
    }

    /**
     * La regla de {@link #puedeFacturar(Instant)}: primero la configuración (con su {@code FOR SHARE}), después la
     * credencial (con el suyo, en el mismo orden que quien activa un certificado) y el emisor.
     */
    private Emision emision(Instant ahora) {
        return emision(configs.bloquearCompartidaScoped(), true, ahora);
    }

    /**
     * La regla, con la configuración ya leída (con lock o sin él): habilitada con ambiente y punto de venta, y sin el bloqueo
     * de ARCA (que, con la facturación habilitada, da su propio motivo, {@link #FRENADA}); en homologación
     * o producción, una credencial activa de ese ambiente, vigente en {@code ahora} y legible (rama 5, S8; la lee con lock
     * si {@code conLock}); y el emisor. Desde la rama 6, SIMULADOR también exige credencial.
     */
    private Emision emision(Optional<ConfigArca> leida, boolean conLock, Instant ahora) {
        Optional<ConfigArca> config = leida.filter(ConfigArca::puedeFacturar);
        if (config.isEmpty()) {
            return leida.filter(c -> c.isFacturacionHabilitada() && c.estaBloqueada())
                    .map(c -> Emision.impedida(String.format(FRENADA, c.getBloqueoCodigo()),
                            String.format(FRENADA_POR_ARCA, c.getBloqueoCodigo())))
                    .orElseGet(() -> Emision.impedida(NO_HABILITADA, FRENADA_NO_HABILITADA));
        }
        if (!credencial.dejaFacturar(config.get(), ahora, conLock)) {
            return Emision.impedida(SIN_CREDENCIAL, FRENADA_SIN_CREDENCIAL);
        }
        return emisor(TenantContext.requireFerreteriaId())
                .map(emisor -> new Emision(config.get(), emisor, null, null))
                .orElseGet(() -> Emision.impedida(FALTAN_DATOS, FRENADA_FALTAN_DATOS));
    }

    /** La copia del emisor: los datos de CU-01 paso 1 de la ferretería; vacío si falta alguno de los que se imprimen. */
    private Optional<EmisorFiscal> emisor(Long ferreteriaId) {
        // findById es valido: la ferreteria es la raiz del tenant, no un repositorio de tenant.
        return ferreterias.findById(ferreteriaId)
                .map(DatosFiscales::de)
                .filter(PasosFiscales::completos)
                .map(datos -> new EmisorFiscal(datos.cuit(), datos.razonSocial(),
                        CondicionIvaEmisor.valueOf(datos.condicionIva().name()), datos.domicilioComercial(),
                        datos.ingresosBrutos(), datos.inicioActividades()));
    }

    private static boolean completos(DatosFiscales datos) {
        return !vacio(datos.razonSocial()) && datos.condicionIva() != null && !vacio(datos.domicilioComercial())
                && datos.inicioActividades() != null && !vacio(datos.ingresosBrutos());
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

    /** Si la serie del registro tiene otro registro con un número mayor que {@code ultimo} (el 10016, C6). */
    @Transactional(readOnly = true)
    public boolean hayNumeroDespuesDe(ComprobanteFiscal registro, long ultimo) {
        return registros.contarNumerosDespuesDeScoped(registro.getId(), registro.getAmbiente(), registro.getPuntoVenta(),
                registro.getCbteTipo(), ultimo) > 0;
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

    /**
     * A: el registro AUTORIZADO con su CAE, el intento cerrado y el número como último de la serie, cuyo backoff vuelve a
     * cero (C5: ARCA contesta).
     */
    @Transactional
    public void autorizado(long serieId, long registroId, long intentoId, long numero, LocalDate cbteFch, String cae,
            LocalDate vencimiento, String observaciones, String errores, String respuesta, Instant ahora) {
        series.registrarAutorizadoScoped(serieId, numero, cbteFch);
        series.reiniciarBackoffScoped(serieId);
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

    /** ARCA tiene N con todos los campos enviados: se adopta su CAE, y el backoff de la serie vuelve a cero. */
    @Transactional
    public void adoptar(long serieId, long registroId, long intentoId, long numero, LocalDate cbteFch, String cae,
            LocalDate vencimiento, String observaciones, String verificacion, Instant ahora) {
        series.registrarAutorizadoScoped(serieId, numero, cbteFch);
        series.reiniciarBackoffScoped(serieId);
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

    // ---- El 10016 (rama 7, C6) ----

    /**
     * 10016 con un número que no era el próximo de ARCA: la serie se resincroniza con lo que dice ARCA (también hacia
     * atrás), el registro vuelve a PENDIENTE sin número para renumerarse y el intento se cierra RECHAZADO, con la respuesta y
     * el último autorizado tal cual. Todo con la serie todavía propia.
     */
    @Transactional
    public void renumerar(long serieId, String dueno, long registroId, long intentoId, long numero, long ultimo,
            LocalDate fecha, String respuesta, String verificacion, Instant ahora, Instant vence) {
        renovarOFallar(serieId, dueno, ahora, vence);
        exigir(series.sincronizarScoped(serieId, dueno, ahora, ultimo, fecha), "la serie ya no es de esta toma");
        exigir(registros.liberarScoped(registroId, numero), "el registro ya no está en curso");
        exigir(intentos.rechazarConVerificacionScoped(intentoId, respuesta, verificacion, ahora),
                "el intento ya no está abierto");
    }

    /**
     * 10016 con ARCA atrás de números que la serie ya tiene: renumerar chocaría con ellos o repetiría un número que ARCA
     * perdió. El registro pasa a SERIE_DESINCRONIZADA con el número enviado y el intento a ALERTA, con la respuesta y el
     * último autorizado tal cual; la caché de la serie no se toca. Traba la serie hasta que la vea una persona.
     */
    @Transactional
    public void desincronizadaPorArca(long serieId, String dueno, long registroId, long intentoId, long numero,
            String respuesta, String verificacion, Instant ahora, Instant vence) {
        renovarOFallar(serieId, dueno, ahora, vence);
        exigir(registros.pasarScoped(registroId, numero, EstadoFiscal.EN_CURSO, EstadoFiscal.SERIE_DESINCRONIZADA),
                "el registro ya no está en curso");
        exigir(intentos.alertarConVerificacionScoped(intentoId, respuesta, verificacion, ahora),
                "el intento ya no está abierto");
    }

    /**
     * 10016 con el número próximo: el problema es la CbteFch. El registro pasa a FUERA_DE_FECHA sin número, con lo que dijo
     * ARCA; el intento se cierra RECHAZADO; la serie aprende la fecha del último de ARCA. El número sigue libre.
     */
    @Transactional
    public void fueraDeFechaPorArca(long serieId, String dueno, long registroId, long intentoId, long numero, long ultimo,
            LocalDate fecha, String observaciones, String errores, String respuesta, String verificacion, Instant ahora,
            Instant vence) {
        renovarOFallar(serieId, dueno, ahora, vence);
        exigir(series.sincronizarScoped(serieId, dueno, ahora, ultimo, fecha), "la serie ya no es de esta toma");
        exigir(registros.fueraDeFechaPorArcaScoped(registroId, numero, observaciones, errores),
                "el registro ya no está en curso");
        exigir(intentos.rechazarConVerificacionScoped(intentoId, respuesta, verificacion, ahora),
                "el intento ya no está abierto");
    }

    /**
     * 10016 sin poder preguntar a ARCA su último autorizado: no se sabe si fue el número o la fecha. El registro vuelve a
     * PENDIENTE sin número (el número no se consumió) y el intento se cierra RECHAZADO con la respuesta; la serie no se toca.
     * La atención siguiente lo vuelve a mandar y, con otro 10016, lo entiende.
     */
    @Transactional
    public void rechazadoSinEntender(long registroId, long intentoId, long numero, String respuesta, Instant ahora) {
        exigir(registros.liberarScoped(registroId, numero), "el registro ya no está en curso");
        exigir(intentos.rechazarConVerificacionScoped(intentoId, respuesta, null, ahora), "el intento ya no está abierto");
    }

    // ---- El job de reintento (rama 7, S2 y C5) ----

    /**
     * Las series de la ferretería del request que el job tiene que atender en {@code ahora}: con algún registro en trámite,
     * sin backoff o con el próximo reintento vencido, y sin alertas (S13: las resuelve soporte). FIFO entre series.
     */
    @Transactional(readOnly = true)
    public List<ClaveDeSerie> seriesConTrabajo(Instant ahora) {
        return registros.seriesConTrabajoScoped(ahora);
    }

    /**
     * El resultado de la pasada del job sobre una serie, en su backoff: SIN_RESPUESTA (un error transitorio) suma una falla
     * seguida y pospone la serie según {@link PoliticaDeReintento#espera(int)} desde {@code ahora}; TERMINADA lo vuelve a
     * cero. Las demás no lo cambian: OCUPADA y SERIE_PERDIDA son de otra instancia, TRABADA espera a soporte y SIN_TIEMPO
     * cortó sin error.
     */
    @Transactional
    public void registrarPasada(ClaveDeSerie clave, Atencion atencion, Instant ahora) {
        switch (atencion) {
            case SIN_RESPUESTA -> {
                long serieId = series.asegurarScoped(clave.ambiente(), clave.puntoVenta(), clave.cbteTipo());
                int fallas = series.fallasSeguidasBloqueandoScoped(serieId).orElseThrow() + 1;
                series.posponerScoped(serieId, fallas, ahora.plus(reintento.espera(fallas)));
            }
            case TERMINADA -> series.reiniciarBackoffScoped(
                    series.asegurarScoped(clave.ambiente(), clave.puntoVenta(), clave.cbteTipo()));
            default -> {
                // OCUPADA, SERIE_PERDIDA, TRABADA y SIN_TIEMPO no cambian el backoff.
            }
        }
    }

    // ---- La venta salió con el ticket provisorio (rama 7, S6) ----

    /**
     * Cuando la venta deja de esperar su CAE: si su registro vigente sigue en trámite (PENDIENTE, EN_CURSO o
     * EN_VERIFICACION), la venta sale con el ticket provisorio y queda marcada. Devuelve el registro vigente leído en la
     * misma transacción, después de marcarlo: lo que responde la venta y la marca no se contradicen.
     */
    @Transactional
    public Optional<ComprobanteFiscal> salioConTicketProvisorio(long comprobanteId) {
        registros.marcarProvisorioScoped(comprobanteId);
        return registros.deComprobanteScoped(comprobanteId);
    }

    // ---- Las acciones del Dueño (rama 7, CU-16 punto 7; D10 y S12) ----

    /**
     * Solo lectura, para quien va a reintentar (C2): que la venta tenga un registro vigente RECHAZADO o FUERA_DE_FECHA, leído
     * sin cargarlo en el contexto de persistencia de quien llama. Así «sin una rechazada» se responde antes que lo que
     * dependa del cliente de hoy; {@link #reintentar} lo vuelve a exigir al reemplazarlo.
     *
     * @throws AccionFiscalImpedidaException SIN_FACTURA_RECHAZADA si no lo tiene
     */
    @Transactional(readOnly = true)
    public void exigirRechazada(long comprobanteId) {
        registros.vigenteDeScoped(comprobanteId)
                .filter(r -> r.estado() == EstadoFiscal.RECHAZADO || r.estado() == EstadoFiscal.FUERA_DE_FECHA)
                .orElseThrow(AccionFiscalImpedidaException::sinFacturaRechazada);
    }

    /**
     * Solo lectura, para el detalle de Ventas (C1, «fecha nueva»): si la fecha del registro entra hoy (el {@code Clock}) en
     * la ventana de ARCA, con el último autorizado que conoce su serie ({@link VentanaCbteFch}). Es la regla de
     * {@link #reintentar} con la serie del registro; si al reintentar cambia la letra, el reintento decide con la nueva.
     */
    @Transactional(readOnly = true)
    public boolean fechaEntraHoy(ComprobanteFiscal registro) {
        LocalDate ultima = series.ultimaCbteFchScoped(registro.getAmbiente(), registro.getPuntoVenta(),
                registro.getCbteTipo()).orElse(null);
        return VentanaCbteFch.admite(LocalDate.ofInstant(registro.getRegistradoEn(), ZONA_ARCA),
                LocalDate.ofInstant(reloj.instant(), ZONA_ARCA), ultima);
    }

    /**
     * «Dejar sin factura»: el registro vigente RECHAZADO o FUERA_DE_FECHA de la venta pasa a SIN_FACTURA con el motivo
     * (sin espacios de más; obligatorio, hasta {@value #LARGO_MOTIVO} caracteres), el usuario y la hora del {@code Clock}.
     * Es definitivo (S12).
     *
     * @throws DatosInvalidosException con el motivo vacío o largo (400 en {@code motivo})
     * @throws AccionFiscalImpedidaException SIN_FACTURA_RECHAZADA si la venta (de la ferretería del request) no tiene un
     *     registro vigente RECHAZADO o FUERA_DE_FECHA
     */
    @Transactional
    public void dejarSinFactura(long comprobanteId, String motivo, long usuarioId) {
        String limpio = motivo == null ? "" : motivo.strip();
        if (limpio.isEmpty()) {
            throw new DatosInvalidosException(Map.of("motivo", MOTIVO_VACIO));
        }
        if (limpio.codePointCount(0, limpio.length()) > LARGO_MOTIVO) {
            throw new DatosInvalidosException(Map.of("motivo", MOTIVO_LARGO));
        }
        if (registros.dejarSinFacturaScoped(comprobanteId, limpio, usuarioId, Instant.now(reloj)) != 1) {
            throw AccionFiscalImpedidaException.sinFacturaRechazada();
        }
    }

    /**
     * «Reintentar» (D10): el registro vigente RECHAZADO o FUERA_DE_FECHA de la venta queda reemplazado (como historia, con
     * sus errores) y nace otro PENDIENTE, vigente, con la copia de {@code ahora}: el receptor que pasa quien llama (el
     * cliente de hoy), el emisor de CU-01 leído ahora, y la letra y el desglose recalculados como en el alta. La letra puede
     * cambiar; el ambiente y el punto de venta son los de la configuración de hoy.
     *
     * <p>La fecha nunca cambia sola: el registro nuevo lleva la hora del reemplazado (la de la venta, o la de hoy que el
     * Dueño ya confirmó en un reintento anterior) si su CbteFch entra en la ventana de ARCA ({@link VentanaCbteFch}, con el
     * último autorizado que conoce la serie de la letra nueva); si no entra, solo con {@code fechaDeHoy} lleva
     * {@code ahora}. Con {@code fechaDeHoy}, lleva {@code ahora} siempre.
     *
     * <p>Exige la transacción de quien llama, que ya tomó al cliente con lock exclusivo: acá se toman, en orden,
     * {@code config_arca} con {@code FOR SHARE} (la regla del alta, {@link #puedeFacturar(Instant)}, en {@code ahora}) y el
     * registro reemplazado. La serie solo se lee. No limpia el contexto de persistencia de quien llama ni carga el registro
     * reemplazado en él. Pedir el CAE va después del commit, como en la venta.
     *
     * @return el id del registro nuevo
     * @throws AccionFiscalImpedidaException SIN_FACTURA_RECHAZADA si la venta no tiene un vigente RECHAZADO o
     *     FUERA_DE_FECHA; FACTURACION_FRENADA si la ferretería no factura en {@code ahora}; FECHA_NUEVA_REQUERIDA si la fecha
     *     ya no entra y no se pidió la de hoy. En los tres casos no cambia nada.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long reintentar(AltaFiscal alta, boolean fechaDeHoy, Instant ahora) {
        RegistroVigente reemplazado = registros.vigenteDeScoped(alta.comprobanteId())
                .filter(r -> r.estado() == EstadoFiscal.RECHAZADO || r.estado() == EstadoFiscal.FUERA_DE_FECHA)
                .orElseThrow(AccionFiscalImpedidaException::sinFacturaRechazada);
        if (reemplazado.clase() == ClaseComprobante.NOTA_CREDITO) {
            return reintentarNota(reemplazado, alta.comprobanteId(), fechaDeHoy, ahora);
        }
        Emision emision = emision(ahora);
        if (emision.impedimento() != null) {
            throw AccionFiscalImpedidaException.facturacionFrenada(String.format(FACTURACION_FRENADA, emision.frenada()));
        }
        Instant registradoEn = ahora;
        if (!fechaDeHoy) {
            ConfigArca config = emision.config();
            int cbteTipo = EncuadreFiscal.de(emision.emisor().condicionIva(), alta.receptor().condicionIva())
                    .cbteTipo(ClaseComprobante.FACTURA);
            LocalDate ultima = series.ultimaCbteFchScoped(config.getAmbiente(), config.getPuntoVenta(), cbteTipo)
                    .orElse(null);
            if (!VentanaCbteFch.admite(LocalDate.ofInstant(reemplazado.registradoEn(), ZONA_ARCA),
                    LocalDate.ofInstant(ahora, ZONA_ARCA), ultima)) {
                throw AccionFiscalImpedidaException.fechaNuevaRequerida();
            }
            registradoEn = reemplazado.registradoEn();
        }
        if (registros.reemplazarScoped(reemplazado.id(), ahora) != 1) {
            throw AccionFiscalImpedidaException.sinFacturaRechazada();
        }
        return alta(emision, alta, registradoEn);
    }

    /**
     * «Reintentar» de una nota de crédito (rama 8, C1): la NC no copia al cliente ni a los renglones de hoy, ni al emisor de
     * CU-01, sino otra vez a su factura asociada (que no cambia). El cerrojo es el de la anulación ({@link #exigirQueFacture}):
     * la factura del ambiente de hoy y la ferretería facturando. La fecha, como en la factura: la del registro reemplazado si
     * entra en la ventana de ARCA en la serie de la NC; si no, solo con {@code fechaDeHoy}.
     */
    private long reintentarNota(RegistroVigente reemplazado, long notaId, boolean fechaDeHoy, Instant ahora) {
        ComprobanteFiscal factura = registros.findScoped(reemplazado.asociadoId()).orElseThrow();
        exigirQueFacture(factura.getAmbiente(), ahora);
        Instant registradoEn = ahora;
        if (!fechaDeHoy) {
            LocalDate ultima = series.ultimaCbteFchScoped(factura.getAmbiente(), factura.getPuntoVenta(),
                    factura.getLetra().cbteTipo(ClaseComprobante.NOTA_CREDITO)).orElse(null);
            if (!VentanaCbteFch.admite(LocalDate.ofInstant(reemplazado.registradoEn(), ZONA_ARCA),
                    LocalDate.ofInstant(ahora, ZONA_ARCA), ultima)) {
                throw AccionFiscalImpedidaException.fechaNuevaRequerida();
            }
            registradoEn = reemplazado.registradoEn();
        }
        if (registros.reemplazarScoped(reemplazado.id(), ahora) != 1) {
            throw AccionFiscalImpedidaException.sinFacturaRechazada();
        }
        return altaDeNota(notaId, factura, registradoEn);
    }

    // ---- La anulación de una venta (rama 8, CU-08; C1 de cu-08-nota-de-credito) ----

    /**
     * Decide cómo se anula la venta del lado fiscal, con el registro vigente de su factura tomado con lock exclusivo hasta el
     * fin de la transacción de quien llama (el paso «factura original» del orden de locks: después de la caja y el cliente,
     * antes del numerador). Así, mientras dura la anulación, ni el job ni la venta siguiente de la serie lo mandan a ARCA: un
     * PENDIENTE tomado acá lo encuentran después SIN_FACTURA, y uno que ya mandaron se ve acá EN_CURSO.
     *
     * <p>El registro se busca en la foto de la transacción y se toma por su clave primaria (sin lock de hueco): sin registro
     * en la foto no lo tiene (el registro de una venta nace con ella), y si el tomado ya quedó reemplazado por un
     * «Reintentar» que la foto no ve, la anulación se repite en una transacción nueva ({@link CannotAcquireLockException},
     * que la fachada de la anulación reintenta como un deadlock). Con la factura AUTORIZADO, además toma
     * {@code config_arca} con {@code FOR SHARE} ({@link #exigirQueFacture}): el panel no cambia el ambiente hasta el commit.
     *
     * @return NOTA_DE_CREDITO con la factura AUTORIZADO; ANULACION_INTERNA sin registro, o con uno PENDIENTE (sin número ni
     *     envío abierto), RECHAZADO, FUERA_DE_FECHA o SIN_FACTURA
     * @throws AccionFiscalImpedidaException ESPERAR_CAE con un envío en curso, en verificación o en revisión;
     *     OTRO_AMBIENTE o FACTURACION_FRENADA si la factura autorizada no admite NC hoy. No cambia nada
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ViaFiscalDeAnulacion tomarParaAnular(long ventaId, Instant ahora) {
        Optional<RegistroVigente> vigente = registros.vigenteDeScoped(ventaId);
        if (vigente.isEmpty()) {
            return ViaFiscalDeAnulacion.ANULACION_INTERNA;
        }
        ComprobanteFiscalRepository.RegistroTomado tomado = registros.bloquearParaAnularScoped(vigente.get().id())
                .orElseThrow();
        if (tomado.reemplazado()) {
            throw new CannotAcquireLockException("El registro fiscal de la venta " + ventaId + " cambió mientras se tomaba "
                    + "para anularla: se repite en una transacción nueva.");
        }
        return switch (tomado.estado()) {
            case AUTORIZADO -> {
                exigirQueFacture(tomado.ambiente(), ahora);
                yield ViaFiscalDeAnulacion.NOTA_DE_CREDITO;
            }
            case PENDIENTE, RECHAZADO, FUERA_DE_FECHA, SIN_FACTURA -> ViaFiscalDeAnulacion.ANULACION_INTERNA;
            case EN_CURSO, EN_VERIFICACION, CAE_NO_COINCIDE, SERIE_DESINCRONIZADA ->
                    throw AccionFiscalImpedidaException.esperarCae();
        };
    }

    /**
     * Da de alta el registro PENDIENTE de la nota de crédito {@code notaId} de la venta, copiado del registro de su factura
     * ({@link ComprobanteFiscal#notaDeCredito}) con su desglose, y con {@code ahora} como fecha. Va en la transacción de
     * quien llama, después de {@link #tomarParaAnular} con NOTA_DE_CREDITO: la factura ya está tomada y se lee de nuevo con
     * su lock, de lo último confirmado. Devuelve el id del registro.
     *
     * @throws IllegalStateException si la venta (de la ferretería del request) no tiene su factura AUTORIZADO
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public long registrarNotaDeCredito(long notaId, long ventaId, Instant ahora) {
        ComprobanteFiscal factura = registros.vigenteDeScoped(ventaId)
                .flatMap(v -> registros.bloquearRegistroScoped(v.id()))
                .filter(r -> !r.esNotaDeCredito() && r.getEstado() == EstadoFiscal.AUTORIZADO)
                .orElseThrow(() -> new IllegalStateException("La venta " + ventaId + " no tiene una factura autorizada: "
                        + "la nota de crédito va después de tomarParaAnular con NOTA_DE_CREDITO."));
        return altaDeNota(notaId, factura, ahora);
    }

    /**
     * La anulación interna (CU-08 punto 3, D12): el registro vigente PENDIENTE, RECHAZADO o FUERA_DE_FECHA de la venta pasa a
     * SIN_FACTURA con el motivo (sin espacios de más; obligatorio, hasta {@value #LARGO_MOTIVO} caracteres), el usuario y
     * {@code ahora}, con un UPDATE condicional como los del job ({@link ComprobanteFiscalRepository#cerrarSinCae}). Sin
     * registro, o ya SIN_FACTURA, no hace nada. Va en la transacción de quien llama, después de {@link #tomarParaAnular} con
     * ANULACION_INTERNA, y no limpia su contexto de persistencia.
     *
     * @throws DatosInvalidosException con el motivo vacío o largo (400 en {@code motivo})
     * @throws IllegalStateException si el registro está en otro estado (no se lo tomó con {@link #tomarParaAnular})
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cerrarSinCae(long ventaId, String motivo, long usuarioId, Instant ahora) {
        String limpio = motivo == null ? "" : motivo.strip();
        if (limpio.isEmpty()) {
            throw new DatosInvalidosException(Map.of("motivo", MOTIVO_DE_ANULACION_VACIO));
        }
        if (limpio.codePointCount(0, limpio.length()) > LARGO_MOTIVO) {
            throw new DatosInvalidosException(Map.of("motivo", MOTIVO_LARGO));
        }
        Optional<RegistroVigente> vigente = registros.vigenteDeScoped(ventaId);
        if (vigente.isEmpty() || registros.cerrarSinCaeScoped(vigente.get().id(), limpio, usuarioId, ahora) == 1) {
            return;
        }
        EstadoFiscal estado = registros.bloquearParaAnularScoped(vigente.get().id()).orElseThrow().estado();
        if (estado != EstadoFiscal.SIN_FACTURA) {
            throw new IllegalStateException("El registro fiscal de la venta " + ventaId + " está " + estado + ": la "
                    + "anulación interna va después de tomarParaAnular con ANULACION_INTERNA.");
        }
    }

    /**
     * El cerrojo de la nota de crédito (C1): la factura tiene que ser del ambiente de hoy de {@code config_arca} (leído con
     * {@code FOR SHARE}, así no cambia hasta el commit) y la ferretería tiene que facturar en {@code ahora}, con la regla
     * del alta. Primero el ambiente, que no se arregla: sin ambiente en la configuración cuenta la facturación frenada.
     *
     * @throws AccionFiscalImpedidaException OTRO_AMBIENTE o FACTURACION_FRENADA
     */
    private void exigirQueFacture(Ambiente ambienteDeLaFactura, Instant ahora) {
        Optional<ConfigArca> leida = configs.bloquearCompartidaScoped();
        if (leida.map(ConfigArca::getAmbiente).filter(a -> a != ambienteDeLaFactura).isPresent()) {
            throw AccionFiscalImpedidaException.otroAmbiente();
        }
        Emision emision = emision(leida, true, ahora);
        if (emision.impedimento() != null) {
            throw AccionFiscalImpedidaException.facturacionFrenada(String.format(FACTURACION_FRENADA, emision.frenada()));
        }
    }

    /** El alta de la nota de crédito copiada de la factura, con el desglose de la factura. Devuelve el id del registro. */
    private long altaDeNota(long notaId, ComprobanteFiscal factura, Instant registradoEn) {
        ComprobanteFiscal nota = registros.save(ComprobanteFiscal.notaDeCredito(notaId, factura, registradoEn));
        for (ComprobanteFiscalAlicuota alicuota : alicuotas.deRegistroScoped(factura.getId())) {
            alicuotas.save(ComprobanteFiscalAlicuota.copiaDe(nota.getId(), alicuota));
        }
        return nota.getId();
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
