package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.cotizacion.CotizacionTestBase;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

/**
 * POST /api/cotizacion/recalculo («Recalcular ahora») por HTTP contra MySQL (T-029 y T-030, aislamiento). A (CUIT
 * 30-77040001-1) y B (30-77040002-2) tienen la cotizacion configurada distinto; casa propia t29-.
 */
class RecalculoCotizacionIntegrationTest extends CotizacionTestBase {

    private static final String URL = "/api/cotizacion/recalculo";
    private static final Instant HACE_UN_RATO = Instant.parse("2026-10-16T11:00:00Z");

    private Long a;
    private Long b;
    private String duenoA;
    private String vendedorA;
    private String duenoB;

    @BeforeEach
    void sembrar() throws Exception {
        a = ferreteria("30-77040001-1", "Recalculo HTTP A");
        b = ferreteria("30-77040002-2", "Recalculo HTTP B");
        borrarCotizaciones("t29-");
        // Dolar de precios de A: oficial venta 1100. De B: oficial compra 950.
        cotizacion("USD", "t29-oficial", "950", "1100", HACE_UN_RATO);
        configuracion(a, c -> c.setCasaPrecios("t29-oficial"));
        configuracion(b, c -> {
            c.setCasaPrecios("t29-oficial");
            c.setLadoPrecios(com.ferreteria.cotizacion.model.LadoCotizacion.COMPRA);
        });
        duenoA = login(usuario(a, "dueno-t29a@test.local", "DUENO"));
        vendedorA = login(usuario(a, "vendedor-t29a@test.local", "VENDEDOR"));
        duenoB = login(usuario(b, "dueno-t29b@test.local", "DUENO"));
    }

    @AfterEach
    void limpiar() {
        borrarCotizaciones("t29-");
        borrarDatosDe(a, b);
    }

    private JsonNode recalcular(String token) throws Exception {
        String cuerpo = postJson(URL, token, "{}")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(cuerpo);
    }

    // ---- T-029 ----

