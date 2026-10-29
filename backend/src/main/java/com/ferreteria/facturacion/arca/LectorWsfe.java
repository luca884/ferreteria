package com.ferreteria.facturacion.arca;

import com.ferreteria.facturacion.wsfe.AlicIva;
import com.ferreteria.facturacion.wsfe.CbteAsoc;
import com.ferreteria.facturacion.wsfe.Err;
import com.ferreteria.facturacion.wsfe.FeCabResp;
import com.ferreteria.facturacion.wsfe.FeCaeDetRequest;
import com.ferreteria.facturacion.wsfe.FeCaeDetResponse;
import com.ferreteria.facturacion.wsfe.FeCaeResponse;
import com.ferreteria.facturacion.wsfe.FeCompConsultarResponse;
import com.ferreteria.facturacion.wsfe.FeCompUltimoAutorizadoResponse;
import com.ferreteria.facturacion.wsfe.Obs;
import com.ferreteria.facturacion.wsfe.PeriodoAsoc;
import com.ferreteria.facturacion.wsfe.Resultado;
import com.ferreteria.facturacion.wsfe.ResultGet;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import javax.xml.XMLConstants;
import org.w3c.dom.Element;

/**
 * Lee las respuestas de WSFEv1 hacia los records del puerto ({@code facturacion.wsfe}), con el parser de
 * {@link XmlSeguro}. Solo mira el elemento del {@code soap:Body}: el header {@code FEHeaderInfo} (ambiente, fecha y versión
 * del servidor) se ignora.
 *
 * <p>Es tolerante con lo que el WSDL permite omitir: una lista ausente ({@code Errors}, {@code Events},
 * {@code Observaciones}, {@code Iva}…) es vacía, un texto vacío es null, y una fecha «NULL» (como manda ARCA el
 * {@code FchBaja} de un punto de venta sin baja, inferido) también. Un número obligatorio que no viene vale 0, como el
 * {@code CbteNro} de {@code FECompUltimoAutorizado}. Lo que no tiene la forma esperada (otro elemento, un {@code soap:Fault},
 * un número que no es número, un importe que no entra) es {@link XmlIlegibleException}, sin el documento en el mensaje.
 */
public final class LectorWsfe {

    private static final String NS = MetodoWsfe.NAMESPACE;
    private static final DateTimeFormatter FECHA = DateTimeFormatter.BASIC_ISO_DATE;
    private static final DateTimeFormatter FECHA_HORA = DateTimeFormatter.ofPattern("uuuuMMddHHmmss");

    private LectorWsfe() {
    }

    /** {@code FEDummyResult}: {@code AppServer}, {@code DbServer} y {@code AuthServer}. */
    public static FeDummyResponse feDummy(String sobre) {
        return leer(sobre, MetodoWsfe.FE_DUMMY, r -> new FeDummyResponse(texto(r, "AppServer"), texto(r, "DbServer"),
                texto(r, "AuthServer")));
    }

    /** {@code FEParamGetPtosVentaResult}: {@code ResultGet/PtoVenta}, {@code Errors} y {@code Events}. */
    public static ConEventos<FeParamGetPtosVentaResponse> feParamGetPtosVenta(String sobre) {
        return leer(sobre, MetodoWsfe.FE_PARAM_GET_PTOS_VENTA, r -> new ConEventos<>(new FeParamGetPtosVentaResponse(
                items(r, "ResultGet", "PtoVenta").stream().map(LectorWsfe::puntoDeVenta).toList(), errores(r)),
                eventos(r)));
    }

    /** {@code FECompUltimoAutorizadoResult}: {@code PtoVta}, {@code CbteTipo}, {@code CbteNro} (ausente, 0) y {@code Errors}. */
    public static ConEventos<FeCompUltimoAutorizadoResponse> feCompUltimoAutorizado(String sobre) {
        return leer(sobre, MetodoWsfe.FE_COMP_ULTIMO_AUTORIZADO, r -> new ConEventos<>(new FeCompUltimoAutorizadoResponse(
                entero(r, "PtoVta"), entero(r, "CbteTipo"), largo(r, "CbteNro"), errores(r)), eventos(r)));
    }

