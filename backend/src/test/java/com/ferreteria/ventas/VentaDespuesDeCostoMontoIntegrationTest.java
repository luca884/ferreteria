package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ferreteria.cotizacion.CotizacionTestBase;
import com.ferreteria.cotizacion.model.LadoCotizacion;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Regresion del corte 3 (T-236, R-216, R-217, R-221): despues de una actualizacion masiva por monto el mostrador y
 * las ventas siguen leyendo el precio final en pesos guardado; los modos de siempre (porcentaje y margen) dan lo de
 * antes; y el listado y el GET del articulo no se rompen con los parametros y campos nuevos.
 * CUIT 30-78409001-1.
 */
class VentaDespuesDeCostoMontoIntegrationTest extends CotizacionTestBase {

    private final Instant haceUnRato = Instant.now().minusSeconds(600);

    private Long f;
    private String dueno;
    private String vendedor;

    @BeforeEach
    void sembrar() throws Exception {
        f = ferreteria("30-78409001-1", "Regresion costo monto");
        borrarCotizaciones("t236-");
        borrarDatosDeVentas();
        borrarDatosDe(f);
        cotizacion("USD", "t236-oficial", "990", "1000", haceUnRato);
        configuracion(f, c -> {
            c.setCasaPrecios("t236-oficial");
            c.fijarVigente(new BigDecimal("1000.0000"), "t236-oficial", LadoCotizacion.VENTA, haceUnRato);
        });
        dueno = login(usuario(f, "dueno-t236@test.local", "DUENO"));
        vendedor = login(usuario(f, "vendedor-t236@test.local", "VENDEDOR"));
    }

    @AfterEach
    void limpiar() {
        borrarCotizaciones("t236-");
        borrarDatosDeVentas();
        borrarDatosDe(f);
    }

    private void borrarDatosDeVentas() {
        jdbc.update("delete from pago where ferreteria_id = ?", f);
        jdbc.update("delete from item_comprobante where ferreteria_id = ?", f);
        jdbc.update("delete from movimiento_stock where ferreteria_id = ?", f);
        jdbc.update("delete from comprobante where ferreteria_id = ?", f);
        jdbc.update("delete from numerador_comprobante where ferreteria_id = ?", f);
    }

    private Long conStock(Long articuloId) {
        jdbc.update("update articulo set stock_actual = 10 where id = ?", articuloId);
        return articuloId;
    }