    @Test
    @DisplayName("el Dueno recibe 200 con {cotizacion, articulosRecalculados}; el articulo de 10 USD pasa a 17.310")
    void duenoRecalcula() throws Exception {
        Long usd = articuloUsd(a, "T29-USD", "10.00", "15730.00");

        postJson(URL, duenoA, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cotizacion", is(1100.0)))
                .andExpect(jsonPath("$.articulosRecalculados", is(1)));

        assertThat(precioDe(usd)).isEqualByComparingTo("17310");
        assertThat(historialDe(usd)).singleElement().satisfies(h -> {
            assertThat(h.get("origen")).isEqualTo("COTIZACION");
            assertThat(h.get("usuario_id")).isEqualTo(usuarioId("dueno-t29a@test.local"));
        });
    }

    @Test
    @DisplayName("tambien se puede pedir sin cuerpo, y con el dolar sin cambio la cantidad es 0")
    void sinCuerpoYSinCambio() throws Exception {
        articuloUsd(a, "T29-USD", "10.00", "17310.00");

        String cuerpo = mvc.perform(post(URL).header("Authorization", "Bearer " + duenoA))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(objectMapper.readTree(cuerpo).get("articulosRecalculados").asInt()).isZero();
    }

    @Test
    @DisplayName("el Vendedor recibe 403 y ningun precio cambia")
    void vendedorNoPuede() throws Exception {
        Long usd = articuloUsd(a, "T29-USD", "10.00", "15730.00");

        postJson(URL, vendedorA, "{}").andExpect(status().isForbidden());

        assertThat(precioDe(usd)).isEqualByComparingTo("15730");
        assertThat(historialDe(usd)).isEmpty();
    }

    @Test
    @DisplayName("sin token recibe 401")
    void sinToken() throws Exception {
        mvc.perform(post(URL).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("con la cotizacion apagada responde 409 «La cotización está apagada» y nada cambia")
    void apagadaDa409() throws Exception {
        configuracion(a, c -> {
            c.setCasaPrecios("t29-oficial");
            c.setActiva(false);
        });
        Long usd = articuloUsd(a, "T29-USD", "10.00", "15730.00");

        postJson(URL, duenoA, "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("La cotización está apagada")));

        assertThat(precioDe(usd)).isEqualByComparingTo("15730");
        assertThat(historialDe(usd)).isEmpty();
    }

    @Test
    @DisplayName("sin ninguna cotizacion guardada responde 409 «Todavía no hay cotización del dólar. Probá en unos minutos»")
    void sinCotizacionDa409() throws Exception {
        configuracion(a, c -> c.setCasaPrecios("t29-mep"));
        Long usd = articuloUsd(a, "T29-USD", "10.00", "15730.00");

        postJson(URL, duenoA, "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("Todavía no hay cotización del dólar. Probá en unos minutos")));

        assertThat(precioDe(usd)).isEqualByComparingTo("15730");
    }

    @Test
    @DisplayName("no hay forma de frenarlo por tickets (R-034): el pedido no admite ningun parametro y responde 200")
    void ningunParametroLoFrena() throws Exception {
        articuloUsd(a, "T29-USD", "10.00", "15730.00");

        postJson(URL + "?ticketsAbiertos=3&forzar=false", duenoA, "{\"ticketsAbiertos\":3}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articulosRecalculados", is(1)));
    }

    // ---- T-030: aislamiento ----

    @Test
    @DisplayName("aislamiento: el Dueno de A recalcula y los articulos de B (uno inactivo) no cambian ni tienen historial")
    void elRecalculoDeANoTocaB() throws Exception {
        Long usdA = articuloUsd(a, "T30-A", "10.00", "15730.00");
        Long usdB = articuloUsd(b, "T30-B", "10.00", "15730.00");
        Long inactivoB = articulo(b, "T30-B-INACTIVO", "USD", "10.00", "15730.00", false);

        JsonNode respuesta = recalcular(duenoA);

        assertThat(respuesta.get("articulosRecalculados").asInt()).isEqualTo(1);
        assertThat(precioDe(usdA)).isEqualByComparingTo("17310");
        assertThat(precioDe(usdB)).isEqualByComparingTo("15730");
        assertThat(precioDe(inactivoB)).isEqualByComparingTo("15730");
        assertThat(historialDe(usdB)).isEmpty();
        assertThat(historialDe(inactivoB)).isEmpty();
        assertThat(historialDeLaFerreteria(b)).isZero();
        assertThat(configuracionDe(b).getVigente()).isNull();
    }

    @Test
    @DisplayName("aislamiento: cada Dueno recalcula con SU dolar: A con venta 1100 (17.310) y B con compra 950 (14.950)")
    void cadaDuenoConSuDolar() throws Exception {
        Long usdA = articuloUsd(a, "T30-A", "10.00", "15730.00");
        Long usdB = articuloUsd(b, "T30-B", "10.00", "15730.00");

        JsonNode deA = recalcular(duenoA);
        JsonNode deB = recalcular(duenoB);

        assertThat(deA.get("cotizacion").decimalValue()).isEqualByComparingTo(new BigDecimal("1100"));
        assertThat(deB.get("cotizacion").decimalValue()).isEqualByComparingTo(new BigDecimal("950"));
        assertThat(precioDe(usdA)).isEqualByComparingTo("17310");
        assertThat(precioDe(usdB)).isEqualByComparingTo("14950");
        assertThat(historialDe(usdA)).singleElement()
                .satisfies(h -> assertThat(h.get("usuario_id")).isEqualTo(usuarioId("dueno-t29a@test.local")));
        assertThat(historialDe(usdB)).singleElement()
                .satisfies(h -> assertThat(h.get("usuario_id")).isEqualTo(usuarioId("dueno-t29b@test.local")));
    }

    @Test
    @DisplayName("aislamiento: un ferreteriaId por parametro o por body se ignora; la ferreteria sale solo del token")
    void laFerreteriaSaleDelToken() throws Exception {
        Long usdA = articuloUsd(a, "T30-A", "10.00", "15730.00");
        Long usdB = articuloUsd(b, "T30-B", "10.00", "15730.00");

        postJson(URL + "?ferreteriaId=" + b, duenoA, "{\"ferreteriaId\":" + b + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articulosRecalculados", is(1)));
        mvc.perform(post(URL).header("Authorization", "Bearer " + duenoA).header("X-Ferreteria-Id", b.toString()))
                .andExpect(status().isOk());

        assertThat(precioDe(usdA)).isEqualByComparingTo("17310");
        assertThat(precioDe(usdB)).isEqualByComparingTo("15730");
        assertThat(historialDeLaFerreteria(b)).isZero();
    }
}
