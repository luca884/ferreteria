package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CU-07 pago mixto (GDS-07): {@code pagos} en {@code POST /api/ventas}, contra MySQL real. */
class VentaPagoMixtoIntegrationTest extends VentasTestBase {

    private record Parte(String medio, String monto) {}

    /**
     * El cuerpo con la lista de pagos y sin forma unica. Cada parte nombra el medio de la forma de siempre de la ultima
     * tienda; "INEXISTENTE" manda un id que no existe.
     */
    private String conPagos(String clave, String total, Linea linea, Parte... partes) throws Exception {
        Map<String, Object> cuerpo = mapa(clave, null, total, linea);
        cuerpo.remove("formaPagoId");
        List<Map<String, Object>> pagos = new ArrayList<>();
        for (Parte p : partes) {
            Map<String, Object> pago = new LinkedHashMap<>();
            pago.put("formaPagoId", "INEXISTENTE".equals(p.medio()) ? 999_999_999L : formaDe(ultimaTienda, p.medio()));
            pago.put("monto", new BigDecimal(p.monto()));
            pagos.add(pago);
        }
        cuerpo.put("pagos", pagos);
        return objectMapper.writeValueAsString(cuerpo);
    }

    @Test
    @DisplayName("el vendedor paga mitad en efectivo y mitad con débito: 201, dos pagos guardados y sin forma única")
    void pagoMixto() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        JsonNode venta = json(postJson("/api/ventas", login(t.emailVendedor()),
                conPagos(clave(), "300.00", linea(a, "3", "100.00"),
                        new Parte("EFECTIVO", "100.00"), new Parte("DEBITO", "200.00")))
                .andExpect(status().isCreated()));

        assertThat(venta.has("medioPago")).isFalse();
        assertThat(venta.has("formaPagoId")).isFalse();
        assertThat(venta.get("pagos")).hasSize(2);
        assertThat(venta.get("pagos").get(0).get("medio").asText()).isEqualTo("EFECTIVO");
        assertThat(venta.get("pagos").get(1).get("monto").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(jdbc.queryForList("select medio, monto from pago where comprobante_id = ? order by id",
                venta.get("ventaId").asLong())).hasSize(2);
    }

    @Test
    @DisplayName("la caja suma cada medio con lo que se pagó con él")
    void laCajaSumaPorMedio() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        postJson("/api/ventas", login(t.emailVendedor()),
                conPagos(clave(), "300.00", linea(a, "3", "100.00"),
                        new Parte("EFECTIVO", "100.00"), new Parte("TRANSFERENCIA", "200.00")))
                .andExpect(status().isCreated());

