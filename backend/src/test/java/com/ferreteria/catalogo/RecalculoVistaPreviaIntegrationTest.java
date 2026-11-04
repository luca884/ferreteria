package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.cotizacion.CotizacionTestBase;
import com.ferreteria.cotizacion.model.LadoCotizacion;
import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Vista previa de «Recalcular ahora» (mejoras-ferreteros-bloque-1, S2): GET /api/cotizacion/recalculo/vista-previa
 * muestra como quedarian los articulos en dolares sin tocar nada, y el POST confirma con el dolar que se vio. A (CUIT
 * 30-77410001-1) y B (30-77410002-2); casa propia t41-.
 */
class RecalculoVistaPreviaIntegrationTest extends CotizacionTestBase {

    private static final String URL = "/api/cotizacion/recalculo";
    private static final String VISTA_PREVIA = URL + "/vista-previa";
    private static final Instant HACE_UN_RATO = Instant.parse("2026-10-16T11:00:00Z");

    private Long a;
    private Long b;
    private String duenoA;
    private String vendedorA;
    private String duenoB;

    @BeforeEach
    void sembrar() throws Exception {
        a = ferreteria("30-77410001-1", "Vista previa A");
        b = ferreteria("30-77410002-2", "Vista previa B");
        borrarCotizaciones("t41-");
        // Dolar de precios de A: oficial venta 1100. De B: oficial compra 950.
        cotizacion("USD", "t41-oficial", "950", "1100", HACE_UN_RATO);
        configuracion(a, c -> c.setCasaPrecios("t41-oficial"));
        configuracion(b, c -> {
            c.setCasaPrecios("t41-oficial");
            c.setLadoPrecios(LadoCotizacion.COMPRA);
        });
        duenoA = login(usuario(a, "dueno-t41a@test.local", "DUENO"));
        vendedorA = login(usuario(a, "vendedor-t41a@test.local", "VENDEDOR"));
        duenoB = login(usuario(b, "dueno-t41b@test.local", "DUENO"));
    }

    @AfterEach
    void limpiar() {
        borrarCotizaciones("t41-");
        borrarDatosDe(a, b);
    }

    private BigDecimal vigenteDe(Long ferreteriaId) {
        return jdbc.queryForObject("select vigente from configuracion_cotizacion where ferreteria_id = ?",
                BigDecimal.class, ferreteriaId);
    }

    @Test
    @DisplayName("la vista previa lista cada articulo en dolares con su precio actual, el nuevo y la diferencia, sin tocar nada")
    void vistaPreviaSinEfectos() throws Exception {
        Long usd = articuloUsd(a, "T41-USD", "10.00", "15730.00");
        Long igual = articuloUsd(a, "T41-IGUAL", "10.00", "17310.00");
        articulo(a, "T41-ARS", "ARS", "100.00", "160.00", true);

        JsonNode r = getJson(VISTA_PREVIA, duenoA);

        assertThat(r.get("dolar").decimalValue()).isEqualByComparingTo("1100");
        assertThat(r.get("vigente").isNull()).isTrue();
        assertThat(r.get("superaUmbral").asBoolean()).isFalse();
        assertThat(r.get("aviso").isNull()).isTrue();
        assertThat(r.get("articulos")).hasSize(2);
        JsonNode fila = r.get("articulos").get(0);
        assertThat(fila.get("codigoInterno").asText()).isEqualTo("T41-IGUAL");
        fila = r.get("articulos").get(1);
        assertThat(fila.get("codigoInterno").asText()).isEqualTo("T41-USD");
        assertThat(fila.get("descripcion").asText()).isEqualTo("Articulo T41-USD");
        assertThat(fila.get("precioActual").decimalValue()).isEqualByComparingTo("15730");
        assertThat(fila.get("precioNuevo").decimalValue()).isEqualByComparingTo("17310");
        assertThat(fila.get("diferencia").decimalValue()).isEqualByComparingTo("1580");
        assertThat(r.get("articulos").get(0).get("diferencia").decimalValue()).isEqualByComparingTo("0");
        assertThat(r.get("articulosQueCambian").asInt()).isEqualTo(1);

        assertThat(precioDe(usd)).isEqualByComparingTo("15730");
        assertThat(precioDe(igual)).isEqualByComparingTo("17310");
        assertThat(historialDe(usd)).isEmpty();
        assertThat(vigenteDe(a)).isNull();
    }

