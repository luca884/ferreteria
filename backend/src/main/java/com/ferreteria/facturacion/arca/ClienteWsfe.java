package com.ferreteria.facturacion.arca;

import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.wsfe.Auth;
import com.ferreteria.facturacion.wsfe.Err;
import com.ferreteria.facturacion.wsfe.FeCaeReq;
import com.ferreteria.facturacion.wsfe.FeCaeResponse;
import com.ferreteria.facturacion.wsfe.FeCompConsultarResponse;
import com.ferreteria.facturacion.wsfe.FeCompUltimoAutorizadoResponse;
import com.ferreteria.facturacion.wsfe.PedidoNoLlegoException;
import com.ferreteria.facturacion.wsfe.ResultadoInciertoException;
import com.ferreteria.facturacion.wsfe.ServicioWsfe;
import java.net.URI;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * El cliente de WSFEv1 de un ambiente (S4): el puerto {@link ServicioWsfe} del motor, más {@code FEDummy} y
 * {@code FEParamGetPtosVenta} para «Probar conexión» y el chequeo del super admin (L4: no están en el puerto, así los dobles
 * del motor no cambian). Arma el XML con {@link PlantillasWsfe}, lo manda por su {@link Transporte} a la URL de WSFE de su
 * ambiente (de la configuración, C6) y lee la respuesta con {@link LectorWsfe}.
 *
 * <ul>
 *   <li>Atiende un solo ambiente: un {@link Auth} de otro se rechaza con {@link IllegalArgumentException} antes de mandar
 *       nada, como el simulador.</li>
 *   <li>{@code Auth.Token} y {@code Auth.Sign} salen del {@link ProveedorDeTicket}; {@code Auth.Cuit} es la del registro,
 *       {@code auth.cuit()} (S17). En PRODUCCION, si no es la CUIT de la credencial del ticket, no se manda nada: es un
 *       error del sistema, con un log {@code ERROR} {@link AlertaArca}, y el pedido no llegó. En HOMOLOGACION se manda igual
 *       (un certificado de otra CUIT puede dar 601; la rama 11 confirma con la piloto).</li>
 *   <li>Un status distinto de 200, o un cuerpo que no se puede leer, es {@link ResultadoInciertoException}: pudo haber
 *       llegado.</li>
 *   <li>El 600 (C4: ARCA no acepta el TA) descarta el ticket, pide otro una sola vez y repite la llamada una vez. Si vuelve a
 *       dar 600, esa respuesta vuelve tal cual, como error de cabecera. Repetir es seguro: con un 600, ARCA no procesó el
 *       pedido.</li>
 * </ul>
 *
 * <p>Los logs (S10) nunca llevan el sobre ni la respuesta, que tienen el token y el sign: a lo sumo el método, el ambiente,
 * el status y los milisegundos, en DEBUG.
 */
public class ClienteWsfe implements ServicioWsfe {

    /** El servicio de WSAA para el que se pide el ticket. */
    public static final String SERVICIO = "wsfe";

    /** El código de WSFEv1 para un {@code Auth} que ARCA no acepta (token o sign vencidos o inválidos). */
    static final int TA_RECHAZADO = 600;

    private static final Logger log = LoggerFactory.getLogger(ClienteWsfe.class);

    private final Ambiente ambiente;
    private final Transporte transporte;
    private final URI url;
    private final ProveedorDeTicket tickets;

    /**
     * @param ambiente el único ambiente que atiende
     * @param transporte por dónde van los pedidos
     * @param url la URL de WSFEv1 del ambiente ({@code app.facturacion.arca.wsfe.*})
     * @param tickets de dónde sale el TA de {@value #SERVICIO}
     */
    public ClienteWsfe(Ambiente ambiente, Transporte transporte, URI url, ProveedorDeTicket tickets) {
        this.ambiente = Objects.requireNonNull(ambiente, "el ambiente");
        this.transporte = Objects.requireNonNull(transporte, "el transporte");
        this.url = Objects.requireNonNull(url, "la URL de WSFEv1");
        this.tickets = Objects.requireNonNull(tickets, "el proveedor de tickets");
    }

    /** El ambiente que atiende. */
    public Ambiente ambiente() {
        return ambiente;
    }

    /**
     * {@code FEDummy}: si andan los servidores de ARCA (AppServer, DbServer y AuthServer). Sin ticket.
     *
     * @throws PedidoNoLlegoException si seguro no llegó
     * @throws ResultadoInciertoException si no hubo una respuesta legible
     */
    public FeDummyResponse feDummy() {
        return llamar(MetodoWsfe.FE_DUMMY, PlantillasWsfe.feDummy(), LectorWsfe::feDummy);
    }

    /**
     * {@code FEParamGetPtosVenta}: los puntos de venta de la CUIT, con su tipo de emisión, si están bloqueados y su baja.
     *
     * @throws PedidoNoLlegoException si seguro no llegó
     * @throws ResultadoInciertoException si no hubo una respuesta legible
     */
    public FeParamGetPtosVentaResponse feParamGetPtosVenta(Auth auth) {
        return conTicket(auth, MetodoWsfe.FE_PARAM_GET_PTOS_VENTA,
                ticket -> PlantillasWsfe.feParamGetPtosVenta(ticket, auth.cuit()),
                cuerpo -> LectorWsfe.feParamGetPtosVenta(cuerpo).respuesta(), FeParamGetPtosVentaResponse::errors);
    }