    /**
     * {@code FECAESolicitarResult}: la cabecera, el único detalle (con sus {@code Observaciones}, el {@code CAE} y su
     * {@code CAEFchVto}), {@code Errors} y {@code Events}.
     *
     * <p>Una falla de la cabecera o de la autenticación (como el 600) puede venir sin {@code FeCabResp}, que el WSDL permite
     * omitir: con {@code Errors}, la cabecera se toma como rechazada (R), con ceros en lo demás, porque ARCA no procesó el
     * comprobante (inferido; la forma real se ve en la rama 11). Lo mismo si la cabecera no trae {@code Resultado}.
     */
    public static ConEventos<FeCaeResponse> feCaeSolicitar(String sobre) {
        return leer(sobre, MetodoWsfe.FE_CAE_SOLICITAR, r -> {
            List<Err> errores = errores(r);
            FeCabResp cabecera = XmlSeguro.hijo(r, "FeCabResp").map(c -> cabecera(c, errores)).orElseGet(() -> {
                if (errores.isEmpty()) {
                    throw new XmlIlegibleException("La respuesta de FECAESolicitar no trae FeCabResp ni Errors.");
                }
                return new FeCabResp(0, 0, 0, null, 0, Resultado.R);
            });
            FeCaeDetResponse detalle = items(r, "FeDetResp", "FECAEDetResponse").stream().findFirst()
                    .map(LectorWsfe::detalleDeRespuesta).orElse(null);
            return new ConEventos<>(new FeCaeResponse(cabecera, detalle, errores), eventos(r));
        });
    }

    /** {@code FECompConsultarResult}: el comprobante ({@code ResultGet}, null si no vino), {@code Errors} y {@code Events}. */
    public static ConEventos<FeCompConsultarResponse> feCompConsultar(String sobre) {
        return leer(sobre, MetodoWsfe.FE_COMP_CONSULTAR, r -> new ConEventos<>(new FeCompConsultarResponse(
                XmlSeguro.hijo(r, "ResultGet").map(LectorWsfe::comprobante).orElse(null), errores(r)), eventos(r)));
    }

    /**
     * El {@code <Metodo>Result} de la respuesta, leído con {@code lectura}. Cualquier dato que no entra en los records del
     * puerto (un número mal escrito, un importe de más, una fecha imposible) vuelve como {@link XmlIlegibleException}.
     */
    private static <T> T leer(String sobre, MetodoWsfe metodo, Function<Element, T> lectura) {
        Element cuerpo = XmlSeguro.cuerpoSoap(XmlSeguro.leer(sobre));
        if (XmlSeguro.es(cuerpo, XmlSeguro.SOAP_ENV, "Fault")) {
            String codigo = XmlSeguro.elementos(cuerpo).stream().filter(e -> "faultcode".equals(e.getLocalName()))
                    .map(e -> e.getTextContent().strip()).findFirst().orElse("sin faultcode");
            throw new XmlIlegibleException("ARCA respondió un SOAP Fault a " + metodo + " (" + codigo + ").");
        }
        if (!XmlSeguro.es(cuerpo, NS, metodo.respuesta())) {
            throw new XmlIlegibleException("La respuesta a " + metodo + " no es " + metodo.respuesta() + ": vino "
                    + cuerpo.getLocalName() + ".");
        }
        Element resultado = XmlSeguro.hijo(cuerpo, metodo.resultado()).orElseThrow(
                () -> new XmlIlegibleException("La respuesta a " + metodo + " no trae " + metodo.resultado() + "."));
        try {
            return lectura.apply(resultado);
        } catch (IllegalArgumentException | NullPointerException | DateTimeException e) {
            throw new XmlIlegibleException("La respuesta a " + metodo + " no tiene la forma esperada: " + e.getMessage(), e);
        }
    }

    private static PtoVenta puntoDeVenta(Element p) {
        return new PtoVenta(entero(p, "Nro"), texto(p, "EmisionTipo"), "S".equalsIgnoreCase(texto(p, "Bloqueado")),
                fecha(p, "FchBaja"));
    }

    /** {@code FECAECabResponse}; sin {@code Resultado} y con errores, R (ver {@link #feCaeSolicitar}). */
    private static FeCabResp cabecera(Element c, List<Err> errores) {
        Resultado resultado = resultado(c);
        if (resultado == null && !errores.isEmpty()) {
            resultado = Resultado.R;
        }
        return new FeCabResp(largo(c, "Cuit"), entero(c, "PtoVta"), entero(c, "CbteTipo"), fechaHora(c, "FchProceso"),
                entero(c, "CantReg"), resultado);
    }

    /** {@code FECAEDetResponse}: los campos de {@code FEDetResponse} más {@code CAE} y {@code CAEFchVto}. */
    private static FeCaeDetResponse detalleDeRespuesta(Element d) {
        return new FeCaeDetResponse(entero(d, "Concepto"), entero(d, "DocTipo"), largo(d, "DocNro"), largo(d, "CbteDesde"),
                largo(d, "CbteHasta"), fecha(d, "CbteFch"), resultado(d), texto(d, "CAE"), fecha(d, "CAEFchVto"),
                observaciones(d));
    }

    /** {@code FECompConsResponse}: los campos de {@code FECAEDetRequest} y los del resultado. */
    private static ResultGet comprobante(Element g) {
        return new ResultGet(entero(g, "PtoVta"), entero(g, "CbteTipo"), detalle(g), resultado(g), texto(g, "CodAutorizacion"),
                texto(g, "EmisionTipo"), fecha(g, "FchVto"), fechaHora(g, "FchProceso"), observaciones(g));
    }

