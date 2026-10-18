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

/** CU-07 recargo de la forma de pago (GDS-11, RC-003 a RC-007): {@code POST /api/ventas} contra MySQL real. */
class VentaRecargoIntegrationTest extends VentasTestBase {

    /** El Dueno le pone ese recargo a la forma de siempre de ese medio, por la API. */
    private long conRecargo(Tienda t, String medio, String nombre, String porcentaje) throws Exception {
        long forma = formaDe(t, medio);
        putJson("/api/formas-pago/" + forma, login(t.emailDueno()),
                "{\"nombre\": \"" + nombre + "\", \"medio\": \"" + medio + "\", \"recargoPorcentaje\": " + porcentaje + "}")
                .andExpect(status().isOk());
        return forma;
    }

    private String unaForma(String clave, String total, long forma, Linea... lineas) throws Exception {
        Map<String, Object> cuerpo = mapa(clave, null, total, lineas);
        cuerpo.put("formaPagoId", forma);
        return objectMapper.writeValueAsString(cuerpo);
    }

    private String mixto(String clave, String total, Linea linea, Map<Long, String> partes) throws Exception {
        Map<String, Object> cuerpo = mapa(clave, null, total, linea);
        cuerpo.remove("formaPagoId");
        List<Map<String, Object>> pagos = new ArrayList<>();
        partes.forEach((forma, monto) -> {
            Map<String, Object> pago = new LinkedHashMap<>();
            pago.put("formaPagoId", forma);
            pago.put("monto", new BigDecimal(monto));
            pagos.add(pago);
        });
        cuerpo.put("pagos", pagos);
        return objectMapper.writeValueAsString(cuerpo);
    }

    @Test
    @DisplayName("con una forma del 10 %: el total suma el recargo y la respuesta y la base lo muestran")
    void unaFormaConRecargo() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long credito = conRecargo(t, "CREDITO", "Crédito", "10");

        JsonNode venta = json(postJson("/api/ventas", login(t.emailVendedor()),
                unaForma(clave(), "330.00", credito, linea(a, "3", "100.00"))).andExpect(status().isCreated()));