    @Override
    public FeCompUltimoAutorizadoResponse feCompUltimoAutorizado(Auth auth, int ptoVta, int cbteTipo) {
        return conTicket(auth, MetodoWsfe.FE_COMP_ULTIMO_AUTORIZADO,
                ticket -> PlantillasWsfe.feCompUltimoAutorizado(ticket, auth.cuit(), ptoVta, cbteTipo),
                cuerpo -> LectorWsfe.feCompUltimoAutorizado(cuerpo).respuesta(), FeCompUltimoAutorizadoResponse::errors);
    }

    @Override
    public FeCaeResponse feCaeSolicitar(Auth auth, FeCaeReq feCaeReq) {
        return conTicket(auth, MetodoWsfe.FE_CAE_SOLICITAR,
                ticket -> PlantillasWsfe.feCaeSolicitar(ticket, auth.cuit(), feCaeReq),
                cuerpo -> LectorWsfe.feCaeSolicitar(cuerpo).respuesta(), FeCaeResponse::errors);
    }

    @Override
    public FeCompConsultarResponse feCompConsultar(Auth auth, int cbteTipo, int ptoVta, long cbteNro) {
        return conTicket(auth, MetodoWsfe.FE_COMP_CONSULTAR,
                ticket -> PlantillasWsfe.feCompConsultar(ticket, auth.cuit(), cbteTipo, ptoVta, cbteNro),
                cuerpo -> LectorWsfe.feCompConsultar(cuerpo).respuesta(), FeCompConsultarResponse::errors);
    }

    /** Una llamada con {@code Auth}: el ticket, la llamada y, con un 600, un ticket nuevo y una sola llamada más. */
    private <T> T conTicket(Auth auth, MetodoWsfe metodo, Function<TicketDeAcceso, String> sobre,
            Function<String, T> lector, Function<T, List<Err>> errores) {
        atiende(auth);
        TicketDeAcceso ticket = ticket(auth, metodo);
        T respuesta = llamar(metodo, sobre.apply(ticket), lector);
        if (errores.apply(respuesta).stream().noneMatch(e -> e.code() == TA_RECHAZADO)) {
            return respuesta;
        }
        log.debug("WSFE {} en {}: 600, se renueva el ticket y se repite una vez", metodo, ambiente);
        tickets.descartar(ambiente, SERVICIO, ticket);
        return llamar(metodo, sobre.apply(ticket(auth, metodo)), lector);
    }

    /** El ticket del proveedor; en PRODUCCION, solo si es de la CUIT del registro (S17). */
    private TicketDeAcceso ticket(Auth auth, MetodoWsfe metodo) {
        TicketDeAcceso ticket = tickets.ticket(ambiente, SERVICIO);
        if (ambiente == Ambiente.PRODUCCION && ticket.cuit() != auth.cuit()) {
            log.error(AlertaArca.MARCADOR, "{}: la CUIT del registro ({}) no es la de la credencial activa ({}) en {}: no "
                    + "se mandó {}.", AlertaArca.TEXTO, auth.cuit(), ticket.cuit(), ambiente, metodo);
            throw new PedidoNoLlegoException("La CUIT del comprobante no es la del certificado de ARCA: no se mandó "
                    + metodo + ".");
        }
        return ticket;
    }

    /** Manda el sobre y lee la respuesta: un status distinto de 200 o un cuerpo ilegible es incierto. */
    private <T> T llamar(MetodoWsfe metodo, String sobre, Function<String, T> lector) {
        long desde = System.nanoTime();
        Transporte.Respuesta respuesta;
        try {
            respuesta = transporte.enviar(new Transporte.Pedido(url, metodo.soapAction(), sobre));
        } catch (PedidoNoLlegoException | ResultadoInciertoException e) {
            log.debug("WSFE {} en {}: {} en {} ms", metodo, ambiente, e.getClass().getSimpleName(), milisegundos(desde));
            throw e;
        }
        log.debug("WSFE {} en {}: status {} en {} ms", metodo, ambiente, respuesta.status(), milisegundos(desde));
        if (respuesta.status() != 200) {
            throw new ResultadoInciertoException("ARCA respondió " + respuesta.status() + " a " + metodo + ".");
        }
        try {
            return lector.apply(respuesta.cuerpo());
        } catch (XmlIlegibleException e) {
            throw new ResultadoInciertoException("La respuesta de ARCA a " + metodo + " no se pudo leer.", e);
        }
    }

    private void atiende(Auth auth) {
        Objects.requireNonNull(auth, "el Auth");
        if (auth.ambiente() != ambiente) {
            throw new IllegalArgumentException("Este cliente de WSFEv1 atiende " + ambiente + ": un pedido de "
                    + auth.ambiente() + " no va acá.");
        }
    }

    /** Lo que tardó la llamada (un intervalo: no lee la hora del sistema, que en facturación sale del Clock). */
    private static long milisegundos(long desde) {
        return (System.nanoTime() - desde) / 1_000_000;
    }
}
