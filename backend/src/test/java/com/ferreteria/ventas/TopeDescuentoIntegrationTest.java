package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CU-07: el tope de descuento del usuario que confirma (como el «Limite de Descuento» de GDS). Se mide el descuento
 * efectivo de cada renglon (el del renglon y el global combinados); igual al tope entra. El Dueño no tiene tope.
 */
class TopeDescuentoIntegrationTest extends VentasTestBase {

    private void topeDelVendedor(Tienda t, String tope) {
        jdbc.update("update usuario set tope_descuento = ? where email = ?", new BigDecimal(tope), t.emailVendedor());
    }

    private Map<String, Object> conDescuentos(Map<String, Object> cuerpo, String global, String renglon) {
        if (global != null) {
            cuerpo.put("descuentoPorcentaje", new BigDecimal(global));
        }
        if (renglon != null) {
            ((Map<String, Object>) ((List<?>) cuerpo.get("renglones")).get(0))
                    .put("descuentoPorcentaje", new BigDecimal(renglon));
        }
        return cuerpo;
    }

    private String venta(String total, String global, String renglon, long articulo) throws Exception {
        return objectMapper.writeValueAsString(
                conDescuentos(mapa(clave(), "EFECTIVO", total, linea(articulo, "1", "100.00")), global, renglon));
    }

    private int ventas(Tienda t) {
        return jdbc.queryForObject("select count(*) from comprobante where ferreteria_id = ?", Integer.class, t.id());
    }

    @Test
    @DisplayName("un renglón con más descuento que el tope da 400 en el renglón con el tope, y no se vende")
    void renglonSobreElTope() throws Exception {
        Tienda t = tienda();
        topeDelVendedor(t, "10");
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        JsonNode cuerpo = json(postJson("/api/ventas", login(t.emailVendedor()), venta("85.00", null, "15.00", a))
                .andExpect(status().isBadRequest()));

        assertThat(cuerpo.get("errors").get("renglones[0].descuentoPorcentaje").asText())
                .isEqualTo("Tu tope de descuento es 10 %.");
        assertThat(ventas(t)).isZero();
        assertThat(stock(a)).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("un descuento global sobre el tope da 400 en el global; el tope con decimales se muestra con coma")
    void globalSobreElTope() throws Exception {
        Tienda t = tienda();
        topeDelVendedor(t, "12.5");
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        JsonNode cuerpo = json(postJson("/api/ventas", login(t.emailVendedor()), venta("87.00", "13.00", null, a))
                .andExpect(status().isBadRequest()));

        assertThat(cuerpo.get("errors").get("descuentoPorcentaje").asText())
                .isEqualTo("Tu tope de descuento es 12,5 %.");
        assertThat(cuerpo.get("errors").has("renglones[0].descuentoPorcentaje")).isFalse();
        assertThat(ventas(t)).isZero();
    }

    @Test
    @DisplayName("6 % en el renglón y 6 % global (11,64 % efectivo) pasan un tope de 10 %: 400 en el renglón")
    void combinadosSobreElTope() throws Exception {
        Tienda t = tienda();
        topeDelVendedor(t, "10");
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        JsonNode cuerpo = json(postJson("/api/ventas", login(t.emailVendedor()), venta("88.36", "6.00", "6.00", a))
                .andExpect(status().isBadRequest()));

        assertThat(cuerpo.get("errors").get("renglones[0].descuentoPorcentaje").asText())
                .isEqualTo("Sumado al descuento general, pasa tu tope de descuento de 10 %.");
        assertThat(ventas(t)).isZero();
    }

    @Test
    @DisplayName("un descuento igual al tope entra")
    void igualAlTopeEntra() throws Exception {
        Tienda t = tienda();
        topeDelVendedor(t, "10");
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        postJson("/api/ventas", login(t.emailVendedor()), venta("90.00", null, "10.00", a))
                .andExpect(status().isCreated());
        postJson("/api/ventas", login(t.emailVendedor()), venta("90.00", "10.00", null, a))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("sin tope (vendedor sin cargar o Dueño) se puede dar cualquier descuento válido")
    void sinTope() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        postJson("/api/ventas", login(t.emailVendedor()), venta("50.00", null, "50.00", a))
                .andExpect(status().isCreated());
        topeDelVendedor(t, "10");
        postJson("/api/ventas", login(t.emailDueno()), venta("50.00", "50.00", null, a))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("el tope es del usuario que confirma: el vendedor de otra ferretería con tope no cuenta")
    void elTopeEsDelUsuarioQueConfirma() throws Exception {
        Tienda otra = tienda();
        topeDelVendedor(otra, "5");
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        postJson("/api/ventas", login(t.emailVendedor()), venta("80.00", null, "20.00", a))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("presupuesto y remito con un descuento sobre el tope dan 400 en el renglón y no se emiten")
    void presupuestoYRemito() throws Exception {
        Tienda t = tienda();
        topeDelVendedor(t, "10");
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());

        for (String url : List.of("/api/presupuestos", "/api/remitos")) {
            Map<String, Object> cuerpo = mapa(clave(), null, "80.00", linea(a, "1", "100.00"));
            cuerpo.remove("formaPagoId");
            JsonNode respuesta = json(postJson(url, token,
                    objectMapper.writeValueAsString(conDescuentos(cuerpo, null, "20.00")))
                    .andExpect(status().isBadRequest()));
            assertThat(respuesta.get("errors").get("renglones[0].descuentoPorcentaje").asText())
                    .isEqualTo("Tu tope de descuento es 10 %.");
        }
        assertThat(jdbc.queryForObject("select count(*) from presupuesto where ferreteria_id = ?", Integer.class,
                t.id())).isZero();
        assertThat(jdbc.queryForObject("select count(*) from remito where ferreteria_id = ?", Integer.class, t.id()))
                .isZero();
        assertThat(stock(a)).isEqualByComparingTo("10");
    }
}
