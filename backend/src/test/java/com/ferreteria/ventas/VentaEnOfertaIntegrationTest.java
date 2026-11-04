package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.common.HoraArgentina;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Ofertas (S2 y S3): la venta, el presupuesto y el remito toman el precio de oferta vigente, y el mostrador lo muestra
 * con el precio normal al lado. La oferta se carga por jdbc: el endpoint lo prueba {@code ArticuloOfertaIntegrationTest}.
 */
class VentaEnOfertaIntegrationTest extends VentasTestBase {

    private void enOferta(long articuloId, String tipo, String valor, LocalDate hasta) {
        jdbc.update("update articulo set oferta_tipo = ?, oferta_valor = ?, oferta_hasta = ? where id = ?",
                tipo, new BigDecimal(valor), hasta, articuloId);
    }

    private BigDecimal precioDelItem(long tiendaId) {
        return jdbc.queryForObject("select i.precio_unitario from item_comprobante i "
                + "join comprobante c on c.id = i.comprobante_id where c.ferreteria_id = ?", BigDecimal.class, tiendaId);
    }

    private String codigoDe(long articuloId) {
        return jdbc.queryForObject("select codigo_interno from articulo where id = ?", String.class, articuloId);
    }

    @Test
    @DisplayName("una venta de un articulo en oferta se registra al precio de oferta")
    void ventaAlPrecioDeOferta() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Taladro en oferta", "UNIDAD", "1000.00", "10");
        enOferta(a, "PRECIO_FIJO", "800", null);

        vender(login(t.emailVendedor()), clave(), "800.00", linea(a, "1", "800.00")).andExpect(status().isCreated());

        assertThat(precioDelItem(t.id())).isEqualByComparingTo("800.00");
    }

    @Test
    @DisplayName("un ticket armado con el precio normal, con la oferta ya vigente, avisa el cambio de precio")
    void ticketConElPrecioViejo() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Taladro precio viejo", "UNIDAD", "1000.00", "10");
        enOferta(a, "DESCUENTO_PORCENTAJE", "10", null);

        JsonNode rechazo = json(vender(login(t.emailVendedor()), clave(), "1000.00", linea(a, "1", "1000.00"))
                .andExpect(status().isConflict()));

        assertThat(rechazo.get("motivo").asText()).isEqualTo("PRECIOS_CAMBIADOS");
        assertThat(rechazo.get("articulos").get(0).get("precioActual").decimalValue()).isEqualByComparingTo("900");
    }

    @Test
    @DisplayName("con la oferta vencida se vende al precio normal")
    void ofertaVencida() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Taladro oferta vencida", "UNIDAD", "1000.00", "10");
        enOferta(a, "PRECIO_FIJO", "800", HoraArgentina.hoy().minusDays(1));

        vender(login(t.emailVendedor()), clave(), "1000.00", linea(a, "1", "1000.00")).andExpect(status().isCreated());

        assertThat(precioDelItem(t.id())).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("el presupuesto y el remito tambien van al precio de oferta")
    void presupuestoYRemito() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Taladro presupuesto", "UNIDAD", "1000.00", "10");
        enOferta(a, "DESCUENTO_MONTO", "150", null);
        String token = login(t.emailDueno());

        JsonNode presupuesto = presupuesto(token, a, "2", "850.00", "1700.00");
        JsonNode remito = remito(token, a, "1", "850.00", "850.00");

        assertThat(presupuesto.get("total").decimalValue()).isEqualByComparingTo("1700.00");
        assertThat(remito.get("total").decimalValue()).isEqualByComparingTo("850.00");
    }

    @Test
    @DisplayName("el mostrador muestra el precio de oferta como precio, con el normal al lado y la marca")
    void mostrador() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Taladro mostrador", "UNIDAD", "1000.00", "10");
        long normal = articulo(t, "Taladro sin oferta", "UNIDAD", "500.00", "10");
        enOferta(a, "PRECIO_FIJO", "800", null);
        String token = login(t.emailVendedor());

        JsonNode enOferta = getJson("/api/articulos/mostrador?q=" + codigoDe(a), token).get("items").get(0);
        JsonNode sinOferta = getJson("/api/articulos/mostrador?q=" + codigoDe(normal), token).get("items").get(0);

        assertThat(enOferta.get("precioFinal").decimalValue()).isEqualByComparingTo("800");
        assertThat(enOferta.get("precioNormal").decimalValue()).isEqualByComparingTo("1000");
        assertThat(enOferta.get("enOferta").asBoolean()).isTrue();
        assertThat(sinOferta.get("precioFinal").decimalValue()).isEqualByComparingTo("500");
        assertThat(sinOferta.get("precioNormal").isNull()).isTrue();
        assertThat(sinOferta.get("enOferta").asBoolean()).isFalse();
    }
}