    private JsonNode masivo(String modo, String valor) throws Exception {
        ObjectNode pedido = objectMapper.createObjectNode();
        pedido.put("modo", modo);
        pedido.put("valor", new BigDecimal(valor));
        JsonNode previa = objectMapper.readTree(postJson("/api/actualizacion-masiva/vista-previa", dueno,
                pedido.toString()).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        pedido.put("huella", previa.get("huella").asText());
        postJson("/api/actualizacion-masiva", dueno, pedido.toString()).andExpect(status().isCreated());
        return previa;
    }

    private ResultActions vender(Long articuloId, String precio) throws Exception {
        Map<String, Object> renglon = new LinkedHashMap<>();
        renglon.put("articuloId", articuloId);
        renglon.put("cantidad", new BigDecimal("1"));
        renglon.put("precioUnitario", new BigDecimal(precio));
        Map<String, Object> venta = new LinkedHashMap<>();
        venta.put("clave", UUID.randomUUID().toString());
        venta.put("renglones", List.of(renglon));
        venta.put("totalMostrado", new BigDecimal(precio));
        venta.put("medioPago", "EFECTIVO");
        return postJson("/api/ventas", vendedor, objectMapper.writeValueAsString(venta));
    }

    private BigDecimal costoDe(Long id) {
        return jdbc.queryForObject("select costo from articulo where id = ?", BigDecimal.class, id);
    }

    @Test
    @DisplayName("un articulo USD (15.730) se vende despues de un COSTO_MONTO +5: el item toma el precio nuevo en pesos guardado")
    void seVendeAlPrecioGuardadoTrasElMonto() throws Exception {
        Long usd = conStock(articuloUsd(f, "T236-USD", "10.00", "15730.00"));

        masivo("COSTO_MONTO", "5");

        assertThat(costoDe(usd)).isEqualByComparingTo("15.00");
        assertThat(precioDe(usd)).isEqualByComparingTo("23600");
        vender(usd, "23600.00").andExpect(status().isCreated());
        Map<String, Object> item = jdbc.queryForMap("select * from item_comprobante where ferreteria_id = ?", f);
        assertThat((BigDecimal) item.get("precio_unitario")).isEqualByComparingTo("23600.00");
        assertThat((BigDecimal) item.get("total")).isEqualByComparingTo("23600.00");
        assertThat(jdbc.queryForObject("select stock_actual from articulo where id = ?", BigDecimal.class, usd))
                .isEqualByComparingTo("9");
    }

    @Test
    @DisplayName("el precio viejo ya no se vende despues del monto, y no queda venta ni baja de stock")
    void elPrecioViejoNoPasa() throws Exception {
        Long usd = conStock(articuloUsd(f, "T236-USD", "10.00", "15730.00"));
        masivo("COSTO_MONTO", "5");

        vender(usd, "15730.00").andExpect(status().is4xxClientError());

        assertThat(jdbc.queryForObject("select count(*) from comprobante where ferreteria_id = ?", Long.class, f))
                .isZero();
        assertThat(jdbc.queryForObject("select stock_actual from articulo where id = ?", BigDecimal.class, usd))
                .isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("COSTO_PORCENTAJE y MARGEN siguen dando los mismos contadores y numeros de antes")
    void losModosDeSiempreNoCambian() throws Exception {
        Long a1 = manualConStock("T236-A1", "100.00", "160.00");
        Long a2 = manualConStock("T236-A2", "200.00", "320.00");

        JsonNode porcentaje = masivo("COSTO_PORCENTAJE", "10");

        assertThat(porcentaje.at("/conteos/total").asInt()).isEqualTo(2);
        assertThat(porcentaje.get("advertenciaMezcla").isNull()).isTrue();
        assertThat(costoDe(a1)).isEqualByComparingTo("110.00");
        assertThat(costoDe(a2)).isEqualByComparingTo("220.00");

        JsonNode margen = masivo("MARGEN", "50");

        assertThat(margen.at("/conteos/total").asInt()).isEqualTo(2);
        assertThat(margen.get("advertenciaMezcla").isNull()).isTrue();
        assertThat(jdbc.queryForObject("select margen_porcentaje from articulo where id = ?", BigDecimal.class, a1))
                .isEqualByComparingTo("50");
        assertThat(costoDe(a1)).isEqualByComparingTo("110.00");
    }

    private Long manualConStock(String codigo, String costo, String precio) {
        return conStock(articulo(f, codigo, "ARS", costo, precio, true));
    }

    @Test
    @DisplayName("GET /api/articulos ignora importacionId y el GET por id sigue trayendo los campos de siempre")
    void elListadoYElGetNoSeRompen() throws Exception {
        Long id = manualConStock("T236-LEG", "100.00", "160.00");

        JsonNode sinParametro = getJson("/api/articulos", dueno);
        JsonNode conParametro = objectMapper.readTree(mvc.perform(get("/api/articulos").param("importacionId", "999999")
                .header("Authorization", "Bearer " + dueno)).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));
        assertThat(conParametro.get("items").size()).isEqualTo(sinParametro.get("items").size()).isEqualTo(1);

        JsonNode articulo = getJson("/api/articulos/" + id, dueno);
        assertThat(articulo.fieldNames()).toIterable().contains("id", "codigoInterno", "descripcion", "costo",
                "precioFinal", "moneda", "stockActual", "activo");
        assertThat(articulo.get("costo").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(articulo.get("precioFinal").decimalValue()).isEqualByComparingTo("160.00");
    }
}
