package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regresiones del verificador de descuento en efectivo (probes de {@code odd-verifier}): los lectores del recargo con
 * signo (idempotencia, caja, reportes, anulacion, cuenta corriente, tope del Vendedor, ofertas y Factura A).
 */
class DescuentoEfectivoRegresionIntegrationTest extends FacturaDelDuenoTestBase {

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

    private String unaForma(String clave, String total, long forma, String global, Linea... lineas) throws Exception {
        Map<String, Object> cuerpo = mapa(clave, null, total, lineas);
        cuerpo.put("formaPagoId", forma);
        if (global != null) {
            cuerpo.put("descuentoPorcentaje", new BigDecimal(global));
        }
        return objectMapper.writeValueAsString(cuerpo);
    }

    private String mixto(String clave, String total, Long cliente, Object[][] partes, Linea... lineas) throws Exception {
        Map<String, Object> cuerpo = mapa(clave, null, total, lineas);
        cuerpo.remove("formaPagoId");
        List<Map<String, Object>> pagos = new ArrayList<>();
        for (Object[] p : partes) {
            pagos.add(new LinkedHashMap<>(Map.of("formaPagoId", p[0], "monto", new BigDecimal((String) p[1]))));
        }
        cuerpo.put("pagos", pagos);
        if (cliente != null) {
            cuerpo.put("clienteId", cliente);
        }
        return objectMapper.writeValueAsString(cuerpo);
    }

    private JsonNode cajaActual(Tienda t) throws Exception {
        String dueno = login(t.emailDueno());
        long caja = getJson("/api/cajas/actual", dueno).get("caja").get("id").asLong();
        return getJson("/api/cajas/" + caja, dueno);
    }

