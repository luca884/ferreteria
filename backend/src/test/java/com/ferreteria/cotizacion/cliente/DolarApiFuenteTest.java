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

/** DolarApi se prueba con respuestas fijas: ningun test llama a dolarapi.com. */
class DolarApiFuenteTest {

    private static final String BASE = "http://dolarapi.test";

    private final RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
    private final MockRestServiceServer servidor = MockRestServiceServer.bindTo(builder).build();
    private final DolarApiFuente fuente = new DolarApiFuente(builder.build());

    private static String json(String recurso) throws IOException {
        return new ClassPathResource("cotizacion/" + recurso).getContentAsString(StandardCharsets.UTF_8);
    }

    private void dolaresOk() throws IOException {
        servidor.expect(requestTo(BASE + "/v1/dolares")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json("dolarapi-dolares.json"), MediaType.APPLICATION_JSON));
    }

    private void cotizacionesOk() throws IOException {
        servidor.expect(requestTo(BASE + "/v1/cotizaciones")).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(json("dolarapi-cotizaciones.json"), MediaType.APPLICATION_JSON));
    }

    private static CotizacionLeida de(List<CotizacionLeida> leidas, String moneda, String casa) {
        return leidas.stream().filter(l -> l.moneda().equals(moneda) && l.casa().equals(casa)).findFirst()
                .orElseThrow(() -> new AssertionError("Falta " + moneda + ":" + casa + " en " + leidas));
    }

    @Test
    @DisplayName("se llama DOLARAPI")
    void nombre() {
        assertThat(fuente.nombre()).isEqualTo("DOLARAPI");
    }

    @Test
    @DisplayName("el oficial 950/1000 con su fecha de mercado se lee con compra, venta y actualizadaEn")
    void leeElOficial() throws IOException {
        dolaresOk();
        cotizacionesOk();

        CotizacionLeida oficial = de(fuente.leer(), "USD", "oficial");

        assertThat(oficial.compra()).isEqualByComparingTo("950");
        assertThat(oficial.venta()).isEqualByComparingTo("1000");
        assertThat(oficial.actualizadaEn()).isEqualTo(Instant.parse("2026-10-16T08:00:00Z"));
        assertThat(oficial.nombre()).isEqualTo("Oficial");
        servidor.verify();
    }

    @Test
    @DisplayName("lee los siete tipos de dolar")
    void leeTodosLosTiposDeDolar() throws IOException {
        dolaresOk();
        cotizacionesOk();

        List<CotizacionLeida> usd = fuente.leer().stream().filter(l -> l.moneda().equals("USD")).toList();

        assertThat(usd).extracting(CotizacionLeida::casa).containsExactlyInAnyOrder(
                "oficial", "blue", "bolsa", "contadoconliqui", "mayorista", "tarjeta", "cripto");
    }

    @Test
    @DisplayName("los importes se leen como BigDecimal exacto, con sus decimales")
    void importesExactos() throws IOException {
        dolaresOk();
        cotizacionesOk();
        List<CotizacionLeida> leidas = fuente.leer();

        assertThat(de(leidas, "USD", "blue").venta()).isEqualTo(new BigDecimal("1200.5"));
        assertThat(de(leidas, "USD", "contadoconliqui").venta()).isEqualTo(new BigDecimal("1070.25"));
        assertThat(de(leidas, "USD", "cripto").venta()).isEqualTo(new BigDecimal("1110.1234"));
    }

    @Test
    @DisplayName("un tipo que informa un solo lado queda con el otro lado vacio")
    void unSoloLado() throws IOException {
        dolaresOk();
        cotizacionesOk();

        CotizacionLeida tarjeta = de(fuente.leer(), "USD", "tarjeta");

        assertThat(tarjeta.compra()).isNull();
        assertThat(tarjeta.venta()).isEqualByComparingTo("1300");
    }

    @Test
    @DisplayName("/v1/cotizaciones aporta solo EUR y BRL: el USD de ahi no pisa al de /v1/dolares y el resto se ignora")
    void cotizacionesAportaSoloEuroYReal() throws IOException {
        dolaresOk();
        cotizacionesOk();

        List<CotizacionLeida> leidas = fuente.leer();

        assertThat(de(leidas, "EUR", "oficial").venta()).isEqualByComparingTo("1150.5");
        assertThat(de(leidas, "BRL", "oficial").compra()).isEqualByComparingTo("170");
        assertThat(de(leidas, "USD", "oficial").venta()).isEqualByComparingTo("1000");
        assertThat(leidas).extracting(CotizacionLeida::moneda).doesNotContain("CLP", "UYU");
        assertThat(leidas.stream().filter(l -> l.moneda().equals("USD") && l.casa().equals("oficial"))).hasSize(1);
        assertThat(leidas).hasSize(9);
    }

    @Test
    @DisplayName("si /v1/dolares anda y /v1/cotizaciones falla, devuelve igual los dolares (euro y real son solo para mostrar)")
    void falloParcialDeCotizaciones() throws IOException {
        dolaresOk();
        servidor.expect(requestTo(BASE + "/v1/cotizaciones")).andRespond(withServerError());

        List<CotizacionLeida> leidas = fuente.leer();

        assertThat(leidas).hasSize(7);
        assertThat(leidas).extracting(CotizacionLeida::moneda).containsOnly("USD");
        servidor.verify();
    }

    @Test
    @DisplayName("si /v1/cotizaciones trae basura tambien devuelve los dolares")
    void cotizacionesInvalidasSeIgnoran() throws IOException {
        dolaresOk();
        servidor.expect(requestTo(BASE + "/v1/cotizaciones")).andRespond(withSuccess("{no es json", MediaType.APPLICATION_JSON));

        assertThat(fuente.leer()).hasSize(7);
    }

    @Test
    @DisplayName("un 5xx en /v1/dolares es un fallo de la fuente")
    void falla5xx() {
        servidor.expect(requestTo(BASE + "/v1/dolares")).andRespond(withServerError());

        assertThatThrownBy(fuente::leer).isInstanceOf(FuenteNoDisponibleException.class);
    }

    @Test
    @DisplayName("un cuerpo vacio en /v1/dolares es un fallo de la fuente")
    void cuerpoVacio() {
        servidor.expect(requestTo(BASE + "/v1/dolares")).andRespond(withSuccess("", MediaType.APPLICATION_JSON));

        assertThatThrownBy(fuente::leer).isInstanceOf(FuenteNoDisponibleException.class);
    }

    @Test
    @DisplayName("un JSON invalido o que no es una lista es un fallo de la fuente")
    void jsonInvalido() {
        servidor.expect(requestTo(BASE + "/v1/dolares")).andRespond(withSuccess("{no es json", MediaType.APPLICATION_JSON));
        assertThatThrownBy(fuente::leer).isInstanceOf(FuenteNoDisponibleException.class);

        servidor.reset();
        servidor.expect(requestTo(BASE + "/v1/dolares"))
                .andRespond(withSuccess("{\"mensaje\":\"mantenimiento\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(fuente::leer).isInstanceOf(FuenteNoDisponibleException.class);
    }

    @Test
    @DisplayName("los tipos desconocidos y los renglones sin ningun importe se descartan")
    void descartaLoQueNoSirve() {
        String dolares = """
                [{"moneda":"USD","casa":"oficial","compra":950,"venta":1000,"fechaActualizacion":"2026-10-16T08:00:00.000Z"},
                 {"moneda":"USD","casa":"nuevo","compra":1,"venta":2,"fechaActualizacion":"2026-10-16T08:00:00.000Z"},
                 {"moneda":"USD","casa":"blue","compra":null,"venta":null,"fechaActualizacion":"2026-10-16T08:00:00.000Z"},
                 {"moneda":"USD","casa":"bolsa","compra":0,"venta":-5,"fechaActualizacion":"2026-10-16T08:00:00.000Z"}]""";
        servidor.expect(requestTo(BASE + "/v1/dolares")).andRespond(withSuccess(dolares, MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/v1/cotizaciones")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        List<CotizacionLeida> leidas = fuente.leer();

        assertThat(leidas).extracting(CotizacionLeida::casa).containsExactly("oficial");
    }

    @Test
    @DisplayName("un importe cero o negativo en un lado se descarta pero el otro lado se conserva")
    void importeNoPositivo() {
        String dolares = """
                [{"moneda":"USD","casa":"oficial","compra":0,"venta":1000,"fechaActualizacion":"2026-10-16T08:00:00.000Z"}]""";
        servidor.expect(requestTo(BASE + "/v1/dolares")).andRespond(withSuccess(dolares, MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/v1/cotizaciones")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        CotizacionLeida oficial = fuente.leer().get(0);

        assertThat(oficial.compra()).isNull();
        assertThat(oficial.venta()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("sin fecha de la fuente, actualizadaEn queda vacia para que la complete el orquestador")
    void sinFecha() {
        String dolares = "[{\"moneda\":\"USD\",\"casa\":\"oficial\",\"compra\":950,\"venta\":1000}]";
        servidor.expect(requestTo(BASE + "/v1/dolares")).andRespond(withSuccess(dolares, MediaType.APPLICATION_JSON));
        servidor.expect(requestTo(BASE + "/v1/cotizaciones")).andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

        assertThat(fuente.leer().get(0).actualizadaEn()).isNull();
    }

    @Test
    @DisplayName("sin nadie escuchando en la URL configurada (127.0.0.1:9) la fuente falla sin tocar la red")
    void conexionRechazada() {
        DolarApiFuente real = new DolarApiFuente("http://127.0.0.1:9");

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
            DolarApiFuente conTimeoutCorto = new DolarApiFuente(
                    "http://127.0.0.1:" + lento.getAddress().getPort(), Duration.ofMillis(500), Duration.ofMillis(200));

            assertThatThrownBy(conTimeoutCorto::leer).isInstanceOf(FuenteNoDisponibleException.class);
        } finally {
            lento.stop(0);
        }
    }
}
