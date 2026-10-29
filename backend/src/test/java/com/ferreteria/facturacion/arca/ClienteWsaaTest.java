package com.ferreteria.facturacion.arca;

import static com.ferreteria.facturacion.arca.FixturesArca.sobre;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.facturacion.arca.ClienteWsfeTest.TransporteGrabador;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.wsfe.PedidoNoLlegoException;
import com.ferreteria.facturacion.wsfe.ResultadoInciertoException;
import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

/**
 * C3: {@link ClienteWsaa} manda {@code loginCms} por su transporte a la URL de WSAA de su ambiente (de la configuración, C6),
 * con el sobre SOAP 1.1 y la {@code SOAPAction} vacía, y lee la respuesta en dos pasos ({@code loginCmsReturn} trae el
 * {@code loginTicketResponse} escapado). Un Fault da {@link FaultWsaaException} con el código (con el prefijo {@code ns1:} o
 * sin él) y el {@code faultstring}; lo demás que no es un 200 legible es incierto. Con un transporte falso que guarda cada
 * pedido y fixtures armados con la forma del WSDL y de la especificación.
 */
class ClienteWsaaTest {

    private static final PropiedadesArca.Urls URLS = new PropiedadesArca.Urls(
            URI.create("https://wsaa.homologacion.example/ws/services/LoginCms"),
            URI.create("https://wsaa.produccion.example/ws/services/LoginCms"),
            URI.create("https://wsaa.simulador.example/ws/services/LoginCms"));
    private static final String CMS = "MIIBase64DelCmsDePrueba==";

    @Test
    @DisplayName("loginCms va a la URL de WSAA del ambiente, con SOAPAction vacía y el CMS en in0, y lee el TA en dos pasos")
    void loginCmsLeeElTaEnDosPasos() {
        TransporteGrabador transporte = new TransporteGrabador(new Transporte.Respuesta(200, sobre("logincms-respuesta")));

        RespuestaLoginCms ta = new ClienteWsaa(Ambiente.HOMOLOGACION, transporte, URLS).loginCms(CMS);

        assertThat(ta.token()).isEqualTo("PD94bWwgdmVyc2lvbj0iMS4wIj8+VE9LRU4tREUtUFJVRUJBLVdTQUE=");
        assertThat(ta.sign()).isEqualTo("U0lHTi1ERS1QUlVFQkEtV1NBQQ==");
        assertThat(ta.generado()).isEqualTo(Instant.parse("2026-10-29T13:15:31.218Z"));
        assertThat(ta.vence()).isEqualTo(Instant.parse("2026-10-30T01:15:31.218Z"));
        assertThat(ta.toString()).doesNotContain(ta.token()).doesNotContain(ta.sign());
        Transporte.Pedido pedido = transporte.pedidos.get(0);
        assertThat(pedido.destino()).isEqualTo(URLS.homologacion());
        assertThat(pedido.soapAction()).isEmpty();
        Element loginCms = XmlSeguro.cuerpoSoap(XmlSeguro.leer(pedido.sobre()));
        assertThat(XmlSeguro.es(loginCms, "http://wsaa.view.sua.dvadac.desein.afip.gov", "loginCms")).isTrue();
        assertThat(XmlSeguro.texto(loginCms, "in0")).hasValue(CMS);
    }

    @Test
    @DisplayName("cada ambiente elige su URL de WSAA de la configuración, también el simulador")
    void cadaAmbienteConSuUrl() {
        for (Ambiente ambiente : Ambiente.values()) {
            TransporteGrabador transporte = new TransporteGrabador(new Transporte.Respuesta(200, sobre("logincms-respuesta")));

            new ClienteWsaa(ambiente, transporte, URLS).loginCms(CMS);

            assertThat(transporte.pedidos.get(0).destino()).as(ambiente.name()).isEqualTo(URLS.de(ambiente));
        }
        assertThat(URLS.de(Ambiente.SIMULADOR)).isEqualTo(URLS.simulador());
    }

    @Test
    @DisplayName("un 500 con Fault da el código, con el prefijo ns1: o sin él, y el faultstring")
    void unFaultDaElCodigoYElTexto() {
        String conPrefijo = sobre("logincms-fault");
        String sinPrefijo = conPrefijo.replace("ns1:coe.alreadyAuthenticated", "coe.alreadyAuthenticated");

        for (String fault : new String[] {conPrefijo, sinPrefijo}) {
            ClienteWsaa cliente = new ClienteWsaa(Ambiente.HOMOLOGACION,
                    new TransporteGrabador(new Transporte.Respuesta(500, fault)), URLS);

            assertThatThrownBy(() -> cliente.loginCms(CMS)).isInstanceOfSatisfying(FaultWsaaException.class, e -> {
                assertThat(e.codigo()).isEqualTo("coe.alreadyAuthenticated");
                assertThat(e.faultstring()).isEqualTo("El CEE ya posee un TA valido para el acceso al WSN solicitado");
            });
        }
    }

    @Test
    @DisplayName("un status sin Fault o un cuerpo ilegible es incierto; la falla del transporte sale tal cual")
    void loQueNoEsUnaRespuestaLegible() {
        ClienteWsaa con503 = new ClienteWsaa(Ambiente.HOMOLOGACION,
                new TransporteGrabador(new Transporte.Respuesta(503, "<html>Service Unavailable</html>")), URLS);
        ClienteWsaa conHtml = new ClienteWsaa(Ambiente.HOMOLOGACION,
                new TransporteGrabador(new Transporte.Respuesta(200, "<html>proxy</html>")), URLS);
        ClienteWsaa sinConexion = new ClienteWsaa(Ambiente.HOMOLOGACION, pedido -> {
            throw new PedidoNoLlegoException("No se pudo conectar con ARCA (conexión rechazada).");
        }, URLS);

        assertThatThrownBy(() -> con503.loginCms(CMS)).isInstanceOf(ResultadoInciertoException.class)
                .hasMessageContaining("503");
        assertThatThrownBy(() -> conHtml.loginCms(CMS)).isInstanceOf(ResultadoInciertoException.class);
        assertThatThrownBy(() -> sinConexion.loginCms(CMS)).isInstanceOf(PedidoNoLlegoException.class);
    }
}