        assertThat(venta.get("total").decimalValue()).isEqualByComparingTo("330.00");
        assertThat(venta.get("subtotal").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(venta.get("descuento").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(venta.get("recargo").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(venta.get("formaPagoId").asLong()).isEqualTo(credito);
        assertThat(venta.get("medioPago").asText()).isEqualTo("CREDITO");
        JsonNode pago = venta.get("pagos").get(0);
        assertThat(pago.get("formaPagoId").asLong()).isEqualTo(credito);
        assertThat(pago.get("formaPago").asText()).isEqualTo("Crédito");
        assertThat(pago.get("medio").asText()).isEqualTo("CREDITO");
        assertThat(pago.get("recargoPorcentaje").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(pago.get("recargo").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(pago.get("monto").decimalValue()).isEqualByComparingTo("330.00");
        JsonNode renglon = venta.get("renglones").get(0);
        assertThat(renglon.get("bruto").decimalValue()).isEqualByComparingTo("300.00");
        assertThat(renglon.get("recargo").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(renglon.get("total").decimalValue()).isEqualByComparingTo("330.00");

        long id = venta.get("ventaId").asLong();
        Map<String, Object> comprobante = jdbc.queryForMap("select total, recargo, subtotal from comprobante where id = ?", id);
        assertThat((BigDecimal) comprobante.get("total")).isEqualByComparingTo("330.00");
        assertThat((BigDecimal) comprobante.get("recargo")).isEqualByComparingTo("30.00");
        assertThat((BigDecimal) comprobante.get("subtotal")).isEqualByComparingTo("300.00");
        Map<String, Object> guardado = jdbc.queryForMap("select * from pago where comprobante_id = ?", id);
        assertThat((BigDecimal) guardado.get("monto")).isEqualByComparingTo("330.00");
        assertThat((BigDecimal) guardado.get("recargo")).isEqualByComparingTo("30.00");
        assertThat((BigDecimal) guardado.get("recargo_porcentaje")).isEqualByComparingTo("10.00");
        assertThat(guardado.get("forma_pago_nombre")).isEqualTo("Crédito");
        assertThat(jdbc.queryForObject("select recargo from item_comprobante where comprobante_id = ?", BigDecimal.class,
                id)).isEqualByComparingTo("30.00");
    }

    @Test
    @DisplayName("el total sin el recargo da 409 PRECIOS_CAMBIADOS con el total que lo incluye, y no guarda nada")
    void totalSinRecargoDa409() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long credito = conRecargo(t, "CREDITO", "Crédito", "10");

        JsonNode rechazo = json(postJson("/api/ventas", login(t.emailVendedor()),
                unaForma(clave(), "300.00", credito, linea(a, "3", "100.00"))).andExpect(status().isConflict()));

        assertThat(rechazo.get("motivo").asText()).isEqualTo("PRECIOS_CAMBIADOS");
        assertThat(rechazo.get("total").decimalValue()).isEqualByComparingTo("330.00");
        assertThat(comprobantes(t)).isZero();
        assertThat(stock(a)).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("en el pago mixto el recargo va solo sobre la parte de la forma que lo tiene, y la caja suma lo cobrado por medio")
    void pagoMixtoConRecargoEnUnaParte() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long credito = conRecargo(t, "CREDITO", "Crédito", "10");
        long efectivo = formaDe(t, "EFECTIVO");
        Map<Long, String> partes = new LinkedHashMap<>();
        partes.put(efectivo, "100.00");
        partes.put(credito, "200.00");

        JsonNode venta = json(postJson("/api/ventas", login(t.emailVendedor()),
                mixto(clave(), "320.00", linea(a, "3", "100.00"), partes)).andExpect(status().isCreated()));

        assertThat(venta.get("recargo").decimalValue()).isEqualByComparingTo("20.00");
        assertThat(venta.get("total").decimalValue()).isEqualByComparingTo("320.00");
        assertThat(venta.has("formaPagoId")).isFalse();
        assertThat(venta.get("pagos").get(0).get("monto").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(venta.get("pagos").get(0).get("recargo").decimalValue()).isEqualByComparingTo("0.00");
        assertThat(venta.get("pagos").get(1).get("monto").decimalValue()).isEqualByComparingTo("220.00");
        assertThat(venta.get("pagos").get(1).get("recargo").decimalValue()).isEqualByComparingTo("20.00");
        String dueno = login(t.emailDueno());
        long caja = getJson("/api/cajas/actual", dueno).get("caja").get("id").asLong();
        JsonNode detalle = getJson("/api/cajas/" + caja, dueno);
        assertThat(detalle.get("ventas").get("EFECTIVO").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(detalle.get("ventas").get("CREDITO").decimalValue()).isEqualByComparingTo("220.00");
        assertThat(detalle.get("totalVentas").decimalValue()).isEqualByComparingTo("320.00");
    }

    @Test
    @DisplayName("las partes que no suman el total sin recargo dan 400 en pagos")
    void partesQueNoSuman() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long credito = conRecargo(t, "CREDITO", "Crédito", "10");
        Map<Long, String> partes = new LinkedHashMap<>();
        partes.put(formaDe(t, "EFECTIVO"), "100.00");
        partes.put(credito, "150.00");

        JsonNode error = json(postJson("/api/ventas", login(t.emailVendedor()),
                mixto(clave(), "315.00", linea(a, "3", "100.00"), partes)).andExpect(status().isBadRequest()));

        assertThat(error.get("errors").get("pagos").asText()).isEqualTo("Los pagos tienen que sumar el total de la venta.");
        assertThat(comprobantes(t)).isZero();
    }

    @Test
    @DisplayName("el recargo se calcula por renglon con su alicuota: neto e IVA salen del total de cada uno")
    void recargoConAlicuotas() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Pintura", "UNIDAD", "80.00", "10", "IVA_10_5", true);
        long b = articulo(t, "Tornillo", "UNIDAD", "100.00", "10", "IVA_21", true);
        long credito = conRecargo(t, "CREDITO", "Crédito", "10");

        JsonNode venta = json(postJson("/api/ventas", login(t.emailVendedor()),
                unaForma(clave(), "198.00", credito, linea(a, "1", "80.00"), linea(b, "1", "100.00")))
                .andExpect(status().isCreated()));

        JsonNode pintura = venta.get("renglones").get(0);
        JsonNode tornillo = venta.get("renglones").get(1);
        assertThat(pintura.get("recargo").decimalValue()).isEqualByComparingTo("8.00");
        assertThat(pintura.get("total").decimalValue()).isEqualByComparingTo("88.00");
        assertThat(pintura.get("neto").decimalValue()).isEqualByComparingTo("79.64");
        assertThat(pintura.get("iva").decimalValue()).isEqualByComparingTo("8.36");
        assertThat(tornillo.get("recargo").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(tornillo.get("neto").decimalValue()).isEqualByComparingTo("90.91");
        assertThat(tornillo.get("iva").decimalValue()).isEqualByComparingTo("19.09");
        Map<String, Object> comprobante = jdbc.queryForMap("select neto, iva, total from comprobante where id = ?",
                venta.get("ventaId").asLong());
        assertThat((BigDecimal) comprobante.get("neto")).isEqualByComparingTo("170.55");
        assertThat((BigDecimal) comprobante.get("iva")).isEqualByComparingTo("27.45");
        assertThat((BigDecimal) comprobante.get("total")).isEqualByComparingTo("198.00");
    }

    @Test
    @DisplayName("con un descuento: el recargo va despues, sobre el total ya descontado")
    void recargoDespuesDelDescuento() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long credito = conRecargo(t, "CREDITO", "Crédito", "10");
        Map<String, Object> cuerpo = mapa(clave(), null, "99.00", linea(a, "1", "100.00"));
        cuerpo.put("formaPagoId", credito);
        cuerpo.put("descuentoPorcentaje", new BigDecimal("10"));

        JsonNode venta = json(postJson("/api/ventas", login(t.emailVendedor()), objectMapper.writeValueAsString(cuerpo))
                .andExpect(status().isCreated()));

        assertThat(venta.get("subtotal").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(venta.get("descuento").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(venta.get("recargo").decimalValue()).isEqualByComparingTo("9.00");
        assertThat(venta.get("total").decimalValue()).isEqualByComparingTo("99.00");
    }

    @Test
    @DisplayName("cambiar el recargo despues no toca la venta ya hecha, y repetir el POST devuelve la misma venta")
    void cambiarElRecargoNoTocaLaVenta() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long credito = conRecargo(t, "CREDITO", "Crédito", "10");
        String clave = clave();
        String token = login(t.emailVendedor());
        String cuerpo = unaForma(clave, "110.00", credito, linea(a, "1", "100.00"));
        JsonNode primera = json(postJson("/api/ventas", token, cuerpo).andExpect(status().isCreated()));

        conRecargo(t, "CREDITO", "Visa 6 cuotas", "25");
        JsonNode repetida = json(postJson("/api/ventas", token, cuerpo).andExpect(status().isOk()));

        assertThat(repetida.get("ventaId")).isEqualTo(primera.get("ventaId"));
        assertThat(repetida.get("total").decimalValue()).isEqualByComparingTo("110.00");
        assertThat(repetida.get("pagos").get(0).get("formaPago").asText()).isEqualTo("Crédito");
        assertThat(repetida.get("pagos").get(0).get("recargoPorcentaje").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(comprobantes(t)).isEqualTo(1);
        assertThat(stock(a)).isEqualByComparingTo("9");
        // la venta siguiente ya usa el recargo nuevo: 100 + 25
        JsonNode otra = json(postJson("/api/ventas", token, unaForma(clave(), "125.00", credito,
                linea(a, "1", "100.00"))).andExpect(status().isCreated()));
        assertThat(otra.get("recargo").decimalValue()).isEqualByComparingTo("25.00");
    }

    @Test
    @DisplayName("repetir la clave con otra forma, o con el total sin recargo, es 422 CLAVE_CON_OTRO_CONTENIDO")
    void claveConOtroContenido() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long credito = conRecargo(t, "CREDITO", "Crédito", "10");
        String clave = clave();
        String token = login(t.emailVendedor());
        postJson("/api/ventas", token, unaForma(clave, "110.00", credito, linea(a, "1", "100.00")))
                .andExpect(status().isCreated());

        JsonNode otraForma = json(postJson("/api/ventas", token,
                unaForma(clave, "100.00", formaDe(t, "DEBITO"), linea(a, "1", "100.00")))
                .andExpect(status().isUnprocessableEntity()));
        JsonNode sinRecargo = json(postJson("/api/ventas", token,
                unaForma(clave, "100.00", credito, linea(a, "1", "100.00"))).andExpect(status().isUnprocessableEntity()));

        assertThat(otraForma.get("motivo").asText()).isEqualTo("CLAVE_CON_OTRO_CONTENIDO");
        assertThat(sinRecargo.get("motivo").asText()).isEqualTo("CLAVE_CON_OTRO_CONTENIDO");
        assertThat(comprobantes(t)).isEqualTo(1);
    }

    @Test
    @DisplayName("repetir una venta cuya forma se desactivo despues devuelve la venta original")
    void repetirConLaFormaDesactivada() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long credito = conRecargo(t, "CREDITO", "Crédito", "10");
        String clave = clave();
        String token = login(t.emailVendedor());
        String cuerpo = unaForma(clave, "110.00", credito, linea(a, "1", "100.00"));
        postJson("/api/ventas", token, cuerpo).andExpect(status().isCreated());
        putJson("/api/formas-pago/" + credito, login(t.emailDueno()),
                "{\"nombre\": \"Crédito\", \"medio\": \"CREDITO\", \"activa\": false}").andExpect(status().isOk());

        postJson("/api/ventas", token, cuerpo).andExpect(status().isOk());
    }

    @Test
    @DisplayName("una forma desactivada, inexistente o de otra ferreteria da 400 y no guarda nada")
    void formaNoValida() throws Exception {
        Tienda t = tienda();
        Tienda otra = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long inactiva = formaDe(t, "TRANSFERENCIA");
        putJson("/api/formas-pago/" + inactiva, login(t.emailDueno()),
                "{\"nombre\": \"Transferencia\", \"medio\": \"TRANSFERENCIA\", \"activa\": false}")
                .andExpect(status().isOk());
        long deOtra = formaDe(otra, "EFECTIVO");
        String token = login(t.emailVendedor());

        for (long forma : new long[] {inactiva, deOtra, 999_999_999L}) {
            JsonNode error = json(postJson("/api/ventas", token, unaForma(clave(), "100.00", forma,
                    linea(a, "1", "100.00"))).andExpect(status().isBadRequest()));
            assertThat(error.get("errors").get("formaPagoId").asText()).isEqualTo("La forma de pago no es válida.");
        }
        assertThat(comprobantes(t)).isZero();
        assertThat(stock(a)).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("un recargo que lleva el total por encima del maximo da 400 en totalMostrado")
    void recargoQueExcedeElMaximo() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Oro", "UNIDAD", "9000000000000.00", "10");
        long credito = conRecargo(t, "CREDITO", "Crédito", "200");

        JsonNode error = json(postJson("/api/ventas", login(t.emailVendedor()),
                unaForma(clave(), "9000000000000.00", credito, linea(a, "1", "9000000000000.00")))
                .andExpect(status().isBadRequest()));

        assertThat(error.get("errors").has("totalMostrado")).isTrue();
        assertThat(comprobantes(t)).isZero();
    }
}
