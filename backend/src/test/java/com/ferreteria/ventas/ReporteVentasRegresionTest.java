package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.common.HoraArgentina;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Regresiones de la verificacion independiente de los reportes de ventas (S1, S5, S6, S11): repetir la venta con la misma
 * clave, pago mixto con recargo, rubro de otra ferreteria y bordes del dia en hora de Buenos Aires.
 */
class ReporteVentasRegresionTest extends VentasTestBase {

    private static final String BASE = "/api/reportes/ventas/";

    private static BigDecimal bd(JsonNode n) { return n.decimalValue(); }

    @Test
    @org.junit.jupiter.api.DisplayName("repetir la venta con la misma clave no duplica el renglon ni cambia su costo, y no muestra el costo")
    void repetirLaVentaNoDuplicaNiExponeCosto() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        jdbc.update("update articulo set costo = 40.00 where id = ?", a);
        String v = login(t.emailVendedor());
        String k = clave();
        String c1 = vender(v, k, "100.00", linea(a, "1", "100.00")).andReturn().getResponse().getContentAsString();
        jdbc.update("update articulo set costo = 99.00 where id = ?", a);
        var r2 = vender(v, k, "100.00", linea(a, "1", "100.00")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(r2.getContentAsString()).doesNotContain("costo");
        assertThat(c1).doesNotContain("costo");
        List<Map<String, Object>> filas = jdbc.queryForList(
                "select costo_unitario, costo from item_comprobante where ferreteria_id = ?", t.id());
        assertThat(filas).hasSize(1);
        assertThat((BigDecimal) filas.get(0).get("costo")).isEqualByComparingTo("40.00");
        JsonNode r = getJson(BASE + "resumen", login(t.emailDueno()));
        assertThat(r.get("cantidad").asInt()).isEqualTo(1);
    }

    @Test
    @org.junit.jupiter.api.DisplayName("con pago mixto y recargo, los montos por medio de pago suman el total del resumen")
    void pagoMixtoConRecargoSumaElTotal() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        jdbc.update("update articulo set costo = 40.00 where id = ?", a);
        long credito = formaDe(t, "CREDITO");
        jdbc.update("update forma_pago set recargo_porcentaje = 10.00 where id = ?", credito);
        Map<String, Object> cuerpo = mapa(clave(), null, "320.00", linea(a, "3", "100.00"));
        cuerpo.remove("formaPagoId");
        List<Map<String, Object>> pagos = new ArrayList<>();
        Map<String, Object> p1 = new LinkedHashMap<>();
        p1.put("formaPagoId", formaDe(t, "EFECTIVO")); p1.put("monto", new BigDecimal("100.00"));
        Map<String, Object> p2 = new LinkedHashMap<>();
        p2.put("formaPagoId", credito); p2.put("monto", new BigDecimal("200.00"));
        pagos.add(p1); pagos.add(p2);
        cuerpo.put("pagos", pagos);
        postJson("/api/ventas", login(t.emailVendedor()), objectMapper.writeValueAsString(cuerpo))
                .andExpect(status().isCreated());
        String d = login(t.emailDueno());
        JsonNode resumen = getJson(BASE + "resumen", d);
        JsonNode medios = getJson(BASE + "por-medio-pago", d);
        BigDecimal suma = BigDecimal.ZERO;
        for (JsonNode f : medios.get("filas")) suma = suma.add(bd(f.get("monto")));
        assertThat(bd(resumen.get("total"))).isEqualByComparingTo("320.00");
        assertThat(medios.get("filas")).hasSize(2);
        assertThat(suma).isEqualByComparingTo(bd(resumen.get("total")));
    }

    @Test
    @org.junit.jupiter.api.DisplayName("bordes del dia en hora de Buenos Aires: 23:59:59 de ayer es ayer; 00:00 y 23:59:59 de hoy son hoy")
    void bordesDelDiaEnHoraDeBuenosAires() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String v = login(t.emailVendedor());
        LocalDate hoy = HoraArgentina.hoy();
        long[] ids = new long[3];
        for (int i = 0; i < 3; i++) {
            ids[i] = json(vender(v, clave(), "100.00", linea(a, "1", "100.00")).andExpect(status().isCreated()))
                    .get("ventaId").asLong();
        }
        LocalDateTime[] horas = {hoy.minusDays(1).atTime(23, 59, 59), hoy.atStartOfDay(), hoy.atTime(23, 59, 59)};
        for (int i = 0; i < 3; i++) {
            LocalDateTime guardada = horas[i].atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.UTC)
                    .toLocalDateTime();
            jdbc.update("update comprobante set fecha = ? where id = ?", guardada, ids[i]);
        }
        String d = login(t.emailDueno());
        assertThat(getJson(BASE + "resumen?desde=" + hoy, d).get("cantidad").asInt()).isEqualTo(2);
        assertThat(getJson(BASE + "resumen?hasta=" + hoy.minusDays(1), d).get("cantidad").asInt()).isEqualTo(1);
        JsonNode dias = getJson(BASE + "por-periodo?desde=" + hoy.minusDays(1) + "&hasta=" + hoy, d);
        assertThat(dias.get("filas")).hasSize(2);
        JsonNode cd = getJson(BASE + "con-descuento", d);
        assertThat(cd.get("filas")).isEmpty();
    }
}
