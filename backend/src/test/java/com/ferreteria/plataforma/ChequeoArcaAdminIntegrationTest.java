package com.ferreteria.plataforma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.facturacion.arca.ClienteWsfe;
import com.ferreteria.facturacion.arca.ClientesWsfe;
import com.ferreteria.facturacion.arca.TicketsDeAcceso;
import com.ferreteria.facturacion.arca.TransporteHttp;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.support.IntegrationTestBase;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * El chequeo FEDummy del super admin (rama 6, C2, S9) de punta a punta: el endpoint, los roles y el pasaje por
 * {@code facturacion.api}, con el {@code ClienteWsfe} y el {@code TransporteHttp} reales. Homologación va a un
 * {@code HttpServer} del JDK que contesta el FEDummy real (C7) y producción, a un puerto cerrado (conexión rechazada): nunca a
 * ARCA.
 */
@Import(ChequeoArcaAdminIntegrationTest.ArcaDePrueba.class)
class ChequeoArcaAdminIntegrationTest extends IntegrationTestBase {

    static final String FEDUMMY = "/api/admin/arca/fedummy";

    /** Los clientes de WSFEv1 de este contexto: homologación al servidor de prueba, producción a un puerto cerrado. */
    @TestConfiguration
    static class ArcaDePrueba {

        static final AtomicInteger PEDIDOS = new AtomicInteger();
        static final HttpServer SERVIDOR = servidor();

        private static HttpServer servidor() {
            try {
                HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                servidor.createContext("/wsfev1/service.asmx", intercambio -> {
                    PEDIDOS.incrementAndGet();
                    intercambio.getRequestBody().readAllBytes();
                    byte[] cuerpo = fixture("fedummy-homologacion").getBytes(StandardCharsets.UTF_8);
                    intercambio.getResponseHeaders().add("Content-Type", "text/xml; charset=utf-8");
                    intercambio.sendResponseHeaders(200, cuerpo.length);
                    try (OutputStream salida = intercambio.getResponseBody()) {
                        salida.write(cuerpo);
                    }
                });
                servidor.start();
                return servidor;
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        private static int puertoCerrado() {
            try (ServerSocket socket = new ServerSocket(0)) {
                return socket.getLocalPort();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        @Bean
        @Primary
        ClientesWsfe clientesWsfeDePrueba(TicketsDeAcceso tickets) {
            TransporteHttp http = new TransporteHttp(Duration.ofSeconds(2), Duration.ofSeconds(5));
            URI homologacion = URI.create("http://127.0.0.1:" + SERVIDOR.getAddress().getPort() + "/wsfev1/service.asmx");
            URI produccion = URI.create("http://127.0.0.1:" + puertoCerrado() + "/wsfev1/service.asmx");
            return new ClientesWsfe(List.of(new ClienteWsfe(Ambiente.HOMOLOGACION, http, homologacion, tickets),
                    new ClienteWsfe(Ambiente.PRODUCCION, http, produccion, tickets)));
        }
    }

    private static String fixture(String nombre) {
        try (InputStream entrada = ChequeoArcaAdminIntegrationTest.class.getClassLoader()
                .getResourceAsStream("facturacion/arca/" + nombre + ".xml")) {
            return new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("el super admin corre FEDummy por homologación y por producción: OK en una, el motivo legible en la otra")
    void elSuperAdminChequeaLosDosAmbientes() throws Exception {
        int antes = ArcaDePrueba.PEDIDOS.get();

        String cuerpo = postJson(FEDUMMY, login("admin@demo.local"), "").andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode chequeo = objectMapper.readTree(cuerpo);

        JsonNode homologacion = chequeo.get("homologacion");
        assertThat(homologacion.get("ok").asBoolean()).isTrue();
        assertThat(homologacion.get("appServer").asText()).isEqualTo("OK");
        assertThat(homologacion.get("dbServer").asText()).isEqualTo("OK");
        assertThat(homologacion.get("authServer").asText()).isEqualTo("OK");
        assertThat(homologacion.get("milisegundos").isIntegralNumber()).isTrue();
        assertThat(homologacion.get("error").isNull()).isTrue();
        JsonNode produccion = chequeo.get("produccion");
        assertThat(produccion.get("ok").asBoolean()).isFalse();
        assertThat(produccion.get("appServer").isNull()).isTrue();
        assertThat(produccion.get("milisegundos").isNull()).isTrue();
        assertThat(produccion.get("error").asText()).isEqualTo("No se pudo conectar con ARCA (conexión rechazada).");
        assertThat(homologacion.fieldNames()).toIterable()
                .containsExactly("ok", "appServer", "dbServer", "authServer", "milisegundos", "error");
        assertThat(ArcaDePrueba.PEDIDOS.get() - antes).isEqualTo(1);
    }

    @Test
    @DisplayName("el Dueño y el Vendedor reciben 403, sin llamar a ARCA")
    void soloElSuperAdmin() throws Exception {
        int antes = ArcaDePrueba.PEDIDOS.get();

        postJson(FEDUMMY, login("dueno@tornillo.demo"), "").andExpect(status().isForbidden());
        postJson(FEDUMMY, login("vendedor@tornillo.demo"), "").andExpect(status().isForbidden());

        assertThat(ArcaDePrueba.PEDIDOS.get()).isEqualTo(antes);
    }
}
