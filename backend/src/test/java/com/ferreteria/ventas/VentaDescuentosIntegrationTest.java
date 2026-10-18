package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CU-07 corte 2 (GDS-12): descuento por renglon y global en {@code POST /api/ventas}, contra MySQL real. */
class VentaDescuentosIntegrationTest extends VentasTestBase {

    /** El cuerpo normal del test base, con el descuento global y el del primer renglon. */
    private String conDescuentos(String clave, String total, String descuentoGlobal, String descuentoRenglon,
            Linea linea) throws Exception {
        Map<String, Object> cuerpo = mapa(clave, "EFECTIVO", total, linea);
        if (descuentoGlobal != null) {
            cuerpo.put("descuentoPorcentaje", new BigDecimal(descuentoGlobal));
        }
        if (descuentoRenglon != null) {
            ((Map<String, Object>) ((List<?>) cuerpo.get("renglones")).get(0))
                    .put("descuentoPorcentaje", new BigDecimal(descuentoRenglon));
        }
        return objectMapper.writeValueAsString(cuerpo);
    }

    @Test
    @DisplayName("el vendedor cobra con 10 % en el renglon y 10 % global: 2 x 100 da 162, y se guarda todo con quien vendio")
    void cobraConLosDosDescuentos() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        JsonNode venta = json(postJson("/api/ventas", login(t.emailVendedor()),
                conDescuentos(clave(), "162.00", "10.00", "10.00", linea(a, "2", "100.00")))
                .andExpect(status().isCreated()));

        assertThat(venta.get("total").decimalValue()).isEqualByComparingTo("162.00");
        assertThat(venta.get("subtotal").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(venta.get("descuento").decimalValue()).isEqualByComparingTo("38.00");
        assertThat(venta.get("descuentoPorcentaje").decimalValue()).isEqualByComparingTo("10.00");
        JsonNode renglon = venta.get("renglones").get(0);
        assertThat(renglon.get("bruto").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(renglon.get("descuentoPorcentaje").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(renglon.get("total").decimalValue()).isEqualByComparingTo("162.00");

        Map<String, Object> fila = jdbc.queryForMap("select subtotal, descuento_porcentaje, total, usuario_id, "
                + "(select usuario_id from comprobante where id = ?) as quien from comprobante where id = ?",
                venta.get("ventaId").asLong(), venta.get("ventaId").asLong());
        assertThat((BigDecimal) fila.get("subtotal")).isEqualByComparingTo("200.00");
        assertThat((BigDecimal) fila.get("descuento_porcentaje")).isEqualByComparingTo("10.00");
        assertThat(fila.get("usuario_id")).isNotNull();
        assertThat(jdbc.queryForObject("select monto from pago where comprobante_id = ?", BigDecimal.class,
                venta.get("ventaId").asLong())).isEqualByComparingTo("162.00");
    }

    @Test
    @DisplayName("el IVA sale del renglon ya descontado: neto + IVA = total en el comprobante y en el renglon")
    void ivaSobreElTotalDescontado() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        JsonNode venta = json(postJson("/api/ventas", login(t.emailVendedor()),
                conDescuentos(clave(), "90.00", null, "10.00", linea(a, "1", "100.00")))
                .andExpect(status().isCreated()));

        Map<String, Object> fila = jdbc.queryForMap("select neto, iva, total from comprobante where id = ?",
                venta.get("ventaId").asLong());
        assertThat((BigDecimal) fila.get("neto")).isEqualByComparingTo("74.38");
        assertThat((BigDecimal) fila.get("iva")).isEqualByComparingTo("15.62");
        assertThat((BigDecimal) fila.get("total")).isEqualByComparingTo("90.00");
    }

    @Test
    @DisplayName("el total mostrado sin el descuento da 409 con el total actual y no guarda nada")
    void totalMostradoSinDescuentoDa409() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        JsonNode cuerpo = json(postJson("/api/ventas", login(t.emailVendedor()),
                conDescuentos(clave(), "200.00", "10.00", "10.00", linea(a, "2", "100.00")))
                .andExpect(status().isConflict()));

        assertThat(cuerpo.get("motivo").asText()).isEqualTo("PRECIOS_CAMBIADOS");
        assertThat(cuerpo.get("total").decimalValue()).isEqualByComparingTo("162.00");
        assertThat(comprobantes(t)).isZero();
        assertThat(stock(a)).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("un descuento de 100 % o negativo da 400 en su campo y no guarda nada")
    void descuentoInvalidoDa400() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        JsonNode delRenglon = json(postJson("/api/ventas", login(t.emailVendedor()),
                conDescuentos(clave(), "0.00", null, "100.00", linea(a, "1", "100.00")))
                .andExpect(status().isBadRequest()));
        JsonNode global = json(postJson("/api/ventas", login(t.emailVendedor()),
                conDescuentos(clave(), "100.00", "-5.00", null, linea(a, "1", "100.00")))
                .andExpect(status().isBadRequest()));

        assertThat(delRenglon.get("errors").get("renglones[0].descuentoPorcentaje").asText())
                .isEqualTo("El descuento tiene que estar entre 0 y 99,99 %.");
        assertThat(global.get("errors").get("descuentoPorcentaje").asText())
                .isEqualTo("El descuento tiene que estar entre 0 y 99,99 %.");
        assertThat(comprobantes(t)).isZero();
    }

