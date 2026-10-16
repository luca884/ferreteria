package com.ferreteria.cotizacion.cliente;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.ferreteria.cotizacion.model.CotizacionLeida;
import com.ferreteria.cotizacion.model.FuenteNoDisponibleException;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

/** Bluelytics (el respaldo) se prueba con respuestas fijas: ningun test llama a la API real. */
class BluelyticsFuenteTest {

    private static final String BASE = "http://bluelytics.test";

    private final RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
    private final MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
    private final BluelyticsFuente fuente = new BluelyticsFuente(builder.build());

    private void latestOk() throws IOException {
        String json = new ClassPathResource("cotizacion/bluelytics-latest.json").getContentAsString(StandardCharsets.UTF_8);
        servidor.expect(requestTo(BASE + "/v2/latest")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private static CotizacionLeida de(List<CotizacionLeida> leidas, String moneda, String casa) {
        return leidas.stream().filter(l -> l.moneda().equals(moneda) && l.casa().equals(casa)).findFirst()
                .orElseThrow(() -> new AssertionError("Falta " + moneda + ":" + casa + " en " + leidas));
    }

    @Test
    @DisplayName("se llama BLUELYTICS")
    void nombre() {
        assertThat(fuente.nombre()).isEqualTo("BLUELYTICS");
    }

    @Test
    @DisplayName("oficial 948/998 se lee como USD:oficial con compra 948 y venta 998")
    void leeElOficial() throws IOException {
        latestOk();

        CotizacionLeida oficial = de(fuente.leer(), "USD", "oficial");

        assertThat(oficial.compra()).isEqualByComparingTo("948");
        assertThat(oficial.venta()).isEqualByComparingTo("998");
        assertThat(oficial.nombre()).isEqualTo("Oficial");
        servidor.verify();
    }

    @Test
    @DisplayName("blue es USD:blue y oficial_euro es EUR:oficial, con decimales exactos")
    void leeBlueYEuro() throws IOException {
        latestOk();
        List<CotizacionLeida> leidas = fuente.leer();

        assertThat(de(leidas, "USD", "blue").venta()).isEqualTo(new BigDecimal("1198.5"));
        assertThat(de(leidas, "USD", "blue").compra()).isEqualByComparingTo("1178");
        assertThat(de(leidas, "EUR", "oficial").compra()).isEqualByComparingTo("1090");
        assertThat(de(leidas, "EUR", "oficial").venta()).isEqualByComparingTo("1140");
    }

    @Test
    @DisplayName("no trae MEP ni los otros tipos, y blue_euro se ignora: solo oficial, blue y euro oficial")
    void soloLoQueTraeBluelytics() throws IOException {
        latestOk();

        List<CotizacionLeida> leidas = fuente.leer();

        assertThat(leidas).extracting(l -> l.moneda() + ":" + l.casa())
                .containsExactlyInAnyOrder("USD:oficial", "USD:blue", "EUR:oficial");
    }

    @Test
    @DisplayName("last_update (con su zona) se convierte al instante del mercado")
    void fechaDeActualizacion() throws IOException {
        latestOk();

        assertThat(de(fuente.leer(), "USD", "oficial").actualizadaEn())
                .isEqualTo(Instant.parse("2026-10-16T11:06:30.456789Z"));
    }

    @Test
    @DisplayName("un 5xx es un fallo de la fuente")
    void falla5xx() {
        servidor.expect(requestTo(BASE + "/v2/latest")).andRespond(withServerError());

        assertThatThrownBy(fuente::leer).isInstanceOf(FuenteNoDisponibleException.class);
    }

    @Test
    @DisplayName("un cuerpo vacio, un JSON invalido o uno que no es el esperado son un fallo de la fuente")
    void respuestasInvalidas() {
        servidor.expect(requestTo(BASE + "/v2/latest")).andRespond(withSuccess("", MediaType.APPLICATION_JSON));
        assertThatThrownBy(fuente::leer).isInstanceOf(FuenteNoDisponibleException.class);

        servidor.reset();
        servidor.expect(requestTo(BASE + "/v2/latest")).andRespond(withSuccess("{no es json", MediaType.APPLICATION_JSON));
        assertThatThrownBy(fuente::leer).isInstanceOf(FuenteNoDisponibleException.class);

        servidor.reset();
        servidor.expect(requestTo(BASE + "/v2/latest")).andRespond(withSuccess("[1,2,3]", MediaType.APPLICATION_JSON));
        assertThatThrownBy(fuente::leer).isInstanceOf(FuenteNoDisponibleException.class);
    }

    @Test
    @DisplayName("un objeto sin ningun tipo conocido da una lista vacia, no un error")
    void sinTiposConocidos() {
        servidor.expect(requestTo(BASE + "/v2/latest"))
                .andRespond(withSuccess("{\"last_update\":\"2026-10-16T08:06:30-03:00\"}", MediaType.APPLICATION_JSON));

        assertThat(fuente.leer()).isEmpty();
    }

    @Test
    @DisplayName("sin nadie escuchando en la URL configurada (127.0.0.1:9) la fuente falla sin tocar la red")
    void conexionRechazada() {
        BluelyticsFuente real = new BluelyticsFuente("http://127.0.0.1:9");

        assertThatThrownBy(real::leer).isInstanceOf(FuenteNoDisponibleException.class);
    }

    @Test
    @DisplayName("un timeout de lectura es un fallo de la fuente")
    void timeoutDeLectura() throws IOException {
        HttpServer lento = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        lento.createContext("/", intercambio -> {
            try {
                Thread.sleep(1_500);
                intercambio.sendResponseHeaders(200, -1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                intercambio.close();
            }
        });
        lento.start();
        try {
            BluelyticsFuente conTimeoutCorto = new BluelyticsFuente(
                    "http://127.0.0.1:" + lento.getAddress().getPort(), Duration.ofMillis(500), Duration.ofMillis(200));

            assertThatThrownBy(conTimeoutCorto::leer).isInstanceOf(FuenteNoDisponibleException.class);
        } finally {
            lento.stop(0);
        }
    }
}
