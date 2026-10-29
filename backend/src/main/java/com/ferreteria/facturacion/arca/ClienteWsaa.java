package com.ferreteria.facturacion.arca;

import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.wsfe.PedidoNoLlegoException;
import com.ferreteria.facturacion.wsfe.ResultadoInciertoException;
import java.net.URI;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

/**
 * El cliente de WSAA de un ambiente (C3): {@code loginCms} por su {@link Transporte} a la URL de WSAA de ese ambiente, que
 * elige la configuración (C6, {@code app.facturacion.arca.wsaa.*}; el simulador en el borde también tiene la suya).
 *
 * <ul>
 *   <li>El pedido es un sobre SOAP 1.1 document/literal con {@code SOAPAction: ""}:
 *       {@code <loginCms xmlns="http://wsaa.view.sua.dvadac.desein.afip.gov"><in0>CMS en Base64</in0></loginCms>}.</li>
 *   <li>La respuesta se lee en dos pasos: {@code loginCmsReturn} trae el {@code loginTicketResponse} escapado, que se vuelve
 *       a leer (con {@link XmlSeguro}) para sacar {@code credentials/token}, {@code credentials/sign},
 *       {@code header/generationTime} y {@code header/expirationTime}.</li>
 *   <li>Un SOAP Fault (con el status 500 de SOAP 1.1, o con cualquier otro) es {@link FaultWsaaException}, con el código de
 *       {@code faultcode} sin su prefijo ({@code ns1:} de Axis, o ninguno) y el {@code faultstring}.</li>
 *   <li>Otro status sin Fault, o un cuerpo que no se puede leer, es {@link ResultadoInciertoException}; una falla del
 *       transporte sale tal cual. Para el ticket, las dos dicen lo mismo: no hay TA y el pedido a WSFE no salió.</li>
 * </ul>
 *
 * <p>Los logs (S10) nunca llevan el sobre (el CMS) ni la respuesta (el TA): a lo sumo el ambiente, el status y los
 * milisegundos, en DEBUG.
 */
public final class ClienteWsaa {

    /** El namespace de {@code loginCms} y {@code loginCmsResponse} (el del WSDL). */
    public static final String NAMESPACE = "http://wsaa.view.sua.dvadac.desein.afip.gov";

    /** La {@code SOAPAction} de {@code loginCms}: vacía, como dice el WSDL. */
    static final String SOAP_ACTION = "";

    private static final Logger log = LoggerFactory.getLogger(ClienteWsaa.class);

    private final Ambiente ambiente;
    private final Transporte transporte;
    private final URI url;

    /**
     * @param ambiente el único ambiente que atiende
     * @param transporte por dónde va {@code loginCms}
     * @param urls las URL de WSAA de la configuración; el ambiente elige la suya
     */
    public ClienteWsaa(Ambiente ambiente, Transporte transporte, PropiedadesArca.Urls urls) {
        this.ambiente = Objects.requireNonNull(ambiente, "el ambiente");
        this.transporte = Objects.requireNonNull(transporte, "el transporte");
        this.url = Objects.requireNonNull(urls, "las URL de WSAA").de(ambiente);
    }

    /** El ambiente que atiende. */
    public Ambiente ambiente() {
        return ambiente;
    }

