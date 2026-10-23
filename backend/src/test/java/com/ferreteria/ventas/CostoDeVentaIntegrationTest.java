package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reportes de ventas, S1: cada renglon vendido copia el costo del articulo al momento de vender, sin IVA y en pesos (un
 * articulo en USD se pasa con el dolar vigente de la ferreteria). Cambiar el costo despues no cambia el renglon.
 */
class CostoDeVentaIntegrationTest extends VentasTestBase {

    private void costo(long articuloId, String costo, String moneda) {
        jdbc.update("update articulo set costo = ?, moneda = ? where id = ?", new BigDecimal(costo), moneda, articuloId);
    }

    private Map<String, Object> costoDelRenglon(Tienda tienda, long articuloId) {
        return jdbc.queryForMap("select i.costo_unitario, i.costo from item_comprobante i "
                + "where i.ferreteria_id = ? and i.articulo_id = ?", tienda.id(), articuloId);
    }

    @Test
    @DisplayName("una venta en pesos guarda el costo unitario y el del renglon (unitario x cantidad)")
    void ventaEnPesos() throws Exception {
        Tienda t = tienda();
        long tornillo = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        costo(tornillo, "40.00", "ARS");

        vender(login(t.emailVendedor()), clave(), "300.00", linea(tornillo, "3", "100.00"))
                .andExpect(status().isCreated());

        Map<String, Object> renglon = costoDelRenglon(t, tornillo);
        assertThat((BigDecimal) renglon.get("costo_unitario")).isEqualByComparingTo("40.0000");
        assertThat((BigDecimal) renglon.get("costo")).isEqualByComparingTo("120.00");
    }

    @Test
    @DisplayName("un articulo en USD guarda el costo pasado a pesos con el dolar vigente, a 4 decimales")
    void articuloEnDolares() throws Exception {
        Tienda t = tienda();
        long cable = articulo(t, "Cable", "METRO", "5000.00", "10");
        costo(cable, "2.50", "USD");
        jdbc.update("insert into configuracion_cotizacion (ferreteria_id, vigente) values (?, 1234.5678)", t.id());

        vender(login(t.emailVendedor()), clave(), "7500.00", linea(cable, "1.5", "5000.00"))
                .andExpect(status().isCreated());

        Map<String, Object> renglon = costoDelRenglon(t, cable);
        assertThat((BigDecimal) renglon.get("costo_unitario")).isEqualByComparingTo("3086.4195");
        assertThat((BigDecimal) renglon.get("costo")).isEqualByComparingTo("4629.63");
    }

    @Test
    @DisplayName("cambiar el costo del articulo despues de vender no cambia el costo guardado")
    void elCostoQuedaCopiado() throws Exception {
        Tienda t = tienda();
        long tornillo = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        costo(tornillo, "40.00", "ARS");
        vender(login(t.emailVendedor()), clave(), "100.00", linea(tornillo, "1", "100.00"))
                .andExpect(status().isCreated());

        costo(tornillo, "70.00", "ARS");

        assertThat((BigDecimal) costoDelRenglon(t, tornillo).get("costo")).isEqualByComparingTo("40.00");
    }

    @Test
    @DisplayName("pasar a venta un presupuesto guarda el costo del dia de la venta, no el del presupuesto")
    void desdePresupuesto() throws Exception {
        Tienda t = tienda();
        long tornillo = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        costo(tornillo, "40.00", "ARS");
        String token = login(t.emailVendedor());
        JsonNode presupuesto = presupuesto(token, tornillo, "2", "100.00", "200.00");
        costo(tornillo, "45.00", "ARS");

        Map<String, Object> venta = mapa(clave(), null, "200.00", linea(tornillo, "2", "100.00"));
        venta.put("formaPagoId", formaDePago(token, "EFECTIVO"));
        venta.put("presupuestoId", presupuesto.get("presupuestoId").asLong());
        postJson("/api/ventas", token, objectMapper.writeValueAsString(venta)).andExpect(status().isCreated());

        Map<String, Object> renglon = costoDelRenglon(t, tornillo);
        assertThat((BigDecimal) renglon.get("costo_unitario")).isEqualByComparingTo("45.0000");
        assertThat((BigDecimal) renglon.get("costo")).isEqualByComparingTo("90.00");
    }

    @Test
    @DisplayName("pasar a venta un remito guarda el costo del dia de la venta")
    void desdeRemito() throws Exception {
        Tienda t = tienda();
        long tornillo = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        costo(tornillo, "40.00", "ARS");
        String token = login(t.emailVendedor());
        JsonNode remito = remito(token, tornillo, "1", "100.00", "100.00");
        costo(tornillo, "41.25", "ARS");

        Map<String, Object> venta = mapa(clave(), null, "100.00", linea(tornillo, "1", "100.00"));
        venta.put("formaPagoId", formaDePago(token, "EFECTIVO"));
        venta.put("remitoId", remito.get("remitoId").asLong());
        postJson("/api/ventas", token, objectMapper.writeValueAsString(venta)).andExpect(status().isCreated());

        Map<String, Object> renglon = costoDelRenglon(t, tornillo);
        assertThat((BigDecimal) renglon.get("costo_unitario")).isEqualByComparingTo("41.2500");
        assertThat((BigDecimal) renglon.get("costo")).isEqualByComparingTo("41.25");
    }

    @Test
    @DisplayName("la respuesta de la venta no muestra el costo (el Vendedor no lo ve)")
    void laRespuestaNoTraeCosto() throws Exception {
        Tienda t = tienda();
        long tornillo = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        costo(tornillo, "40.00", "ARS");

        String cuerpo = vender(login(t.emailVendedor()), clave(), "100.00", linea(tornillo, "1", "100.00"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();

        assertThat(cuerpo).doesNotContain("costo");
    }
}
