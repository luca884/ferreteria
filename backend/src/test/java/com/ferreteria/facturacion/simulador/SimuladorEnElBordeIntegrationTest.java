package com.ferreteria.facturacion.simulador;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.facturacion.arca.ClienteWsaa;
import com.ferreteria.facturacion.arca.ClienteWsfe;
import com.ferreteria.facturacion.arca.FaultWsaaException;
import com.ferreteria.facturacion.arca.LectorWsfe;
import com.ferreteria.facturacion.arca.MetodoWsfe;
import com.ferreteria.facturacion.arca.PlantillasWsfe;
import com.ferreteria.facturacion.arca.PropiedadesArca;
import com.ferreteria.facturacion.arca.RespuestaLoginCms;
import com.ferreteria.facturacion.arca.TicketDeAcceso;
import com.ferreteria.facturacion.arca.Tra;
import com.ferreteria.facturacion.arca.Transporte;
import com.ferreteria.facturacion.arca.XmlSeguro;
import com.ferreteria.facturacion.credencial.CredencialDePrueba;
import com.ferreteria.facturacion.credencial.FirmaCms;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.motor.MotorFiscalTestBase;
import com.ferreteria.facturacion.wsfe.Err;
import com.ferreteria.facturacion.wsfe.FeCompUltimoAutorizadoResponse;
import com.ferreteria.facturacion.wsfe.PedidoNoLlegoException;
import com.ferreteria.tenant.TenantContext;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationContext;

/**
 * El simulador en el borde (rama 6, S6), con el XML, el CMS y el TA reales que arman {@link ClienteWsaa}, {@link FirmaCms} y
 * {@link PlantillasWsfe}: WSAA rechaza un TRA que no valida ({@code xml.bad}) y un CMS que no firmó la CA de la ferretería
 * ({@code cms.cert.untrusted}), emite el TA con su lapso y lo recuerda en {@code ticket_simulado}; WSFEv1 rechaza un pedido que
 * no valida contra el XSD (un SOAP Fault) y un {@code Auth} que no emitió (600).
 */
class SimuladorEnElBordeIntegrationTest extends SimuladorEnElBordeTestBase {

    @Autowired
    @Qualifier(SimuladorEnElBorde.BEAN)
    Transporte borde;
    @Autowired
    FirmaCms firma;
    @Autowired
    PropiedadesArca propiedades;
    @Autowired
    WsaaDelSimulador wsaaDelSimulador;
    @Autowired
    WsfeDelSimulador wsfeDelSimulador;
    @Autowired
    ApplicationContext contexto;

    private ClienteWsaa wsaa() {
        return new ClienteWsaa(Ambiente.SIMULADOR, borde, propiedades.wsaa());
    }

    /** El CMS de {@code contenido}, firmado con la credencial activa del simulador de la ferretería del contexto. */
    private String cms(String contenido) {
        return firma.firmar(Ambiente.SIMULADOR, contenido.getBytes(StandardCharsets.UTF_8)).cmsBase64();
    }

    private String traValido() {
        return Tra.armar(reloj.instant(), ClienteWsfe.SERVICIO);
    }

    private Transporte.Respuesta aWsfe(MetodoWsfe metodo, String sobre) {
        return borde.enviar(new Transporte.Pedido(propiedades.wsfe().de(Ambiente.SIMULADOR), metodo.soapAction(), sobre));
    }

    // ---- WSAA ----

    @Test
    @DisplayName("WSAA: emite el TA vigente 12 h, recuerda su huella y, dentro del lapso, contesta coe.alreadyAuthenticated; "
            + "pasado el lapso emite otro")
    void emiteElTaConElLapso() {
        RespuestaLoginCms ta = wsaa().loginCms(cms(traValido()));

        assertThat(ta.generado()).isEqualTo(AHORA);
        assertThat(ta.vence()).isEqualTo(AHORA.plus(Duration.ofHours(12)));
        Map<String, Object> fila = jdbc.queryForMap("select huella, servicio from ticket_simulado where ferreteria_id = ?",
                ferreteriaId);
        assertThat(fila).containsEntry("servicio", "wsfe")
                .containsEntry("huella", WsaaDelSimulador.huella(ta.token(), ta.sign()));

        reloj.avanzar(propiedades.lapsoTa().simulador().minusSeconds(1));
        assertThatThrownBy(() -> wsaa().loginCms(cms(traValido())))
                .isInstanceOfSatisfying(FaultWsaaException.class,
                        e -> assertThat(e.codigo()).isEqualTo("coe.alreadyAuthenticated"));

        reloj.avanzar(Duration.ofSeconds(1));
        RespuestaLoginCms otro = wsaa().loginCms(cms(traValido()));
        assertThat(otro.token()).isNotEqualTo(ta.token());
        assertThat(jdbc.queryForObject("select huella from ticket_simulado where ferreteria_id = ?", String.class,
                ferreteriaId)).isEqualTo(WsaaDelSimulador.huella(otro.token(), otro.sign()));
    }

