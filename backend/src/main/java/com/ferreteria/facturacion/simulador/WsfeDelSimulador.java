package com.ferreteria.facturacion.simulador;

import com.ferreteria.facturacion.arca.ClienteWsfe;
import com.ferreteria.facturacion.arca.EsquemaWsfe;
import com.ferreteria.facturacion.arca.FeParamGetPtosVentaResponse;
import com.ferreteria.facturacion.arca.LectorWsfe;
import com.ferreteria.facturacion.arca.MetodoWsfe;
import com.ferreteria.facturacion.arca.PtoVenta;
import com.ferreteria.facturacion.arca.Transporte;
import com.ferreteria.facturacion.arca.XmlIlegibleException;
import com.ferreteria.facturacion.arca.XmlSeguro;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.model.ConfigArca;
import com.ferreteria.facturacion.repository.ConfigArcaRepository;
import com.ferreteria.facturacion.wsfe.Auth;
import com.ferreteria.facturacion.wsfe.Err;
import com.ferreteria.facturacion.wsfe.FeCabReq;
import com.ferreteria.facturacion.wsfe.FeCaeDetRequest;
import com.ferreteria.facturacion.wsfe.FeCaeReq;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.w3c.dom.Element;

/**
 * WSFEv1 del simulador en el borde (rama 6, S6): recibe el sobre SOAP que armó {@code ClienteWsfe}, como lo recibiría ARCA, y
 * contesta con la forma del WSDL. Lo que decide sobre los comprobantes es el núcleo de la rama 2 ({@link SimuladorWsfe}, con
 * sus reglas de {@link ReglasWsfe}); esta clase pone lo que ARCA hace antes:
 * <ol>
 *   <li>valida el hijo del {@code soap:Body} contra el XSD del WSDL ({@link EsquemaWsfe}, C5) y que la {@code SOAPAction} sea
 *       la de ese método: si no, un SOAP Fault {@code soap:Client} con status 500, como ASMX;</li>
 *   <li>verifica el {@code Auth}: el token y el sign tienen que ser los del último TA que emitió su WSAA para la ferretería
 *       ({@code ticket_simulado}), sin vencer; si no, el error 600 en {@code Errors}, como ARCA con un TA que no acepta
 *       (C4);</li>
 *   <li>lee el pedido hacia los records del puerto, se lo pasa al núcleo y escribe su respuesta.</li>
 * </ol>
 * {@code FEDummy} contesta los tres servidores OK, sin {@code Auth}. {@code FEParamGetPtosVenta} no está en el núcleo: el
 * simulador da como único punto de venta el de la configuración de la ferretería (de tipo CAE, sin bloqueo ni baja), y el 602
 * si no tiene uno en SIMULADOR.
 *
 * <p>Todo es de la ferretería del request ({@code TenantContext}). Ni el sobre ni el {@code Auth} van a un log.
 */
@Component
class WsfeDelSimulador {

    /** El código de WSFEv1 para un {@code Auth} que no se acepta. */
    static final int TA_RECHAZADO = 600;
    static final String TEXTO_600 = "ValidacionDeToken: el simulador no emitió ese token y ese sign, o ya vencieron.";
    /** El código de WSFEv1 para una consulta sin resultados. */
    static final int SIN_RESULTADOS = 602;
    static final String TEXTO_602 = "Sin Resultados: la ferretería no tiene un punto de venta en el simulador.";
    static final String NO_SE_LEE = "Server was unable to read request: el pedido no valida contra el XSD de WSFEv1.";

    private final SimuladorWsfe nucleo;
    private final TicketSimuladoRepository tickets;
    private final ConfigArcaRepository configs;
    private final TransactionTemplate lectura;
    private final Clock reloj;

    WsfeDelSimulador(SimuladorWsfe nucleo, TicketSimuladoRepository tickets, ConfigArcaRepository configs,
            PlatformTransactionManager transacciones, Clock reloj) {
        this.nucleo = nucleo;
        this.tickets = tickets;
        this.configs = configs;
        this.lectura = new TransactionTemplate(transacciones);
        this.lectura.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.lectura.setReadOnly(true);
        this.reloj = reloj;
    }

    /** Atiende un pedido de WSFEv1. */
    Transporte.Respuesta atender(String soapAction, String sobre) {
        Element pedido;
        try {
            pedido = EsquemaWsfe.validarSobre(sobre);
        } catch (XmlIlegibleException e) {
            return fault(NO_SE_LEE);
        }
        Optional<MetodoWsfe> deLaLista = Arrays.stream(MetodoWsfe.values())
                .filter(m -> XmlSeguro.es(pedido, MetodoWsfe.NAMESPACE, m.nombre())).findFirst();
        if (deLaLista.isEmpty()) {
            return fault("El simulador no atiende " + pedido.getLocalName() + ".");
        }
        MetodoWsfe metodo = deLaLista.get();
        if (!metodo.soapAction().equals(soapAction)) {
            return fault("Server did not recognize the value of HTTP Header SOAPAction: " + soapAction + ".");
        }
        if (metodo == MetodoWsfe.FE_DUMMY) {
            return ok(SobresDelSimulador.feDummy());
        }
        Optional<Auth> auth = autorizado(pedido);
        if (auth.isEmpty()) {
            return ok(SobresDelSimulador.rechazoDelAuth(metodo, new Err(TA_RECHAZADO, TEXTO_600)));
        }
        try {
            return ok(responder(metodo, pedido, auth.get()));
        } catch (IllegalArgumentException | DateTimeException e) {
            // Un valor que el XSD acepta pero el manual no (por ejemplo, una CUIT de 10 dígitos): el pedido no se lee.
            return fault("Server was unable to read request: un valor del pedido no tiene la forma de WSFEv1.");
        }
    }

