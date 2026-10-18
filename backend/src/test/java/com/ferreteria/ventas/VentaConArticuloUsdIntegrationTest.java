package com.ferreteria.ventas;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * R-021 (T-050): el mostrador y las ventas no cambian con los articulos en dolares. Leen el precio final en pesos
 * guardado; no conocen la moneda ni la cotizacion. Ferreteria propia (CUIT 30-77200001-1), casa t50-oficial.
 */
class VentaConArticuloUsdIntegrationTest extends CotizacionTestBase {

    private final Instant haceUnRato = Instant.now().minusSeconds(600);

    private Long a;
    private String duenoA;
    private String vendedorA;

    @BeforeEach
    void sembrar() throws Exception {
        a = ferreteria("30-77200001-1", "Ventas en dolares A");
        borrarCotizaciones("t50-");
        borrarDatosDeVentas(a);
        borrarDatosDe(a);
        duenoA = login(usuario(a, "dueno-t50a@test.local", "DUENO"));
        vendedorA = login(usuario(a, "vendedor-t50a@test.local", "VENDEDOR"));
        cajaAbiertaHoy(a); // CU-10: sin caja abierta no se vende
    }

    @AfterEach
    void limpiar() {
        borrarCotizaciones("t50-");
        borrarDatosDeVentas(a);
        borrarDatosDe(a);
    }

    private void borrarDatosDeVentas(Long ferreteriaId) {
        jdbc.update("delete from pago where ferreteria_id = ?", ferreteriaId);
        jdbc.update("delete from item_comprobante where ferreteria_id = ?", ferreteriaId);
        jdbc.update("delete from movimiento_stock where ferreteria_id = ?", ferreteriaId);
        jdbc.update("delete from comprobante where ferreteria_id = ?", ferreteriaId);
        jdbc.update("delete from numerador_comprobante where ferreteria_id = ?", ferreteriaId);
    }

    /** Un articulo en dolares con stock suficiente para vender. */
    private Long articuloUsdConStock(String codigo, String costo, String precioFinal, boolean activo) {
        Long id = articulo(a, codigo, "USD", costo, precioFinal, activo);
        jdbc.update("update articulo set stock_actual = 10 where id = ?", id);
        return id;
    }

    private ResultActions vender(String token, Long articuloId, String precioMostrado) throws Exception {
        Map<String, Object> renglon = new LinkedHashMap<>();
        renglon.put("articuloId", articuloId);
        renglon.put("cantidad", new BigDecimal("1"));
        renglon.put("precioUnitario", new BigDecimal(precioMostrado));
        Map<String, Object> venta = new LinkedHashMap<>();
        venta.put("clave", UUID.randomUUID().toString());
        venta.put("renglones", List.of(renglon));
        venta.put("totalMostrado", new BigDecimal(precioMostrado));
        venta.put("formaPagoId", formaDePago(token, "EFECTIVO"));
        return postJson("/api/ventas", token, objectMapper.writeValueAsString(venta));
    }