    @Test
    @DisplayName("WSAA: un TRA que no valida contra el XSD de WSAA 1.2.2, o un sobre sin loginCms, es xml.bad")
    void unXmlQueNoValidaEsXmlBad() {
        String sinService = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><loginTicketRequest version=\"1.0\"><header>"
                + "<uniqueId>1</uniqueId></header></loginTicketRequest>";

        assertThatThrownBy(() -> wsaa().loginCms(cms(sinService)))
                .isInstanceOfSatisfying(FaultWsaaException.class, e -> assertThat(e.codigo()).isEqualTo("xml.bad"));
        Transporte.Respuesta otroMetodo = borde.enviar(new Transporte.Pedido(propiedades.wsaa().de(Ambiente.SIMULADOR), "",
                "<soap:Envelope xmlns:soap=\"" + XmlSeguro.SOAP_ENV + "\"><soap:Body><otro xmlns=\""
                        + ClienteWsaa.NAMESPACE + "\"/></soap:Body></soap:Envelope>"));
        assertThat(otroMetodo.status()).isEqualTo(500);
        assertThat(otroMetodo.cuerpo()).contains("ns1:xml.bad");
        assertThat(jdbc.queryForObject("select count(*) from ticket_simulado where ferreteria_id = ?", Long.class,
                ferreteriaId)).isZero();
    }

    @Test
    @DisplayName("WSAA: un CMS que firmó la CA del simulador de otra ferretería es cms.cert.untrusted")
    void unCmsDeOtraCaEsUntrusted() {
        long otraCuit = MotorFiscalTestBase.cuitNuevo();
        long otra = ferreteriaSinCredencial(otraCuit);
        CredencialDePrueba.delSimulador(credenciales, jdbc, otra);
        TenantContext.setCurrentFerreteriaId(otra);
        String deLaOtra = cms(traValido());
        TenantContext.setCurrentFerreteriaId(ferreteriaId);

        assertThatThrownBy(() -> wsaa().loginCms(deLaOtra))
                .isInstanceOfSatisfying(FaultWsaaException.class,
                        e -> assertThat(e.codigo()).isEqualTo("cms.cert.untrusted"));
    }

    // ---- WSFEv1 ----

    @Test
    @DisplayName("WSFEv1: un pedido que no valida contra el XSD del WSDL es un SOAP Fault (soap:Client, status 500)")
    void unPedidoQueNoValidaEsUnFault() {
        RespuestaLoginCms ta = wsaa().loginCms(cms(traValido()));
        // Los mismos campos en otro orden: se leerían por nombre, pero la secuencia del XSD no lo admite.
        String desordenado = PlantillasWsfe.feCompUltimoAutorizado(new TicketDeAcceso(ta.token(), ta.sign(), cuit), cuit, 1,
                6).replace("<PtoVta>1</PtoVta><CbteTipo>6</CbteTipo>", "<CbteTipo>6</CbteTipo><PtoVta>1</PtoVta>");
        assertThat(desordenado).contains("<CbteTipo>6</CbteTipo><PtoVta>1</PtoVta>");

        Transporte.Respuesta respuesta = aWsfe(MetodoWsfe.FE_COMP_ULTIMO_AUTORIZADO, desordenado);

        assertThat(respuesta.status()).isEqualTo(500);
        assertThat(XmlSeguro.es(XmlSeguro.cuerpoSoap(XmlSeguro.leer(respuesta.cuerpo())), XmlSeguro.SOAP_ENV, "Fault"))
                .isTrue();
        assertThat(respuesta.cuerpo()).contains("<faultcode>soap:Client</faultcode>").doesNotContain(ta.token());
    }

    @Test
    @DisplayName("WSFEv1: un Auth que no emitió su WSAA, o el suyo vencido, da el 600; el suyo vigente se atiende")
    void unAuthQueNoEmitioEs600() {
        RespuestaLoginCms ta = wsaa().loginCms(cms(traValido()));
        TicketDeAcceso suyo = new TicketDeAcceso(ta.token(), ta.sign(), cuit);
        TicketDeAcceso ajeno = new TicketDeAcceso("TOKEN-QUE-NO-EMITIO", ta.sign(), cuit);

        FeCompUltimoAutorizadoResponse conAjeno = ultimo(ajeno);
        FeCompUltimoAutorizadoResponse conSuyo = ultimo(suyo);
        reloj.avanzar(Duration.ofHours(12));
        FeCompUltimoAutorizadoResponse vencido = ultimo(suyo);

        assertThat(conAjeno.errors()).extracting(Err::code).containsExactly(600);
        assertThat(conSuyo.errors()).isEmpty();
        assertThat(conSuyo.cbteNro()).isZero();
        assertThat(vencido.errors()).extracting(Err::code).containsExactly(600);
    }

