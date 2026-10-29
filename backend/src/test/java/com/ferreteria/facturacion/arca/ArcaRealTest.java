package com.ferreteria.facturacion.arca;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.facturacion.model.Ambiente;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocket;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * S7: FEDummy contra ARCA de verdad, en homologación (wswhomo) y en producción (servicios1), con el {@link ClienteWsfe} y el
 * {@link TransporteHttp} reales y las URL y los timeouts del {@code application.yml} (C6). Sin credencial ni CUIT: FEDummy
 * no lleva {@code Auth}, así que no hace falta un ticket de WSAA. Además, el handshake con servicios1, hecho con el mismo
 * {@code SSLContext} y los mismos parámetros que el transporte, negocia una suite ECDHE: si se le ofrece una lista amplia,
 * servicios1 elige DHE de 1024 bits (L3).
 *
 * <p>Sale a internet, así que no corre en la suite ni en {@code -Pimagen}: solo con {@code ./mvnw test -Parca-real}. Si
 * falla, primero hay que mirar si ARCA está caída.
 */
@Tag("arca-real")
class ArcaRealTest {

    private static PropiedadesArca propiedades;

    /** Las propiedades de ARCA del {@code application.yml} real, con la conversión de Spring Boot, como arranca la app. */
    @BeforeAll
    static void configuracionDelYml() {
        AtomicReference<PropiedadesArca> leidas = new AtomicReference<>();
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withInitializer(c -> c.getBeanFactory().setConversionService(new ApplicationConversionService()))
                .withUserConfiguration(ConfiguracionArca.class)
                .run(c -> leidas.set(c.getBean(PropiedadesArca.class)));
        propiedades = leidas.get();
    }

    /** El cliente real de ese ambiente; FEDummy no pide ticket, así que el proveedor falla si alguien lo llama. */
    private static ClienteWsfe cliente(Ambiente ambiente) {
        ProveedorDeTicket sinTicket = new ProveedorDeTicket() {
            @Override
            public TicketDeAcceso ticket(Ambiente a, String servicio) {
                throw new AssertionError("FEDummy no lleva Auth: no tiene que pedir un ticket.");
            }

            @Override
            public void descartar(Ambiente a, String servicio, TicketDeAcceso rechazado) {
                throw new AssertionError("FEDummy no lleva Auth: no tiene que descartar un ticket.");
            }
        };
        TransporteHttp transporte = new TransporteHttp(propiedades.timeoutConexion(), propiedades.timeoutLectura());
        return new ClienteWsfe(ambiente, transporte, propiedades.wsfe().de(ambiente), sinTicket);
    }

    @Test
    @DisplayName("FEDummy en homologación (wswhomo): los tres servidores OK")
    void feDummyEnHomologacion() {
        assertThat(propiedades.wsfe().de(Ambiente.HOMOLOGACION).getHost()).isEqualTo("wswhomo.afip.gov.ar");

        FeDummyResponse respuesta = cliente(Ambiente.HOMOLOGACION).feDummy();

        assertThat(respuesta).as("FEDummy de homologación").isEqualTo(new FeDummyResponse("OK", "OK", "OK"));
    }

    @Test
    @DisplayName("FEDummy en producción (servicios1): los tres servidores OK")
    void feDummyEnProduccion() {
        assertThat(propiedades.wsfe().de(Ambiente.PRODUCCION).getHost()).isEqualTo("servicios1.afip.gov.ar");

        FeDummyResponse respuesta = cliente(Ambiente.PRODUCCION).feDummy();

        assertThat(respuesta).as("FEDummy de producción").isEqualTo(new FeDummyResponse("OK", "OK", "OK"));
    }

    @Test
    @DisplayName("servicios1 negocia una suite ECDHE con el TLS del transporte (en TLS 1.2; 1.3 siempre es efímero)")
    void servicios1NegociaEcdhe() throws Exception {
        URI url = propiedades.wsfe().de(Ambiente.PRODUCCION);
        int puerto = url.getPort() == -1 ? 443 : url.getPort();

        SSLSession sesion;
        try (SSLSocket socket = (SSLSocket) TransporteHttp.contexto(null).getSocketFactory()
                .createSocket(url.getHost(), puerto)) {
            socket.setSoTimeout((int) propiedades.timeoutLectura().toMillis());
            socket.setSSLParameters(TransporteHttp.parametros());
            socket.startHandshake();
            sesion = socket.getSession();
        }

        assertThat(sesion.getProtocol()).isIn("TLSv1.2", "TLSv1.3");
        assertThat(List.of(TransporteHttp.SUITES)).contains(sesion.getCipherSuite());
        if ("TLSv1.2".equals(sesion.getProtocol())) {
            assertThat(sesion.getCipherSuite()).as("suite de TLS 1.2 con servicios1").startsWith("TLS_ECDHE_");
        }
    }
}