        Map<String, BigDecimal> porMedio = new LinkedHashMap<>();
        jdbc.queryForList("select p.medio, sum(p.monto) as total from pago p join comprobante c on c.id = p.comprobante_id "
                + "where c.ferreteria_id = ? group by p.medio", t.id())
                .forEach(f -> porMedio.put((String) f.get("medio"), (BigDecimal) f.get("total")));
        assertThat(porMedio.get("EFECTIVO")).isEqualByComparingTo("100.00");
        assertThat(porMedio.get("TRANSFERENCIA")).isEqualByComparingTo("200.00");
    }

    @Test
    @DisplayName("el dueño ve en la caja del día el efectivo y el digital del pago mixto")
    void elDuenoLoVeEnLaCaja() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        postJson("/api/ventas", login(t.emailVendedor()),
                conPagos(clave(), "300.00", linea(a, "3", "100.00"),
                        new Parte("EFECTIVO", "100.00"), new Parte("CREDITO", "200.00")))
                .andExpect(status().isCreated());
        String token = login(t.emailDueno());
        long cajaId = getJson("/api/cajas/actual", token).get("caja").get("id").asLong();

        JsonNode detalle = getJson("/api/cajas/" + cajaId, token);

        assertThat(detalle.get("ventas").get("EFECTIVO").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(detalle.get("ventas").get("CREDITO").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(detalle.get("totalVentas").decimalValue()).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("pagos que no suman el total dan 400 en pagos y no guardan nada")
    void noSumanElTotal() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        JsonNode cuerpo = json(postJson("/api/ventas", login(t.emailVendedor()),
                conPagos(clave(), "300.00", linea(a, "3", "100.00"),
                        new Parte("EFECTIVO", "100.00"), new Parte("DEBITO", "100.00")))
                .andExpect(status().isBadRequest()));

        assertThat(cuerpo.get("errors").get("pagos").asText()).isEqualTo("Los pagos tienen que sumar el total de la venta.");
        assertThat(comprobantes(t)).isZero();
        assertThat(stock(a)).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("un monto en cero, una forma repetida o una forma inexistente dan 400 y no guardan nada")
    void pagosInvalidos() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());

        JsonNode cero = json(postJson("/api/ventas", token, conPagos(clave(), "100.00", linea(a, "1", "100.00"),
                new Parte("EFECTIVO", "0.00"), new Parte("DEBITO", "100.00"))).andExpect(status().isBadRequest()));
        JsonNode repetido = json(postJson("/api/ventas", token, conPagos(clave(), "100.00", linea(a, "1", "100.00"),
                new Parte("EFECTIVO", "40.00"), new Parte("EFECTIVO", "60.00"))).andExpect(status().isBadRequest()));
        JsonNode inventado = json(postJson("/api/ventas", token, conPagos(clave(), "100.00", linea(a, "1", "100.00"),
                new Parte("INEXISTENTE", "100.00"))).andExpect(status().isBadRequest()));

        assertThat(cero.get("errors").get("pagos[0].monto").asText()).isEqualTo("El monto del pago tiene que ser mayor a cero.");
        assertThat(repetido.get("errors").get("pagos[1].formaPagoId").asText())
                .isEqualTo("Cada forma de pago va una sola vez.");
        assertThat(inventado.get("errors").get("pagos[0].formaPagoId").asText())
                .isEqualTo("La forma de pago no es válida.");
        assertThat(comprobantes(t)).isZero();
    }

    @Test
    @DisplayName("sin forma de pago ni lista de pagos da 400 en formaPagoId")
    void sinFormaNiPagos() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        Map<String, Object> cuerpo = mapa(clave(), null, "100.00", linea(a, "1", "100.00"));
        cuerpo.remove("formaPagoId");

        JsonNode error = json(postJson("/api/ventas", login(t.emailVendedor()), objectMapper.writeValueAsString(cuerpo))
                .andExpect(status().isBadRequest()));

        assertThat(error.get("errors").get("formaPagoId").asText()).isEqualTo("Elegí una forma de pago.");
        assertThat(comprobantes(t)).isZero();
    }

    @Test
    @DisplayName("repetir el POST con el mismo reparto devuelve la misma venta (200); con otro reparto, 422")
    void idempotenciaConPagos() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String clave = clave();
        String token = login(t.emailVendedor());
        Linea linea = linea(a, "3", "100.00");

        JsonNode primera = json(postJson("/api/ventas", token, conPagos(clave, "300.00", linea,
                new Parte("EFECTIVO", "100.00"), new Parte("DEBITO", "200.00"))).andExpect(status().isCreated()));
        JsonNode repetida = json(postJson("/api/ventas", token, conPagos(clave, "300.00", linea,
                new Parte("DEBITO", "200.00"), new Parte("EFECTIVO", "100.00"))).andExpect(status().isOk()));
        JsonNode otra = json(postJson("/api/ventas", token, conPagos(clave, "300.00", linea,
                new Parte("EFECTIVO", "150.00"), new Parte("DEBITO", "150.00")))
                .andExpect(status().isUnprocessableEntity()));

        assertThat(repetida.get("ventaId")).isEqualTo(primera.get("ventaId"));
        assertThat(repetida.get("pagos")).hasSize(2);
        assertThat(otra.get("motivo").asText()).isEqualTo("CLAVE_CON_OTRO_CONTENIDO");
        assertThat(comprobantes(t)).isEqualTo(1);
        assertThat(stock(a)).isEqualByComparingTo("7");
    }

    @Test
    @DisplayName("un pago por el total con la lista es igual a una forma única: una sola fila, con formaPagoId y medioPago en la respuesta")
    void unSoloPagoEnLaLista() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        JsonNode venta = json(postJson("/api/ventas", login(t.emailVendedor()),
                conPagos(clave(), "100.00", linea(a, "1", "100.00"), new Parte("CREDITO", "100.00")))
                .andExpect(status().isCreated()));

        assertThat(venta.get("medioPago").asText()).isEqualTo("CREDITO");
        assertThat(venta.get("formaPagoId").asLong()).isEqualTo(formaDe(t, "CREDITO"));
        assertThat(venta.get("pagos")).hasSize(1);
    }
}