    @Test
    @DisplayName("WSFEv1: FEDummy contesta OK sin Auth, y FEParamGetPtosVenta da el punto de venta de la configuración, CAE")
    void feDummyYPuntosDeVenta() {
        RespuestaLoginCms ta = wsaa().loginCms(cms(traValido()));

        assertThat(LectorWsfe.feDummy(aWsfe(MetodoWsfe.FE_DUMMY, PlantillasWsfe.feDummy()).cuerpo()))
                .satisfies(d -> assertThat(d.appServer() + d.dbServer() + d.authServer()).isEqualTo("OKOKOK"));
        assertThat(LectorWsfe.feParamGetPtosVenta(aWsfe(MetodoWsfe.FE_PARAM_GET_PTOS_VENTA,
                PlantillasWsfe.feParamGetPtosVenta(new TicketDeAcceso(ta.token(), ta.sign(), cuit), cuit)).cuerpo())
                .respuesta().resultGet())
                .singleElement().satisfies(p -> {
                    assertThat(p.nro()).isEqualTo(1);
                    assertThat(p.emisionTipo()).isEqualTo("CAE");
                    assertThat(p.bloqueado()).isFalse();
                    assertThat(p.fchBaja()).isNull();
                });
    }

    @Test
    @DisplayName("un destino que no es del simulador no llega a ningún lado (PedidoNoLlegoException)")
    void otroDestinoNoLlega() {
        assertThatThrownBy(() -> borde.enviar(new Transporte.Pedido(propiedades.wsfe().de(Ambiente.HOMOLOGACION),
                MetodoWsfe.FE_DUMMY.soapAction(), PlantillasWsfe.feDummy())))
                .isInstanceOf(PedidoNoLlegoException.class);
        assertThatThrownBy(() -> borde.enviar(new Transporte.Pedido(URI.create("https://otro.invalid/"), "", "")))
                .isInstanceOf(PedidoNoLlegoException.class);
    }

    // ---- ARCA caída (solo dev, rama 7) ----

    @Test
    @DisplayName("ARCA caída en dev: mientras existe el archivo, WSFEv1 no contesta (PedidoNoLlegoException) y WSAA sí; sin el "
            + "archivo vuelve a contestar. Fuera de dev no hay interruptor")
    void laCaidaDeDevApagaWsfeMientrasExisteElArchivo(@TempDir Path carpeta) throws IOException {
        Path archivo = carpeta.resolve("arca-caida");
        Transporte conCaida = new SimuladorEnElBorde(propiedades, wsaaDelSimulador, wsfeDelSimulador,
                Optional.of(new CaidaSimulada(archivo)));
        Transporte.Pedido dummy = new Transporte.Pedido(propiedades.wsfe().de(Ambiente.SIMULADOR),
                MetodoWsfe.FE_DUMMY.soapAction(), PlantillasWsfe.feDummy());
        assertThat(conCaida.enviar(dummy).status()).isEqualTo(200);

        Files.createFile(archivo);
        assertThatThrownBy(() -> conCaida.enviar(dummy)).isInstanceOf(PedidoNoLlegoException.class)
                .hasMessageContaining("caída");
        assertThat(new ClienteWsaa(Ambiente.SIMULADOR, conCaida, propiedades.wsaa()).loginCms(cms(traValido())).token())
                .isNotBlank();

        Files.delete(archivo);
        assertThat(conCaida.enviar(dummy).status()).isEqualTo(200);
        assertThat(contexto.getBeansOfType(CaidaSimulada.class)).isEmpty();
    }

    private FeCompUltimoAutorizadoResponse ultimo(TicketDeAcceso ticket) {
        Transporte.Respuesta respuesta = aWsfe(MetodoWsfe.FE_COMP_ULTIMO_AUTORIZADO,
                PlantillasWsfe.feCompUltimoAutorizado(ticket, cuit, 1, 6));
        assertThat(respuesta.status()).isEqualTo(200);
        return LectorWsfe.feCompUltimoAutorizado(respuesta.cuerpo()).respuesta();
    }
}