    private String responder(MetodoWsfe metodo, Element pedido, Auth auth) {
        return switch (metodo) {
            case FE_PARAM_GET_PTOS_VENTA -> SobresDelSimulador.feParamGetPtosVenta(puntosDeVenta());
            case FE_COMP_ULTIMO_AUTORIZADO -> SobresDelSimulador.feCompUltimoAutorizado(
                    nucleo.feCompUltimoAutorizado(auth, entero(pedido, "PtoVta"), entero(pedido, "CbteTipo")));
            case FE_CAE_SOLICITAR -> SobresDelSimulador.feCaeSolicitar(nucleo.feCaeSolicitar(auth, feCaeReq(pedido)));
            case FE_COMP_CONSULTAR -> {
                Element consulta = hijo(pedido, "FeCompConsReq");
                yield SobresDelSimulador.feCompConsultar(nucleo.feCompConsultar(auth, entero(consulta, "CbteTipo"),
                        entero(consulta, "PtoVta"), largo(consulta, "CbteNro")));
            }
            case FE_DUMMY -> SobresDelSimulador.feDummy();
        };
    }

    /**
     * El {@code Auth} del pedido, si el simulador lo acepta: su token y su sign son los del último TA que emitió para
     * {@code wsfe} en la ferretería, y ese TA no venció. La CUIT es la que trae el pedido.
     */
    private Optional<Auth> autorizado(Element pedido) {
        Optional<Element> auth = XmlSeguro.hijo(pedido, "Auth");
        Optional<String> token = auth.flatMap(a -> XmlSeguro.texto(a, "Token"));
        Optional<String> sign = auth.flatMap(a -> XmlSeguro.texto(a, "Sign"));
        Optional<String> cuit = auth.flatMap(a -> XmlSeguro.texto(a, "Cuit"));
        if (token.isEmpty() || sign.isEmpty() || cuit.isEmpty()) {
            return Optional.empty();
        }
        Instant ahora = reloj.instant();
        String huella = WsaaDelSimulador.huella(token.get(), sign.get());
        Boolean emitido = lectura.execute(estado -> tickets.deClaveScoped(ClienteWsfe.SERVICIO)
                .filter(t -> t.getHuella().equals(huella) && t.vigenteEn(ahora)).isPresent());
        if (!Boolean.TRUE.equals(emitido)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new Auth(Ambiente.SIMULADOR, Long.parseLong(cuit.get())));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** {@code FEParamGetPtosVenta}: el punto de venta de la configuración en SIMULADOR, o el 602. */
    private FeParamGetPtosVentaResponse puntosDeVenta() {
        Optional<Integer> punto = lectura.execute(estado -> configs.deFerreteriaScoped()
                .filter(c -> c.getAmbiente() == Ambiente.SIMULADOR).map(ConfigArca::getPuntoVenta));
        if (punto == null || punto.isEmpty()) {
            return new FeParamGetPtosVentaResponse(List.of(), List.of(new Err(SIN_RESULTADOS, TEXTO_602)));
        }
        return new FeParamGetPtosVentaResponse(List.of(new PtoVenta(punto.get(), "CAE", false, null)), List.of());
    }

    /** {@code FeCAEReq}: la cabecera y el único detalle (el simulador atiende un comprobante por pedido, como el motor). */
    private static FeCaeReq feCaeReq(Element pedido) {
        Element req = hijo(pedido, "FeCAEReq");
        Element cabecera = hijo(req, "FeCabReq");
        List<Element> detalles = XmlSeguro.hijo(req, "FeDetReq").map(d -> XmlSeguro.hijos(d, "FECAEDetRequest"))
                .orElse(List.of());
        if (detalles.size() != 1) {
            throw new IllegalArgumentException("El simulador atiende un comprobante por pedido: vinieron " + detalles.size()
                    + ".");
        }
        FeCaeDetRequest detalle = LectorWsfe.detalle(detalles.get(0));
        return new FeCaeReq(new FeCabReq(entero(cabecera, "CantReg"), entero(cabecera, "PtoVta"),
                entero(cabecera, "CbteTipo")), detalle);
    }

    private static Element hijo(Element padre, String nombre) {
        return XmlSeguro.hijo(padre, nombre)
                .orElseThrow(() -> new IllegalArgumentException("El pedido no trae " + nombre + "."));
    }

    private static int entero(Element padre, String nombre) {
        return Integer.parseInt(XmlSeguro.texto(padre, nombre)
                .orElseThrow(() -> new IllegalArgumentException("El pedido no trae " + nombre + ".")));
    }

    private static long largo(Element padre, String nombre) {
        return Long.parseLong(XmlSeguro.texto(padre, nombre)
                .orElseThrow(() -> new IllegalArgumentException("El pedido no trae " + nombre + ".")));
    }

    private static Transporte.Respuesta ok(String cuerpo) {
        return new Transporte.Respuesta(200, cuerpo);
    }

    private static Transporte.Respuesta fault(String texto) {
        return new Transporte.Respuesta(500, SobresDelSimulador.faultWsfe(texto));
    }
}