    @Test
    @DisplayName("un descuento con tres decimales da 400: la base guarda hasta dos")
    void descuentoConTresDecimalesDa400() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        JsonNode cuerpo = json(postJson("/api/ventas", login(t.emailVendedor()),
                conDescuentos(clave(), "90.00", "10.005", null, linea(a, "1", "100.00")))
                .andExpect(status().isBadRequest()));

        assertThat(cuerpo.get("errors").get("descuentoPorcentaje").asText())
                .isEqualTo("El descuento admite hasta 2 decimales.");
    }

    @Test
    @DisplayName("repetir el POST con los mismos descuentos devuelve la misma venta (200); con otro descuento, 422")
    void idempotenciaConDescuentos() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String clave = clave();
        String token = login(t.emailVendedor());

        JsonNode primera = json(postJson("/api/ventas", token,
                conDescuentos(clave, "162.00", "10.00", "10.00", linea(a, "2", "100.00")))
                .andExpect(status().isCreated()));
        JsonNode repetida = json(postJson("/api/ventas", token,
                conDescuentos(clave, "162.00", "10.00", "10.00", linea(a, "2", "100.00")))
                .andExpect(status().isOk()));
        JsonNode otra = json(postJson("/api/ventas", token,
                conDescuentos(clave, "162.00", "5.00", "10.00", linea(a, "2", "100.00")))
                .andExpect(status().isUnprocessableEntity()));

        assertThat(repetida.get("ventaId")).isEqualTo(primera.get("ventaId"));
        assertThat(repetida.get("subtotal").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(otra.get("motivo").asText()).isEqualTo("CLAVE_CON_OTRO_CONTENIDO");
        assertThat(comprobantes(t)).isEqualTo(1);
        assertThat(stock(a)).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("sin descuentos en el cuerpo la venta es la de siempre: subtotal = total y porcentajes en 0")
    void sinDescuentosEnElCuerpo() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        JsonNode venta = json(vender(login(t.emailVendedor()), clave(), "300.00", linea(a, "3", "100.00"))
                .andExpect(status().isCreated()));

        assertThat(venta.get("subtotal").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(venta.get("descuento").decimalValue()).isEqualByComparingTo("0");
        assertThat(venta.get("descuentoPorcentaje").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("la caja suma el cobro ya descontado en el medio de pago")
    void laCajaSumaLoCobrado() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        postJson("/api/ventas", login(t.emailVendedor()),
                conDescuentos(clave(), "162.00", "10.00", "10.00", linea(a, "2", "100.00")))
                .andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("select coalesce(sum(p.monto), 0) from pago p join comprobante c "
                + "on c.id = p.comprobante_id where c.ferreteria_id = ? and p.medio = 'EFECTIVO'",
                BigDecimal.class, t.id())).isEqualByComparingTo("162.00");
    }
}
