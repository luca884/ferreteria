package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Descuento en efectivo (S2): el descuento % de la forma de pago en {@code POST /api/ventas}, contra MySQL real. Se
 * guarda como recargo negativo: subtotal - descuento + recargo = total y la caja suma lo cobrado por medio.
 */
class VentaDescuentoEfectivoIntegrationTest extends FacturaDelDuenoTestBase {

    /** El Dueno le pone ese descuento a la forma de siempre de ese medio, por la API. */
    private long conDescuento(Tienda t, String medio, String nombre, String porcentaje) throws Exception {
        long forma = formaDe(t, medio);
        putJson("/api/formas-pago/" + forma, login(t.emailDueno()),
                "{\"nombre\": \"" + nombre + "\", \"medio\": \"" + medio + "\", \"descuentoPorcentaje\": " + porcentaje
                        + "}").andExpect(status().isOk());
        return forma;
    }

    private long conRecargo(Tienda t, String medio, String nombre, String porcentaje) throws Exception {
        long forma = formaDe(t, medio);
        putJson("/api/formas-pago/" + forma, login(t.emailDueno()),
                "{\"nombre\": \"" + nombre + "\", \"medio\": \"" + medio + "\", \"recargoPorcentaje\": " + porcentaje
                        + "}").andExpect(status().isOk());
        return forma;
    }

    private String unaForma(String clave, String total, long forma, String descuentoGlobal, Linea... lineas)
            throws Exception {
        Map<String, Object> cuerpo = mapa(clave, null, total, lineas);
        cuerpo.put("formaPagoId", forma);
        if (descuentoGlobal != null) {
            cuerpo.put("descuentoPorcentaje", new BigDecimal(descuentoGlobal));
        }
        return objectMapper.writeValueAsString(cuerpo);
    }

    private JsonNode cajaActual(Tienda t) throws Exception {
        String dueno = login(t.emailDueno());
        long caja = getJson("/api/cajas/actual", dueno).get("caja").get("id").asLong();
        return getJson("/api/cajas/" + caja, dueno);
    }

