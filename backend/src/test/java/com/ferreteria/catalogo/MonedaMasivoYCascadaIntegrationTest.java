package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.cotizacion.CotizacionTestBase;
import com.ferreteria.cotizacion.model.LadoCotizacion;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El ajuste masivo (CU-05) y la cascada de rubro (CU-03) con articulos en USD, por HTTP contra MySQL (T-036): los dos usan
 * el dolar VIGENTE de la ferreteria (1000), no el valor de la API (1100). Ferreteria propia (CUIT 30-77110001-1) y casa
 * propia (t36-).
 */
class MonedaMasivoYCascadaIntegrationTest extends CotizacionTestBase {

    private static final Instant HACE_UN_RATO = Instant.parse("2026-10-16T11:00:00Z");

    private Long a;
    private Long rubro;
    private String dueno;

    @BeforeEach
    void sembrar() throws Exception {
        a = ferreteria("30-77110001-1", "Masivo y cascada A");
        borrarCotizaciones("t36-");
        cotizacion("USD", "t36-oficial", "1090", "1100", HACE_UN_RATO);
        configuracion(a, c -> {
            c.setCasaPrecios("t36-oficial");
            c.fijarVigente(new BigDecimal("1000.0000"), "t36-oficial", LadoCotizacion.VENTA, HACE_UN_RATO);
        });
        dueno = login(usuario(a, "dueno-t36a@test.local", "DUENO"));
        jdbc.update("insert into rubro (ferreteria_id, nombre, margen_porcentaje) values (?, 'Rubro t36', 35.00)", a);
        rubro = jdbc.queryForObject("select id from rubro where ferreteria_id = ?", Long.class, a);
    }

    @AfterEach
    void limpiar() {
        borrarCotizaciones("t36-");
        borrarDatosDe(a);
    }

    /** Articulo del rubro sin margen propio, ya calculado con el margen de 35 y el dolar en 1000 (USD) o sin dolar (ARS). */
    private Long delRubro(String codigo, String moneda, String costo, String precio, boolean activo) {
        Long id = articulo(a, codigo, moneda, costo, precio, activo);
        jdbc.update("update articulo set rubro_id = ? where id = ?", rubro, id);
        return id;
    }

    private JsonNode cuerpo(org.springframework.test.web.servlet.ResultActions resultado) throws Exception {
        return objectMapper.readTree(resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    // ---- cascada de rubro ----

    @Test
    @DisplayName("cascada: rubro de 35 a 50 % recalcula los USD ACTIVOS con el vigente 1000 (16.340 -> 18.150) y deja intacto el inactivo")
    void cascadaConArticulosEnDolares() throws Exception {
        Long activo = delRubro("T36-USD", "USD", "10.00", "16340.00", true);
        Long inactivo = delRubro("T36-USD-INACTIVO", "USD", "10.00", "16340.00", false);
        Long enPesos = delRubro("T36-ARS", "ARS", "100.00", "170.00", true);

        putJson("/api/rubros/" + rubro, dueno, "{\"nombre\":\"Rubro t36\",\"margenPorcentaje\":50}")
                .andExpect(status().isOk());

        // 10 x 1000 = 10000; x 1,50 = 15000; x 1,21 = 18150 (con la API en 1100 darian 19.970)
        assertThat(precioDe(activo)).isEqualByComparingTo("18150");
        assertThat(precioDe(inactivo)).isEqualByComparingTo("16340");
        assertThat(precioDe(enPesos)).isEqualByComparingTo("190");
        assertThat(historialDe(activo)).singleElement().satisfies(h -> {
            assertThat(h.get("origen")).isEqualTo("MASIVO");
            assertThat(h.get("moneda")).isEqualTo("USD");
            assertThat(h.get("usuario_id")).isEqualTo(usuarioId("dueno-t36a@test.local"));
            assertThat((BigDecimal) h.get("precio_nuevo")).isEqualByComparingTo("18150");
        });
        assertThat(historialDe(inactivo)).isEmpty();
        assertThat(historialDe(enPesos)).hasSize(1);
    }

    @Test
    @DisplayName("cascada: sin ninguna cotizacion el cambio del rubro responde 409 y se revierte todo (el margen sigue en 35)")
    void cascadaSinCotizacionRevierteElRubro() throws Exception {
        configuracion(a, c -> c.setCasaPrecios("t36-no-existe"));
        Long activo = delRubro("T36-USD", "USD", "10.00", "16340.00", true);

        putJson("/api/rubros/" + rubro, dueno, "{\"nombre\":\"Rubro t36\",\"margenPorcentaje\":50}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("Todavía no hay cotización del dólar. Probá en unos minutos")));

        assertThat(precioDe(activo)).isEqualByComparingTo("16340");
        assertThat(jdbc.queryForObject("select margen_porcentaje from rubro where id = ?", BigDecimal.class, rubro))
                .isEqualByComparingTo("35");
    }

    // ---- ajuste masivo ----

    @Test
    @DisplayName("masivo: +10 % de costo sobre un articulo de 10 USD usa el vigente 1000: costo 11 USD y precio 17.310 (con 1100 daria 19.040)")
    void masivoSobreUnArticuloEnDolares() throws Exception {
        Long usd = articuloUsd(a, "T36-USD", "10.00", "15730.00");
        Long enPesos = articulo(a, "T36-ARS", "ARS", "100.00", "160.00", true);
        String pedido = "{\"modo\":\"COSTO_PORCENTAJE\",\"valor\":10}";

        JsonNode previa = cuerpo(postJson("/api/actualizacion-masiva/vista-previa", dueno, pedido)
                .andExpect(status().isOk()));
        assertThat(previa.get("errores")).isEmpty();
        assertThat(previa.get("cambios")).hasSize(2);

        postJson("/api/actualizacion-masiva", dueno,
                "{\"modo\":\"COSTO_PORCENTAJE\",\"valor\":10,\"huella\":\"" + previa.get("huella").asText() + "\"}")
                .andExpect(status().isCreated());

        // 11 x 1000 = 11000; x 1,30 = 14300; x 1,21 = 17303 -> 17310
        assertThat(jdbc.queryForObject("select costo from articulo where id = ?", BigDecimal.class, usd))
                .isEqualByComparingTo("11.00");
        assertThat(precioDe(usd)).isEqualByComparingTo("17310");
        assertThat(precioDe(enPesos)).isEqualByComparingTo("180");
        assertThat(historialDe(usd)).singleElement().satisfies(h -> {
            assertThat(h.get("origen")).isEqualTo("MASIVO");
            assertThat(h.get("moneda")).isEqualTo("USD");
            assertThat((BigDecimal) h.get("precio_anterior")).isEqualByComparingTo("15730");
        });
    }

    @Test
    @DisplayName("masivo: sin ninguna cotizacion y con un USD en el alcance responde 409 en la vista previa y no cambia nada")
    void masivoSinCotizacionDa409() throws Exception {
        configuracion(a, c -> c.setCasaPrecios("t36-no-existe"));
        Long usd = articuloUsd(a, "T36-USD", "10.00", "15730.00");

        postJson("/api/actualizacion-masiva/vista-previa", dueno, "{\"modo\":\"COSTO_PORCENTAJE\",\"valor\":10}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("Todavía no hay cotización del dólar. Probá en unos minutos")));

        assertThat(precioDe(usd)).isEqualByComparingTo("15730");
    }
}