    @Test
    @DisplayName("si el dolar supera el umbral, la vista previa lo dice con el aviso y tampoco toca nada")
    void vistaPreviaConDesvio() throws Exception {
        configuracion(a, c -> {
            c.setCasaPrecios("t41-oficial");
            c.setUmbralDesvioPorcentaje(new BigDecimal("5"));
            c.fijarVigente(new BigDecimal("1000.0000"), "t41-oficial", LadoCotizacion.VENTA, HACE_UN_RATO);
        });
        Long usd = articuloUsd(a, "T41-USD", "10.00", "15730.00");

        JsonNode r = getJson(VISTA_PREVIA, duenoA);

        assertThat(r.get("dolar").decimalValue()).isEqualByComparingTo("1100");
        assertThat(r.get("vigente").decimalValue()).isEqualByComparingTo("1000");
        assertThat(r.get("superaUmbral").asBoolean()).isTrue();
        assertThat(r.get("aviso").asText()).startsWith("El dólar t41-oficial venta vino en $ 1.100,00, un 10 % distinto");
        assertThat(precioDe(usd)).isEqualByComparingTo("15730");
        assertThat(vigenteDe(a)).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("cada ferreteria ve solo sus articulos y su dolar en la vista previa")
    void aislamiento() throws Exception {
        articuloUsd(a, "T41-A", "10.00", "15730.00");
        articuloUsd(b, "T41-B", "10.00", "15730.00");

        JsonNode deB = getJson(VISTA_PREVIA, duenoB);

        assertThat(deB.get("dolar").decimalValue()).isEqualByComparingTo("950");
        assertThat(deB.get("articulos")).singleElement()
                .satisfies(f -> assertThat(f.get("codigoInterno").asText()).isEqualTo("T41-B"));
    }

    @Test
    @DisplayName("el Vendedor recibe 403 en la vista previa")
    void vendedorNoPuede() throws Exception {
        getResult(VISTA_PREVIA, vendedorA).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("con la cotizacion apagada la vista previa responde 409 «La cotización está apagada»")
    void apagadaDa409() throws Exception {
        configuracion(a, c -> {
            c.setCasaPrecios("t41-oficial");
            c.setActiva(false);
        });

        getResult(VISTA_PREVIA, duenoA)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("La cotización está apagada")));
    }

    @Test
    @DisplayName("confirmar con el dolar que se vio aplica ese dolar")
    void confirmaConElDolarVisto() throws Exception {
        Long usd = articuloUsd(a, "T41-USD", "10.00", "15730.00");

        postJson(URL + "?dolar=1100.0000", duenoA, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articulosRecalculados", is(1)));

        assertThat(precioDe(usd)).isEqualByComparingTo("17310");
        assertThat(vigenteDe(a)).isEqualByComparingTo("1100");
    }

    @Test
    @DisplayName("si el dolar cambio desde la vista previa, confirmar da 409 y no toca nada")
    void elDolarCambioDesdeLaVistaPrevia() throws Exception {
        Long usd = articuloUsd(a, "T41-USD", "10.00", "15730.00");

        postJson(URL + "?dolar=1090", duenoA, "{}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message",
                        is("El dólar cambió desde la vista previa ($ 1.090,00 → $ 1.100,00). Revisala de nuevo.")));

        assertThat(precioDe(usd)).isEqualByComparingTo("15730");
        assertThat(historialDe(usd)).isEmpty();
        assertThat(vigenteDe(a)).isNull();
    }
}