    @Test
    @DisplayName("en efectivo con 10 %: $ 300 se cobran $ 270, como recargo negativo en la venta, el renglon y el pago, y la caja suma $ 270")
    void efectivoConDescuento() throws Exception {
        Tienda t = tiendaConDatosFiscales("RESPONSABLE_INSCRIPTO");
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long efectivo = conDescuento(t, "EFECTIVO", "Efectivo", "10");

        JsonNode venta = json(postJson("/api/ventas", login(t.emailVendedor()),
                unaForma(clave(), "270.00", efectivo, null, linea(a, "3", "100.00"))).andExpect(status().isCreated()));

        assertThat(venta.get("total").decimalValue()).isEqualByComparingTo("270.00");
        assertThat(venta.get("subtotal").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(venta.get("descuento").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(venta.get("recargo").decimalValue()).isEqualByComparingTo("-30.00");
        JsonNode pago = venta.get("pagos").get(0);
        assertThat(pago.get("recargoPorcentaje").decimalValue()).isEqualByComparingTo("-10.00");
        assertThat(pago.get("recargo").decimalValue()).isEqualByComparingTo("-30.00");
        assertThat(pago.get("monto").decimalValue()).isEqualByComparingTo("270.00");
        JsonNode renglon = venta.get("renglones").get(0);
        assertThat(renglon.get("recargo").decimalValue()).isEqualByComparingTo("-30.00");
        assertThat(renglon.get("total").decimalValue()).isEqualByComparingTo("270.00");

        long id = venta.get("ventaId").asLong();
        Map<String, Object> comprobante = jdbc.queryForMap("select total, recargo, subtotal from comprobante where id = ?", id);
        assertThat((BigDecimal) comprobante.get("total")).isEqualByComparingTo("270.00");
        assertThat((BigDecimal) comprobante.get("recargo")).isEqualByComparingTo("-30.00");
        Map<String, Object> guardado = jdbc.queryForMap("select * from pago where comprobante_id = ?", id);
        assertThat((BigDecimal) guardado.get("monto")).isEqualByComparingTo("270.00");
        assertThat((BigDecimal) guardado.get("recargo_porcentaje")).isEqualByComparingTo("-10.00");
        assertThat(cajaActual(t).get("ventas").get("EFECTIVO").decimalValue()).isEqualByComparingTo("270.00");
    }

    @Test
    @DisplayName("el total sin el descuento de la forma da 409 PRECIOS_CAMBIADOS con el total descontado, y no guarda nada")
    void totalSinDescuentoDa409() throws Exception {
        Tienda t = tiendaConDatosFiscales("RESPONSABLE_INSCRIPTO");
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long efectivo = conDescuento(t, "EFECTIVO", "Efectivo", "10");

        JsonNode rechazo = json(postJson("/api/ventas", login(t.emailVendedor()),
                unaForma(clave(), "300.00", efectivo, null, linea(a, "3", "100.00"))).andExpect(status().isConflict()));

        assertThat(rechazo.get("motivo").asText()).isEqualTo("PRECIOS_CAMBIADOS");
        assertThat(rechazo.get("total").decimalValue()).isEqualByComparingTo("270.00");
        assertThat(comprobantes(t)).isZero();
    }

    @Test
    @DisplayName("va despues del descuento del Vendedor: $ 300 con 10 % global quedan $ 270 y en efectivo al 10 %, $ 243")
    void despuesDelDescuentoDelVendedor() throws Exception {
        Tienda t = tiendaConDatosFiscales("RESPONSABLE_INSCRIPTO");
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long efectivo = conDescuento(t, "EFECTIVO", "Efectivo", "10");

        JsonNode venta = json(postJson("/api/ventas", login(t.emailDueno()),
                unaForma(clave(), "243.00", efectivo, "10", linea(a, "3", "100.00"))).andExpect(status().isCreated()));

        assertThat(venta.get("subtotal").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(venta.get("descuento").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(venta.get("recargo").decimalValue()).isEqualByComparingTo("-27.00");
        assertThat(venta.get("total").decimalValue()).isEqualByComparingTo("243.00");
    }

    @Test
    @DisplayName("pago mixto: el descuento va solo sobre la parte en efectivo y el recargo sobre la de credito; la caja suma lo cobrado por medio")
    void pagoMixtoConDescuentoYRecargo() throws Exception {
        Tienda t = tiendaConDatosFiscales("RESPONSABLE_INSCRIPTO");
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long efectivo = conDescuento(t, "EFECTIVO", "Efectivo", "10");
        long credito = conRecargo(t, "CREDITO", "Crédito", "10");
        Map<String, Object> cuerpo = mapa(clave(), null, "310.00", linea(a, "3", "100.00"));
        cuerpo.remove("formaPagoId");
        List<Map<String, Object>> pagos = new ArrayList<>();
        pagos.add(new LinkedHashMap<>(Map.of("formaPagoId", efectivo, "monto", new BigDecimal("100.00"))));
        pagos.add(new LinkedHashMap<>(Map.of("formaPagoId", credito, "monto", new BigDecimal("200.00"))));
        cuerpo.put("pagos", pagos);

        JsonNode venta = json(postJson("/api/ventas", login(t.emailVendedor()), objectMapper.writeValueAsString(cuerpo))
                .andExpect(status().isCreated()));

        assertThat(venta.get("recargo").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(venta.get("total").decimalValue()).isEqualByComparingTo("310.00");
        assertThat(venta.get("pagos").get(0).get("recargo").decimalValue()).isEqualByComparingTo("-10.00");
        assertThat(venta.get("pagos").get(0).get("monto").decimalValue()).isEqualByComparingTo("90.00");
        assertThat(venta.get("pagos").get(1).get("recargo").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(venta.get("pagos").get(1).get("monto").decimalValue()).isEqualByComparingTo("220.00");
        JsonNode caja = cajaActual(t);
        assertThat(caja.get("ventas").get("EFECTIVO").decimalValue()).isEqualByComparingTo("90.00");
        assertThat(caja.get("ventas").get("CREDITO").decimalValue()).isEqualByComparingTo("220.00");
    }

    @Test
    @DisplayName("la factura lleva el total descontado (B de $ 242 en efectivo al 10 %: $ 217,80) y su NC devuelve lo mismo")
    void facturaYNotaDeCredito() throws Exception {
        Tienda t = tiendaQueFactura("RESPONSABLE_INSCRIPTO");
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        conDescuento(t, "EFECTIVO", "Efectivo", "10");

        JsonNode venta = json(venderA(t, null, null, clave(), "217.80", linea(a, "2", "121.00"))
                .andExpect(status().isCreated()));

        long id = venta.get("ventaId").asLong();
        Map<String, Object> registro = registroDe(id);
        assertThat(registro).containsEntry("estado", "AUTORIZADO");
        assertThat((BigDecimal) registro.get("imp_total")).isEqualByComparingTo("217.80");
        assertThat((BigDecimal) registro.get("imp_neto")).isEqualByComparingTo("180.00");
        assertThat((BigDecimal) registro.get("imp_iva")).isEqualByComparingTo("37.80");

        JsonNode nota = json(postJson("/api/ventas/" + id + "/anular", login(t.emailDueno()), "{}")
                .andExpect(status().isCreated()));

        long notaId = nota.get("ventaId").asLong();
        assertThat(nota.get("total").decimalValue()).isEqualByComparingTo("217.80");
        assertThat(nota.get("recargo").decimalValue()).isEqualByComparingTo("-24.20");
        assertThat((BigDecimal) registroDe(notaId).get("imp_total")).isEqualByComparingTo("217.80");
        assertThat(stock(a)).isEqualByComparingTo("10");
    }
}
