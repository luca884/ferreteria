package com.ferreteria.facturacion.arca;

import com.ferreteria.facturacion.credencial.FirmaCms;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.wsfe.PedidoNoLlegoException;
import com.ferreteria.facturacion.wsfe.ResultadoInciertoException;
import com.ferreteria.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * El TA de WSAA de cada ferretería (rama 6, S2 y C4): el {@link ProveedorDeTicket} real de {@link ClienteWsfe}. Lo pide a
 * WSAA con {@code loginCms} (el TRA de {@link Tra}, firmado por {@link FirmaCms} con la credencial activa del ambiente), lo
 * guarda cifrado por (ferretería, servicio, ambiente) y lo reusa mientras sirva. No guarda nada en memoria: dos instancias
 * (las dos JVM de un deploy) comparten solo la base, y una instancia nueva reusa el TA que dejó la anterior.
 *
 * <p>La regla:
 * <ul>
 *   <li><b>TA vigente:</b> si hay un TA de la credencial activa (misma huella) que no venció, se usa. Se renueva cuando le
 *       faltan menos de {@code margen-renovacion} y ya pasó el {@code lapso-ta} del ambiente desde que se sacó; si la
 *       renovación falla y el viejo sigue vigente, se usa el viejo. Un TA de otra credencial no se usa.</li>
 *   <li><b>Sin TA vigente</b> (o con la renovación que toca), se toma el lease de la fila con un UPDATE condicional (dueño
 *       UUID, {@code lease-ticket}), se firma el TRA, se llama a {@code loginCms}, se guarda cifrado y se suelta el lease. Si
 *       el lease lo tiene otro, se espera con pausas cortas y se relee, hasta {@code espera-ticket}.</li>
 *   <li><b>El cortacircuito</b> ({@link FallaWsaa}): con el bloqueo de {@code config_arca}, o antes de {@code espera_hasta},
 *       no se llama a WSAA. La excepción es {@link #probar}, de «Probar conexión» (C1): pasa por encima del bloqueo, porque
 *       una prueba OK es lo que lo levanta; la espera la respeta igual.</li>
 * </ul>
 *
 * <p>Toda falla de conseguir el ticket sale como {@link PedidoNoLlegoException}, porque el pedido a WSFE todavía no se
 * mandó: el transporte de WSAA (no llegó o incierto), un fault, la espera, el bloqueo, la falta de credencial y el fin de la
 * espera del lease. Un error de transporte en WSAA no es un fault: no cambia la espera.
 *
 * <p>Sin transacciones propias: cada paso con la base es una transacción corta de {@link PasosDelTicket}, y la llamada a WSAA
 * va sin ninguna abierta. Los plazos salen del {@code Clock}; las pausas, de {@link Pausa}. Los logs (S10) nunca llevan el
 * token, el sign ni el CMS.
 */
public class TicketsDeAcceso implements ProveedorDeTicket {

    /** La pausa entre dos lecturas mientras otro saca el TA. */
    static final Duration PAUSA = Duration.ofMillis(50);

    static final String SIN_CREDENCIAL = "No hay un certificado de ARCA activo de %s: no se pidió el ticket de acceso.";
    static final String FRENADA = "La facturación con ARCA está frenada: ARCA rechazó el certificado (%s).";
    static final String EN_ESPERA = "ARCA pidió esperar (%s): no se pide otro ticket de acceso hasta %s.";
    static final String RECHAZADO = "WSAA rechazó el pedido del ticket de acceso (%s).";
    static final String SIN_RESPUESTA = "No se pudo pedir el ticket de acceso a ARCA: %s";
    static final String OTRO_NO_TERMINO =
            "Otro pedido está sacando el ticket de acceso de ARCA y no terminó a tiempo: no se mandó nada.";

    private static final Logger log = LoggerFactory.getLogger(TicketsDeAcceso.class);

    /** Cómo se espera entre dos lecturas: en producción, {@link #PAUSA}; en los tests, lo que adelante el reloj. */
    @FunctionalInterface
    public interface Pausa {

        /** Una pausa corta. */
        void pausar() throws InterruptedException;

        /** {@link #PAUSA} con {@code Thread.sleep}. */
        Pausa CORTA = () -> Thread.sleep(PAUSA.toMillis());
    }

    private final PasosDelTicket pasos;
    private final FirmaCms firma;
    private final Map<Ambiente, ClienteWsaa> clientes;
    private final PropiedadesArca propiedades;
    private final Clock reloj;
    private final Pausa pausa;

    /** Con la pausa de producción. */
    public TicketsDeAcceso(PasosDelTicket pasos, FirmaCms firma, List<ClienteWsaa> clientes, PropiedadesArca propiedades,
            Clock reloj) {
        this(pasos, firma, clientes, propiedades, reloj, Pausa.CORTA);
    }

    /**
     * @param pasos los pasos con la base
     * @param firma la firma del TRA con la credencial activa
     * @param clientes un cliente de WSAA por ambiente (cada uno con su transporte)
     * @param propiedades la configuración de C6: lapsos, margen, esperas y lease
     * @param reloj de donde salen todas las horas
     * @param pausa cómo se espera mientras otro saca el TA
     */
    public TicketsDeAcceso(PasosDelTicket pasos, FirmaCms firma, List<ClienteWsaa> clientes, PropiedadesArca propiedades,
            Clock reloj, Pausa pausa) {
        this.pasos = Objects.requireNonNull(pasos, "los pasos del ticket");
        this.firma = Objects.requireNonNull(firma, "la firma");
        this.clientes = new EnumMap<>(Ambiente.class);
        for (ClienteWsaa cliente : clientes) {
            if (this.clientes.put(cliente.ambiente(), cliente) != null) {
                throw new IllegalArgumentException("Dos clientes de WSAA para " + cliente.ambiente() + ".");
            }
        }
        this.propiedades = Objects.requireNonNull(propiedades, "la configuración de ARCA");
        this.reloj = Objects.requireNonNull(reloj, "el reloj");
        this.pausa = Objects.requireNonNull(pausa, "la pausa");
    }

    /**
     * {@inheritDoc}
     *
     * @throws IllegalStateException si se llama con una transacción abierta: la llamada a WSAA nunca va dentro de una (el
     *     lease y el TA se confirman en transacciones cortas propias, que otra JVM tiene que ver enseguida)
     */
    @Override
    public TicketDeAcceso ticket(Ambiente ambiente, String servicio) {
        return obtener(ambiente, servicio, true).ticket();
    }

    /**
     * El TA para «Probar conexión» (C1, CU-01 paso 4), con su vencimiento: igual que {@link #ticket}, pero con el bloqueo de
     * C4 llama a WSAA igual (si el certificado ya sirve, la prueba OK levanta el bloqueo; si ARCA lo vuelve a rechazar, el
     * bloqueo queda con el código nuevo). Respeta la espera de C4 y reusa el TA vigente.
     *
     * @throws PedidoNoLlegoException si no se pudo conseguir; con un fault de WSAA, su causa es la
     *     {@link FaultWsaaException}
     * @throws IllegalStateException si se llama con una transacción abierta
     */
    public Probado probar(Ambiente ambiente, String servicio) {
        return obtener(ambiente, servicio, false);
    }

    private Probado obtener(Ambiente ambiente, String servicio, boolean respetarBloqueo) {
        Objects.requireNonNull(ambiente, "el ambiente");
        Objects.requireNonNull(servicio, "el servicio");
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("El ticket de acceso de ARCA se pide sin una transacción abierta: la llamada a "
                    + "WSAA no puede ir dentro de una.");
        }
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        try {
            return conseguir(ambiente, servicio, ferreteriaId, respetarBloqueo);
        } catch (DataAccessException | TransactionException e) {
            throw new PedidoNoLlegoException("No se pudo leer el ticket de acceso de ARCA: no se mandó nada.", e);
        }
    }

    @Override
    public void descartar(Ambiente ambiente, String servicio, TicketDeAcceso rechazado) {
        Objects.requireNonNull(rechazado, "el ticket rechazado");
        try {
            if (pasos.descartar(ambiente, servicio, rechazado)) {
                log.debug("Ticket de acceso de {} en {} descartado para la ferretería {}", servicio, ambiente,
                        TenantContext.requireFerreteriaId());
            }
        } catch (DataAccessException | TransactionException e) {
            // Si no se pudo borrar, el próximo ticket() devuelve el mismo y WSFE vuelve a contestar 600: sale como error de
            // cabecera, igual que un segundo 600.
            log.warn("No se pudo descartar el ticket de acceso de {} en {}: {}", servicio, ambiente, e.getMessage());
        }
    }

    private Probado conseguir(Ambiente ambiente, String servicio, Long ferreteriaId, boolean respetarBloqueo) {
        Instant limite = null;
        while (true) {
            Instant ahora = Instant.now(reloj);
            if (limite == null) {
                limite = ahora.plus(propiedades.esperaTicket());
            }
            PasosDelTicket.Estado leido = pasos.leer(ambiente, servicio);
            Optional<Probado> resuelto = resolverSinWsaa(leido, ambiente, ahora, respetarBloqueo);
            if (resuelto.isPresent()) {
                return resuelto.get();
            }
            Optional<Probado> vigente = vigente(leido, ahora);
            if (leido.leaseDeOtro(ahora)) {
                if (vigente.isPresent()) {
                    return vigente.get(); // otro lo está renovando: mientras tanto sirve el viejo
                }
                if (!ahora.isBefore(limite)) {
                    throw new PedidoNoLlegoException(OTRO_NO_TERMINO);
                }
                pausar();
                continue;
            }
            String dueno = UUID.randomUUID().toString();
            Optional<PasosDelTicket.Estado> tomado =
                    pasos.tomar(ambiente, servicio, dueno, ahora, ahora.plus(propiedades.leaseTicket()));
            if (tomado.isEmpty()) {
                continue; // otro lo tomó entre la lectura y la toma: la próxima lectura lo ve
            }
            return pedir(ambiente, servicio, ferreteriaId, dueno, tomado.get(), ahora, respetarBloqueo);
        }
    }

    /**
     * Lo que se resuelve sin llamar a WSAA: el TA vigente que no toca renovar, o, si hace falta WSAA pero no se puede (sin
     * credencial, con el bloqueo o en espera), el TA vigente o la falla. Vacío si hay que llamar a WSAA.
     */
    private Optional<Probado> resolverSinWsaa(PasosDelTicket.Estado leido, Ambiente ambiente, Instant ahora,
            boolean respetarBloqueo) {
        if (leido.firmante() == null) {
            throw new PedidoNoLlegoException(String.format(SIN_CREDENCIAL, ambiente));
        }
        Optional<Probado> vigente = vigente(leido, ahora);
        if (vigente.isPresent() && !tocaRenovar(leido.guardado(), ambiente, ahora)) {
            return vigente;
        }
        String impedimento = impedimento(leido, ahora, respetarBloqueo);
        if (impedimento != null) {
            return Optional.of(vigente.orElseThrow(() -> new PedidoNoLlegoException(impedimento)));
        }
        return Optional.empty();
    }

    /** Con el lease tomado: si otro dejó un TA mientras tanto, ese; si no, firma, {@code loginCms} y guarda. */
    private Probado pedir(Ambiente ambiente, String servicio, Long ferreteriaId, String dueno,
            PasosDelTicket.Estado tomado, Instant ahora, boolean respetarBloqueo) {
        boolean suelto = false;
        try {
            Optional<Probado> resuelto = resolverSinWsaa(tomado, ambiente, ahora, respetarBloqueo);
            if (resuelto.isPresent()) {
                return resuelto.get();
            }
            Optional<Probado> vigente = vigente(tomado, ahora);
            ClienteWsaa cliente = clientes.get(ambiente);
            if (cliente == null) {
                throw new PedidoNoLlegoException("No hay un cliente de WSAA para " + ambiente + ": no se mandó nada.");
            }
            FirmaCms.Firmado firmado;
            RespuestaLoginCms ta;
            try {
                firmado = firma.firmar(ambiente, Tra.armar(Instant.now(reloj), servicio).getBytes(StandardCharsets.UTF_8));
                ta = cliente.loginCms(firmado.cmsBase64());
            } catch (FaultWsaaException e) {
                registrarFault(ambiente, servicio, ferreteriaId, dueno, e); // también suelta el lease
                suelto = true;
                PedidoNoLlegoException rechazo = new PedidoNoLlegoException(String.format(RECHAZADO, e.codigo()), e);
                if (!respetarBloqueo && FallaWsaa.de(e.codigo()) == FallaWsaa.CREDENCIAL) {
                    // «Probar conexión»: el TA viejo no prueba nada si ARCA acaba de rechazar el certificado
                    throw rechazo;
                }
                return vigente.orElseThrow(() -> rechazo);
            } catch (PedidoNoLlegoException | ResultadoInciertoException | FirmaCms.NoSePudoFirmarException e) {
                log.warn("No se pudo sacar el ticket de acceso de {} en {} para la ferretería {}: {}", servicio, ambiente,
                        ferreteriaId, e.getMessage());
                return vigente.orElseThrow(() -> new PedidoNoLlegoException(String.format(SIN_RESPUESTA, e.getMessage()),
                        e));
            }
            pasos.guardar(ambiente, servicio, dueno, firmado.huella(), ta);
            suelto = true;
            log.info("Ticket de acceso de {} en {} para la ferretería {} (huella {}): vence {}", servicio, ambiente,
                    ferreteriaId, firmado.huella(), ta.vence());
            return new Probado(new TicketDeAcceso(ta.token(), ta.sign(), firmado.cuit()), ta.vence());
        } finally {
            if (!suelto) {
                pasos.soltar(ambiente, servicio, dueno);
            }
        }
    }

    /** El cortacircuito de C4: qué deja cada fault, en la base y en el log. */
    private void registrarFault(Ambiente ambiente, String servicio, Long ferreteriaId, String dueno, FaultWsaaException e) {
        Instant ahora = Instant.now(reloj);
        String codigo = e.codigo();
        switch (FallaWsaa.de(codigo)) {
            case CREDENCIAL -> {
                pasos.bloquear(ambiente, servicio, dueno, codigo, e.faultstring(), ahora);
                log.warn("ARCA rechazó el certificado de la ferretería {} en {} ({}): la facturación queda frenada hasta "
                        + "otro certificado o «Probar conexión».", ferreteriaId, ambiente, codigo);
            }
            case SISTEMA -> {
                Instant hasta = ahora.plus(propiedades.esperaErrorSistema());
                pasos.esperar(ambiente, servicio, dueno, codigo, ahora, hasta);
                log.error(AlertaArca.MARCADOR, "{}: WSAA contestó {} a loginCms de la ferretería {} en {}: no se llama a "
                        + "WSAA hasta {}.", AlertaArca.TEXTO, codigo, ferreteriaId, ambiente, hasta);
            }
            case TRANSITORIO -> esperar(ambiente, servicio, ferreteriaId, dueno, codigo, ahora,
                    propiedades.esperaWsaa());
            case TA_VIGENTE -> esperar(ambiente, servicio, ferreteriaId, dueno, codigo, ahora,
                    propiedades.esperaTaVigente());
        }
    }

    private void esperar(Ambiente ambiente, String servicio, Long ferreteriaId, String dueno, String codigo,
            Instant ahora, Duration espera) {
        Instant hasta = ahora.plus(espera);
        pasos.esperar(ambiente, servicio, dueno, codigo, ahora, hasta);
        log.warn("WSAA contestó {} a la ferretería {} en {}: no se llama a WSAA hasta {}.", codigo, ferreteriaId, ambiente,
                hasta);
    }

    /** El TA guardado de la credencial activa, si no venció en {@code ahora}. */
    private static Optional<Probado> vigente(PasosDelTicket.Estado leido, Instant ahora) {
        PasosDelTicket.Guardado guardado = leido.guardado();
        if (guardado == null || leido.firmante() == null || !guardado.vence().isAfter(ahora)) {
            return Optional.empty();
        }
        return Optional.of(new Probado(new TicketDeAcceso(guardado.token(), guardado.sign(), leido.firmante().cuit()),
                guardado.vence()));
    }

    /** Si toca renovarlo: le falta menos que el margen para vencer, y ya pasó el lapso del ambiente desde que se sacó. */
    private boolean tocaRenovar(PasosDelTicket.Guardado guardado, Ambiente ambiente, Instant ahora) {
        boolean cercaDeVencer = guardado.vence().minus(propiedades.margenRenovacion()).isBefore(ahora);
        boolean pasoElLapso = !ahora.isBefore(guardado.generado().plus(propiedades.lapsoTa().de(ambiente)));
        return cercaDeVencer && pasoElLapso;
    }

    /**
     * Por qué no se puede llamar a WSAA en {@code ahora} (el bloqueo o la espera de C4), o null. Sin
     * {@code respetarBloqueo} («Probar conexión»), el bloqueo no impide.
     */
    private static String impedimento(PasosDelTicket.Estado leido, Instant ahora, boolean respetarBloqueo) {
        if (respetarBloqueo && leido.bloqueo() != null) {
            return String.format(FRENADA, leido.bloqueo());
        }
        if (leido.enEspera(ahora)) {
            return String.format(EN_ESPERA, leido.ultimaFalla(), leido.esperaHasta());
        }
        return null;
    }

    private void pausar() {
        try {
            pausa.pausar();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PedidoNoLlegoException("Se interrumpió la espera del ticket de acceso de ARCA: no se mandó nada.", e);
        }
    }

    /**
     * Un TA con su vencimiento ({@code expirationTime}), para «Probar conexión». {@link #toString()} no muestra el token ni
     * el sign (los enmascara {@link TicketDeAcceso}).
     *
     * @param ticket el token, el sign y la CUIT de la credencial
     * @param vence hasta cuándo vale
     */
    public record Probado(TicketDeAcceso ticket, Instant vence) {

        public Probado {
            Objects.requireNonNull(ticket, "el ticket");
            Objects.requireNonNull(vence, "el vencimiento");
        }
    }
}