    /**
     * Los campos de {@code FECAEDetRequest} de un elemento: el detalle de un pedido de {@code FECAESolicitar} o la parte del
     * pedido de un {@code FECompConsResponse} (que lo extiende). Lo usan esta clase y el simulador en el borde, que lee los
     * pedidos. Lo que no tiene la forma esperada sale como {@link IllegalArgumentException} (un número mal escrito o un campo
     * que el record no acepta) o {@link DateTimeException}.
     */
    public static FeCaeDetRequest detalle(Element g) {
        return new FeCaeDetRequest(entero(g, "Concepto"), entero(g, "DocTipo"), largo(g, "DocNro"),
                largo(g, "CbteDesde"), largo(g, "CbteHasta"), fecha(g, "CbteFch"), importe(g, "ImpTotal"),
                importe(g, "ImpTotConc"), importe(g, "ImpNeto"), importe(g, "ImpOpEx"), importe(g, "ImpTrib"),
                importe(g, "ImpIVA"), texto(g, "MonId"), importe(g, "MonCotiz"), enteroOpcional(g, "CondicionIVAReceptorId"),
                items(g, "Iva", "AlicIva").stream()
                        .map(a -> new AlicIva(entero(a, "Id"), importe(a, "BaseImp"), importe(a, "Importe"))).toList(),
                items(g, "CbtesAsoc", "CbteAsoc").stream()
                        .map(a -> new CbteAsoc(entero(a, "Tipo"), entero(a, "PtoVta"), largo(a, "Nro"), texto(a, "Cuit"),
                                fecha(a, "CbteFch"))).toList(),
                XmlSeguro.hijo(g, "PeriodoAsoc").map(p -> new PeriodoAsoc(fecha(p, "FchDesde"), fecha(p, "FchHasta")))
                        .orElse(null));
    }

    private static List<Err> errores(Element padre) {
        return items(padre, "Errors", "Err").stream().map(e -> new Err(entero(e, "Code"), texto(e, "Msg"))).toList();
    }

    private static List<Evt> eventos(Element padre) {
        return items(padre, "Events", "Evt").stream().map(e -> new Evt(entero(e, "Code"), texto(e, "Msg"))).toList();
    }

    private static List<Obs> observaciones(Element padre) {
        return items(padre, "Observaciones", "Obs").stream().map(o -> new Obs(entero(o, "Code"), texto(o, "Msg")))
                .toList();
    }

    /** Los elementos {@code item} de la lista {@code lista}; vacío si la lista no vino. Un item {@code xsi:nil} se salta. */
    private static List<Element> items(Element padre, String lista, String item) {
        return XmlSeguro.hijo(padre, lista).map(l -> XmlSeguro.hijos(l, item)).orElse(List.of()).stream()
                .filter(e -> !"true".equals(e.getAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "nil")))
                .toList();
    }

    private static String texto(Element padre, String nombre) {
        return XmlSeguro.texto(padre, nombre).orElse(null);
    }

    /** Un {@code s:int}; si no vino, 0. */
    private static int entero(Element padre, String nombre) {
        return XmlSeguro.texto(padre, nombre).map(Integer::parseInt).orElse(0);
    }

    private static Integer enteroOpcional(Element padre, String nombre) {
        return XmlSeguro.texto(padre, nombre).map(Integer::valueOf).orElse(null);
    }

    /** Un {@code s:long}; si no vino, 0. */
    private static long largo(Element padre, String nombre) {
        return XmlSeguro.texto(padre, nombre).map(Long::parseLong).orElse(0L);
    }

    /** Un {@code s:double} como {@link BigDecimal} (sin pasar por double); la escala la fija el record. */
    private static BigDecimal importe(Element padre, String nombre) {
        return XmlSeguro.texto(padre, nombre).map(BigDecimal::new).orElse(null);
    }

    private static Resultado resultado(Element padre) {
        return XmlSeguro.texto(padre, "Resultado").map(Resultado::valueOf).orElse(null);
    }

    /** yyyymmdd; vacío o «NULL», null. */
    private static LocalDate fecha(Element padre, String nombre) {
        return conValor(padre, nombre).map(t -> LocalDate.parse(t, FECHA)).orElse(null);
    }

    /** yyyymmddhhmiss, hora de ARCA (Buenos Aires); vacío o «NULL», null. */
    private static LocalDateTime fechaHora(Element padre, String nombre) {
        return conValor(padre, nombre).map(t -> LocalDateTime.parse(t, FECHA_HORA)).orElse(null);
    }

    private static Optional<String> conValor(Element padre, String nombre) {
        return XmlSeguro.texto(padre, nombre).filter(t -> !"NULL".equalsIgnoreCase(t));
    }
}