    @Test
    @DisplayName("el reintento de una venta en efectivo con descuento devuelve la misma venta, sin duplicar caja ni stock")
    void reintentoConDescuento() throws Exception {
        Tienda t = tiendaConDatosFiscales("RESPONSABLE_INSCRIPTO");
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long efectivo = conDescuento(t, "EFECTIVO", "Efectivo", "10");
        String cuerpo = unaForma(clave(), "270.00", efectivo, null, linea(a, "3", "100.00"));

        JsonNode primera = json(postJson("/api/ventas", login(t.emailVendedor()), cuerpo).andExpect(status().isCreated()));
        JsonNode segunda = json(postJson("/api/ventas", login(t.emailVendedor()), cuerpo).andExpect(status().isOk()));

        assertThat(segunda.get("ventaId").asLong()).isEqualTo(primera.get("ventaId").asLong());
        assertThat(comprobantes(t)).isEqualTo(1);
        assertThat(cajaActual(t).get("ventas").get("EFECTIVO").decimalValue()).isEqualByComparingTo("270.00");
        assertThat(stock(a)).isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("mixto con neto cero (-10 y +10) y con neto negativo: totales, caja por medio y reintentos idempotentes")
    void mixtoNetoCeroYNegativo() throws Exception {
        Tienda t = tiendaConDatosFiscales("RESPONSABLE_INSCRIPTO");
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long efectivo = conDescuento(t, "EFECTIVO", "Efectivo", "10");
        long credito = conRecargo(t, "CREDITO", "Crédito", "10");
        String cero = mixto(clave(), "200.00", null,
                new Object[][] {{efectivo, "100.00"}, {credito, "100.00"}}, linea(a, "2", "100.00"));

        JsonNode venta = json(postJson("/api/ventas", login(t.emailVendedor()), cero).andExpect(status().isCreated()));

        assertThat(venta.get("total").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(venta.get("recargo").decimalValue()).isEqualByComparingTo("0");
        assertThat(venta.get("descuento").decimalValue()).isEqualByComparingTo("0");
        assertThat(venta.get("pagos").get(0).get("monto").decimalValue()).isEqualByComparingTo("90.00");
        assertThat(venta.get("pagos").get(1).get("monto").decimalValue()).isEqualByComparingTo("110.00");
        JsonNode caja = cajaActual(t);
        assertThat(caja.get("ventas").get("EFECTIVO").decimalValue()).isEqualByComparingTo("90.00");
        assertThat(caja.get("ventas").get("CREDITO").decimalValue()).isEqualByComparingTo("110.00");
        JsonNode otra = json(postJson("/api/ventas", login(t.emailVendedor()), cero).andExpect(status().isOk()));
        assertThat(otra.get("ventaId").asLong()).isEqualTo(venta.get("ventaId").asLong());

        // Efectivo 200 (-20) + credito 100 (+10) = 290.
        String negativo = mixto(clave(), "290.00", null,
                new Object[][] {{efectivo, "200.00"}, {credito, "100.00"}}, linea(a, "3", "100.00"));
        JsonNode otraVenta = json(postJson("/api/ventas", login(t.emailVendedor()), negativo)
                .andExpect(status().isCreated()));
        assertThat(otraVenta.get("recargo").decimalValue()).isEqualByComparingTo("-10.00");
        assertThat(otraVenta.get("total").decimalValue()).isEqualByComparingTo("290.00");
        postJson("/api/ventas", login(t.emailVendedor()), negativo).andExpect(status().isOk());
    }

    @Test
    @DisplayName("reportes: «Con descuento» mide solo el del Vendedor; «por medio de pago» y el resumen suman lo cobrado")
    void reportes() throws Exception {
        Tienda t = tiendaConDatosFiscales("RESPONSABLE_INSCRIPTO");
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "50");
        long efectivo = conDescuento(t, "EFECTIVO", "Efectivo", "10");
        String dueno = login(t.emailDueno());
        postJson("/api/ventas", dueno, unaForma(clave(), "270.00", efectivo, null, linea(a, "3", "100.00")))
                .andExpect(status().isCreated());
        JsonNode conGlobal = json(postJson("/api/ventas", dueno,
                unaForma(clave(), "243.00", efectivo, "10", linea(a, "3", "100.00"))).andExpect(status().isCreated()));

        JsonNode conDescuento = getJson("/api/reportes/ventas/con-descuento", dueno);
        JsonNode porMedio = getJson("/api/reportes/ventas/por-medio-pago", dueno);
        JsonNode resumen = getJson("/api/reportes/ventas/resumen", dueno);

        assertThat(conDescuento.get("filas")).hasSize(1);
        assertThat(conDescuento.get("filas").get(0).get("ventaId").asLong())
                .isEqualTo(conGlobal.get("ventaId").asLong());
        assertThat(conDescuento.get("filas").get(0).get("descuento").decimalValue()).isEqualByComparingTo("30.00");
        assertThat(conDescuento.get("filas").get(0).get("total").decimalValue()).isEqualByComparingTo("243.00");
        assertThat(porMedio.get("filas").get(0).get("monto").decimalValue()).isEqualByComparingTo("513.00");
        assertThat(resumen.get("total").decimalValue()).isEqualByComparingTo("513.00");
    }

    @Test
    @DisplayName("la anulacion interna de una venta con descuento: NC por lo cobrado con el recargo negativo, stock repuesto y caja")
    void anulacionInterna() throws Exception {
        Tienda t = tiendaConDatosFiscales("RESPONSABLE_INSCRIPTO");
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long efectivo = conDescuento(t, "EFECTIVO", "Efectivo", "10");
        JsonNode venta = json(postJson("/api/ventas", login(t.emailVendedor()),
                unaForma(clave(), "270.00", efectivo, null, linea(a, "3", "100.00"))).andExpect(status().isCreated()));

        JsonNode nota = json(postJson("/api/ventas/" + venta.get("ventaId").asLong() + "/anular", login(t.emailDueno()),
                "{\"motivo\": \"Se equivoco de articulo\"}").andExpect(status().isCreated()));

        assertThat(nota.get("total").decimalValue()).isEqualByComparingTo("270.00");
        assertThat(nota.get("recargo").decimalValue()).isEqualByComparingTo("-30.00");
        assertThat(stock(a)).isEqualByComparingTo("10");
        assertThat(cajaActual(t).get("ventasAnuladas").get("total").decimalValue()).isEqualByComparingTo("270.00");
    }

    @Test
    @DisplayName("mixto efectivo con descuento + cuenta corriente: la cuenta debe solo su parte, y anular la deja en 0")
    void mixtoConCuentaCorriente() throws Exception {
        Tienda t = tiendaConDatosFiscales("RESPONSABLE_INSCRIPTO");
        activarFormaCuentaCorriente(t);
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long efectivo = conDescuento(t, "EFECTIVO", "Efectivo", "10");
        long cc = formaDe(t, "CUENTA_CORRIENTE");
        String razon = "Cliente " + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into cliente (ferreteria_id, razon_social, limite_cuenta_corriente) values (?, ?, 1000.00)",
                t.id(), razon);
        long c = jdbc.queryForObject("select id from cliente where ferreteria_id = ? and razon_social = ?", Long.class,
                t.id(), razon);

        JsonNode venta = json(postJson("/api/ventas", login(t.emailVendedor()),
                mixto(clave(), "290.00", c, new Object[][] {{efectivo, "100.00"}, {cc, "200.00"}},
                        linea(a, "3", "100.00"))).andExpect(status().isCreated()));

        assertThat(venta.get("total").decimalValue()).isEqualByComparingTo("290.00");
        assertThat(jdbc.queryForObject("select saldo from cliente where id = ?", BigDecimal.class, c))
                .isEqualByComparingTo("200.00");
        assertThat(jdbc.queryForObject("select sum(debe) from movimiento_cuenta_cliente where cliente_id = ?",
                BigDecimal.class, c)).isEqualByComparingTo("200.00");
        JsonNode caja = cajaActual(t);
        assertThat(caja.get("ventas").get("EFECTIVO").decimalValue()).isEqualByComparingTo("90.00");
        assertThat(caja.get("ventas").get("CUENTA_CORRIENTE").decimalValue()).isEqualByComparingTo("200.00");
        JsonNode nota = json(postJson("/api/ventas/" + venta.get("ventaId").asLong() + "/anular", login(t.emailDueno()),
                "{\"motivo\": \"Se equivoco de articulo\"}").andExpect(status().isCreated()));
        assertThat(nota.get("total").decimalValue()).isEqualByComparingTo("290.00");
        assertThat(jdbc.queryForObject("select saldo from cliente where id = ?", BigDecimal.class, c))
                .isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("la cuenta corriente queda con descuento 0 al editarla, al pasarle una forma con descuento y al crearla")
    void cuentaCorrienteSinDescuento() throws Exception {
        Tienda t = tiendaConDatosFiscales("RESPONSABLE_INSCRIPTO");
        String dueno = login(t.emailDueno());
        long cc = formaDe(t, "CUENTA_CORRIENTE");

        JsonNode editada = json(putJson("/api/formas-pago/" + cc, dueno,
                "{\"nombre\": \"Cuenta corriente\", \"medio\": \"CUENTA_CORRIENTE\", \"descuentoPorcentaje\": 10}")
                .andExpect(status().isOk()));
        long transferencia = conDescuento(t, "TRANSFERENCIA", "Transferencia", "5");
        JsonNode convertida = json(putJson("/api/formas-pago/" + transferencia, dueno,
                "{\"nombre\": \"Fiado\", \"medio\": \"CUENTA_CORRIENTE\"}").andExpect(status().isOk()));
        JsonNode alta = json(postJson("/api/formas-pago", dueno,
                "{\"nombre\": \"Fiado 2\", \"medio\": \"CUENTA_CORRIENTE\", \"descuentoPorcentaje\": 7}")
                .andExpect(status().isCreated()));

        assertThat(editada.get("descuentoPorcentaje").decimalValue()).isEqualByComparingTo("0");
        assertThat(convertida.get("descuentoPorcentaje").decimalValue()).isEqualByComparingTo("0");
        assertThat(alta.get("descuentoPorcentaje").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("recargo y descuento juntos: el 422 no toca la fila ni crea nada; el recargo que no viene se conserva; con recargo 0 pasa")
    void recargoYDescuentoJuntos() throws Exception {
        Tienda t = tiendaConDatosFiscales("RESPONSABLE_INSCRIPTO");
        String dueno = login(t.emailDueno());
        long credito = conRecargo(t, "CREDITO", "Crédito", "5");
        Map<String, Object> antes = jdbc.queryForMap("select * from forma_pago where id = ?", credito);

        putJson("/api/formas-pago/" + credito, dueno,
                "{\"nombre\": \"Crédito X\", \"medio\": \"CREDITO\", \"recargoPorcentaje\": 5, \"descuentoPorcentaje\": 5}")
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForMap("select * from forma_pago where id = ?", credito)).isEqualTo(antes);
        putJson("/api/formas-pago/" + credito, dueno,
                "{\"nombre\": \"Crédito\", \"medio\": \"CREDITO\", \"descuentoPorcentaje\": 5}")
                .andExpect(status().isUnprocessableEntity());
        long formas = jdbc.queryForObject("select count(*) from forma_pago where ferreteria_id = ?", Long.class, t.id());
        postJson("/api/formas-pago", dueno,
                "{\"nombre\": \"Ambas\", \"medio\": \"DEBITO\", \"recargoPorcentaje\": 1, \"descuentoPorcentaje\": 1}")
                .andExpect(status().isUnprocessableEntity());
        assertThat(jdbc.queryForObject("select count(*) from forma_pago where ferreteria_id = ?", Long.class, t.id()))
                .isEqualTo(formas);

        JsonNode pasada = json(putJson("/api/formas-pago/" + credito, dueno,
                "{\"nombre\": \"Crédito\", \"medio\": \"CREDITO\", \"recargoPorcentaje\": 0, \"descuentoPorcentaje\": 5}")
                .andExpect(status().isOk()));
        assertThat(pasada.get("descuentoPorcentaje").decimalValue()).isEqualByComparingTo("5");
        assertThat(pasada.get("recargoPorcentaje").decimalValue()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("el tope del Vendedor no cuenta el descuento de la forma, pero sigue frenando su descuento global")
    void topeDelVendedor() throws Exception {
        Tienda t = tiendaConDatosFiscales("RESPONSABLE_INSCRIPTO");
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long efectivo = conDescuento(t, "EFECTIVO", "Efectivo", "20");
        jdbc.update("update usuario set tope_descuento = 0 where email = ?", t.emailVendedor());

        postJson("/api/ventas", login(t.emailVendedor()),
                unaForma(clave(), "240.00", efectivo, null, linea(a, "3", "100.00"))).andExpect(status().isCreated());
        postJson("/api/ventas", login(t.emailVendedor()),
                unaForma(clave(), "216.00", efectivo, "10", linea(a, "3", "100.00"))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("un articulo en oferta tambien lleva el descuento de la forma: $ 800 en efectivo al 10 % son $ 720")
    void ofertaConDescuento() throws Exception {
        Tienda t = tiendaConDatosFiscales("RESPONSABLE_INSCRIPTO");
        long a = articulo(t, "Taladro", "UNIDAD", "1000.00", "10");
        jdbc.update("update articulo set oferta_tipo = 'PRECIO_FIJO', oferta_valor = 800 where id = ?", a);
        long efectivo = conDescuento(t, "EFECTIVO", "Efectivo", "10");

        JsonNode venta = json(postJson("/api/ventas", login(t.emailVendedor()),
                unaForma(clave(), "720.00", efectivo, null, linea(a, "1", "800.00"))).andExpect(status().isCreated()));

        assertThat(venta.get("total").decimalValue()).isEqualByComparingTo("720.00");
    }

    @Test
    @DisplayName("Factura A con 21 % y 10,5 % al 7,5 %: total 437,99 = neto + IVA y cada renglon con su parte negativa")
    void facturaAConDosAlicuotas() throws Exception {
        Tienda t = tiendaQueFactura("RESPONSABLE_INSCRIPTO");
        long a = articulo(t, "Martillo", "UNIDAD", "121.00", "10");
        long b = articulo(t, "Clavo", "UNIDAD", "110.50", "10", "IVA_10_5", true);
        conDescuento(t, "EFECTIVO", "Efectivo", "7.5");
        long c = cliente(t, "Cliente RI " + UUID.randomUUID().toString().substring(0, 6), "RESPONSABLE_INSCRIPTO", "CUIT",
                cuitDeCliente(), "Calle 123");

        // 121 x 3 + 110,50 = 473,50; el 7,5 % es 35,5125 -> 35,51; total 437,99.
        JsonNode venta = json(venderA(t, c, null, clave(), "437.99", linea(a, "3", "121.00"), linea(b, "1", "110.50"))
                .andExpect(status().isCreated()));

        Map<String, Object> registro = registroDe(venta.get("ventaId").asLong());
        assertThat(registro).containsEntry("estado", "AUTORIZADO");
        BigDecimal total = (BigDecimal) registro.get("imp_total");
        assertThat(total).isEqualByComparingTo("437.99");
        assertThat(((BigDecimal) registro.get("imp_neto")).add((BigDecimal) registro.get("imp_iva")))
                .isEqualByComparingTo(total);
        BigDecimal suma = BigDecimal.ZERO;
        for (JsonNode renglon : venta.get("renglones")) {
            suma = suma.add(renglon.get("recargo").decimalValue());
            assertThat(renglon.get("recargo").decimalValue().signum()).isLessThanOrEqualTo(0);
        }
        assertThat(suma).isEqualByComparingTo("-35.51");
    }
}