    /**
     * {@code loginCms}: el TA para el TRA firmado en {@code cmsBase64}.
     *
     * @throws FaultWsaaException si WSAA contestó un Fault
     * @throws PedidoNoLlegoException si seguro no llegó
     * @throws ResultadoInciertoException si no hubo una respuesta legible
     */
    public RespuestaLoginCms loginCms(String cmsBase64) {
        Objects.requireNonNull(cmsBase64, "el CMS");
        String sobre = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
                + "<soap:Envelope xmlns:soap=\"" + XmlSeguro.SOAP_ENV + "\"><soap:Body>"
                + "<loginCms xmlns=\"" + NAMESPACE + "\"><in0>" + XmlSeguro.escapar(cmsBase64) + "</in0></loginCms>"
                + "</soap:Body></soap:Envelope>";
        long desde = System.nanoTime();
        Transporte.Respuesta respuesta;
        try {
            respuesta = transporte.enviar(new Transporte.Pedido(url, SOAP_ACTION, sobre));
        } catch (PedidoNoLlegoException | ResultadoInciertoException e) {
            log.debug("WSAA loginCms en {}: {} en {} ms", ambiente, e.getClass().getSimpleName(), milisegundos(desde));
            throw e;
        }
        log.debug("WSAA loginCms en {}: status {} en {} ms", ambiente, respuesta.status(), milisegundos(desde));
        Element cuerpo;
        try {
            cuerpo = XmlSeguro.cuerpoSoap(XmlSeguro.leer(respuesta.cuerpo()));
        } catch (XmlIlegibleException e) {
            throw new ResultadoInciertoException("La respuesta de WSAA (status " + respuesta.status()
                    + ") no se pudo leer.", e);
        }
        if (XmlSeguro.es(cuerpo, XmlSeguro.SOAP_ENV, "Fault")) {
            throw fault(cuerpo);
        }
        if (respuesta.status() != 200) {
            throw new ResultadoInciertoException("WSAA respondió " + respuesta.status() + " a loginCms.");
        }
        try {
            return ticket(cuerpo);
        } catch (XmlIlegibleException e) {
            throw new ResultadoInciertoException("La respuesta de WSAA a loginCms no se pudo leer.", e);
        }
    }

    /** El TA: {@code loginCmsReturn}, leído otra vez como documento. */
    private static RespuestaLoginCms ticket(Element cuerpo) {
        if (!XmlSeguro.es(cuerpo, NAMESPACE, "loginCmsResponse")) {
            throw new XmlIlegibleException("La respuesta de WSAA no es loginCmsResponse: «" + cuerpo.getLocalName() + "».");
        }
        String escapado = XmlSeguro.texto(cuerpo, "loginCmsReturn")
                .orElseThrow(() -> new XmlIlegibleException("loginCmsResponse no trae loginCmsReturn."));
        Element ta = XmlSeguro.leer(escapado).getDocumentElement();
        if (!"loginTicketResponse".equals(ta.getLocalName())) {
            throw new XmlIlegibleException("loginCmsReturn no trae un loginTicketResponse: «" + ta.getLocalName() + "».");
        }
        Element header = XmlSeguro.hijo(ta, "header")
                .orElseThrow(() -> new XmlIlegibleException("El loginTicketResponse no trae header."));
        Element credenciales = XmlSeguro.hijo(ta, "credentials")
                .orElseThrow(() -> new XmlIlegibleException("El loginTicketResponse no trae credentials."));
        return new RespuestaLoginCms(requerido(credenciales, "token"), requerido(credenciales, "sign"),
                tiempo(header, "generationTime"), tiempo(header, "expirationTime"));
    }

    /** El Fault de SOAP 1.1: {@code faultcode} y {@code faultstring} van sin namespace. */
    private static FaultWsaaException fault(Element fault) {
        String codigo = sinNamespace(fault, "faultcode")
                .orElseThrow(() -> new ResultadoInciertoException("WSAA contestó un Fault sin faultcode."));
        String sinPrefijo = codigo.substring(codigo.lastIndexOf(':') + 1);
        return new FaultWsaaException(sinPrefijo, sinNamespace(fault, "faultstring").orElse(""));
    }

    private static Optional<String> sinNamespace(Element padre, String nombre) {
        return XmlSeguro.elementos(padre).stream()
                .filter(h -> nombre.equals(h.getLocalName()) && h.getNamespaceURI() == null)
                .map(h -> h.getTextContent().strip()).filter(t -> !t.isEmpty()).findFirst();
    }

    private static String requerido(Element padre, String nombre) {
        return XmlSeguro.texto(padre, nombre)
                .orElseThrow(() -> new XmlIlegibleException("El loginTicketResponse no trae " + nombre + "."));
    }

    private static Instant tiempo(Element header, String nombre) {
        String texto = requerido(header, nombre);
        try {
            return OffsetDateTime.parse(texto).toInstant();
        } catch (DateTimeException e) {
            throw new XmlIlegibleException("El " + nombre + " del loginTicketResponse no es una fecha con zona.", e);
        }
    }

    /** Lo que tardó la llamada (un intervalo: no lee la hora del sistema, que en facturación sale del Clock). */
    private static long milisegundos(long desde) {
        return (System.nanoTime() - desde) / 1_000_000;
    }
}