    private JsonNode cuerpoDe(ResultActions resultado) throws Exception {
        return objectMapper.readTree(resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private static List<String> campos(JsonNode objeto) {
        List<String> nombres = new ArrayList<>();
        objeto.fieldNames().forEachRemaining(nombres::add);
        return nombres;
    }

    private Map<String, Object> itemDeLaVenta() {
        return jdbc.queryForMap("select * from item_comprobante where ferreteria_id = ?", a);
    }

    @Test
    @DisplayName("R-021: un articulo USD en 15.730 con el dolar luego en 1.100 sin recalculo se vende a 15.730 y queda guardado asi")
    void seVendeAlPrecioGuardadoAunqueElDolarHayaSubido() throws Exception {
        cotizacion("USD", "t50-oficial", "1090", "1100", haceUnRato);
        configuracion(a, c -> {
            c.setCasaPrecios("t50-oficial");
            c.fijarVigente(new BigDecimal("1000.0000"), "t50-oficial", LadoCotizacion.VENTA, haceUnRato);
        });
        Long usd = articuloUsdConStock("T50-DOL", "10.00", "15730.00", true);

        JsonNode venta = cuerpoDe(vender(vendedorA, usd, "15730.00").andExpect(status().isCreated()));

        assertThat(venta.get("total").decimalValue()).isEqualByComparingTo("15730.00");
        assertThat(precioDe(usd)).isEqualByComparingTo("15730");
        assertThat(jdbc.queryForObject("select total from comprobante where ferreteria_id = ?", BigDecimal.class, a))
                .isEqualByComparingTo("15730.00");
        Map<String, Object> item = itemDeLaVenta();
        assertThat((BigDecimal) item.get("precio_unitario")).isEqualByComparingTo("15730.00");
        assertThat((BigDecimal) item.get("total")).isEqualByComparingTo("15730.00");
    }

    @Test
    @DisplayName("R-021: la venta no expone la moneda ni la cotizacion, ni tampoco el item guardado")
    void laVentaNoExponeMonedaNiCotizacion() throws Exception {
        cotizacion("USD", "t50-oficial", "1090", "1100", haceUnRato);
        configuracion(a, c -> c.setCasaPrecios("t50-oficial"));
        Long usd = articuloUsdConStock("T50-DOL", "10.00", "15730.00", true);

        JsonNode venta = cuerpoDe(vender(vendedorA, usd, "15730.00").andExpect(status().isCreated()));

        assertThat(campos(venta)).doesNotContain("moneda", "cotizacion");
        assertThat(campos(venta.get("renglones").get(0))).doesNotContain("moneda", "cotizacion", "costo");
        assertThat(itemDeLaVenta().keySet()).doesNotContain("moneda", "cotizacion");
    }

    @Test
    @DisplayName("R-021: el mostrador devuelve el articulo USD solo con su precio en pesos, sin moneda, costo ni cotizacion")
    void elMostradorNoExponeMonedaNiCotizacion() throws Exception {
        cotizacion("USD", "t50-oficial", "1090", "1100", haceUnRato);
        configuracion(a, c -> c.setCasaPrecios("t50-oficial"));
        articuloUsdConStock("T50-DOL", "10.00", "15730.00", true);

        String texto = mvc.perform(get("/api/articulos/mostrador").param("q", "T50-DOL")
                        .header("Authorization", "Bearer " + vendedorA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].precioFinal", is(15730.0)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode articulo = objectMapper.readTree(texto).get("items").get(0);
        assertThat(campos(articulo)).doesNotContain("moneda", "cotizacion", "costo", "margenPorcentaje");
        assertThat(texto).doesNotContain("moneda").doesNotContain("cotizacion").doesNotContain("USD");
    }

    @Test
    @DisplayName("R-032: un USD inactivo recalculado a 17.310 se vende a ese precio al reactivarse; al viejo lo frena PRECIOS_CAMBIADOS")
    void unUsdInactivoRecalculadoSeVendeAlPrecioNuevoAlReactivarse() throws Exception {
        cotizacion("USD", "t50-oficial", "1090", "1100", haceUnRato);
        configuracion(a, c -> c.setCasaPrecios("t50-oficial"));
        Long usd = articuloUsdConStock("T50-DOL", "10.00", "15730.00", false);

        postJson("/api/cotizacion/recalculo", duenoA, "{}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.articulosRecalculados", is(1)));
        assertThat(precioDe(usd)).isEqualByComparingTo("17310");

        postJson("/api/articulos/" + usd + "/reactivacion", duenoA, "{}").andExpect(status().isOk());

        JsonNode rechazo = cuerpoDe(vender(vendedorA, usd, "15730.00").andExpect(status().isConflict()));
        assertThat(rechazo.get("motivo").asText()).isEqualTo("PRECIOS_CAMBIADOS");
        assertThat(rechazo.get("articulos").get(0).get("precioActual").decimalValue()).isEqualByComparingTo("17310");

        vender(vendedorA, usd, "17310.00").andExpect(status().isCreated());
        assertThat((BigDecimal) itemDeLaVenta().get("precio_unitario")).isEqualByComparingTo("17310.00");
        assertThat(jdbc.queryForObject("select total from comprobante where ferreteria_id = ?", BigDecimal.class, a))
                .isEqualByComparingTo("17310.00");
    }
}
