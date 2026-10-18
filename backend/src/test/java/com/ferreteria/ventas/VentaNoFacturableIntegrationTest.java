package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CU-07 (NF-002): la venta no facturable con {@code POST /api/ventas} y {@code facturable: false}, contra MySQL real.
 * Cobra, mueve caja y stock y usa el numerador de siempre; solo cambia el estado del comprobante.
 */
class VentaNoFacturableIntegrationTest extends VentasTestBase {

    /** El cuerpo normal en efectivo con la marca puesta: sin marca (null) no manda el campo. */
    private ResultActions venderConMarca(String token, String clave, Boolean facturable, String total, Linea... lineas)
            throws Exception {
        Map<String, Object> cuerpo = mapa(clave, null, total, lineas);
        cuerpo.put("formaPagoId", formaDePago(token, "EFECTIVO"));
        if (facturable != null) {
            cuerpo.put("facturable", facturable);
        }
        return postJson("/api/ventas", token, objectMapper.writeValueAsString(cuerpo));
    }

    private String estado(long comprobanteId) {
        return jdbc.queryForObject("select estado from comprobante where id = ?", String.class, comprobanteId);
    }

    @Test
    @DisplayName("sin la marca la venta es facturable: SIN_FACTURAR y la respuesta dice facturable true")
    void sinMarca() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        JsonNode venta = json(venderConMarca(login(t.emailVendedor()), clave(), null, "100.00",
                linea(a, "1", "100.00")).andExpect(status().isCreated()));

        assertThat(venta.get("facturable").asBoolean()).isTrue();
        assertThat(estado(venta.get("ventaId").asLong())).isEqualTo("SIN_FACTURAR");
    }

    @Test
    @DisplayName("facturable true explicito es la venta de siempre")
    void conMarcaTrue() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        JsonNode venta = json(venderConMarca(login(t.emailVendedor()), clave(), true, "100.00",
                linea(a, "1", "100.00")).andExpect(status().isCreated()));

        assertThat(venta.get("facturable").asBoolean()).isTrue();
        assertThat(estado(venta.get("ventaId").asLong())).isEqualTo("SIN_FACTURAR");
    }

    @Test
    @DisplayName("facturable false guarda NO_FACTURABLE, baja el stock, entra en la caja y cobra con su pago")
    void noFacturable() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        JsonNode venta = json(venderConMarca(login(t.emailVendedor()), clave(), false, "200.00",
                linea(a, "2", "100.00")).andExpect(status().isCreated()));

        long id = venta.get("ventaId").asLong();
        assertThat(venta.get("facturable").asBoolean()).isFalse();
        assertThat(venta.get("total").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(estado(id)).isEqualTo("NO_FACTURABLE");
        assertThat(jdbc.queryForObject("select tipo from comprobante where id = ?", String.class, id)).isEqualTo("VENTA");
        assertThat(jdbc.queryForObject("select caja_id from comprobante where id = ?", Long.class, id)).isNotNull();
        assertThat(jdbc.queryForObject("select count(*) from pago where comprobante_id = ?", Long.class, id)).isEqualTo(1L);
        assertThat(stock(a)).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("la numeracion es una sola: facturable, no facturable y facturable salen 1, 2 y 3")
    void mismoNumerador() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());

        long uno = json(venderConMarca(token, clave(), null, "100.00", linea(a, "1", "100.00"))
                .andExpect(status().isCreated())).get("numero").asLong();
        long dos = json(venderConMarca(token, clave(), false, "100.00", linea(a, "1", "100.00"))
                .andExpect(status().isCreated())).get("numero").asLong();
        long tres = json(venderConMarca(token, clave(), true, "100.00", linea(a, "1", "100.00"))
                .andExpect(status().isCreated())).get("numero").asLong();

        assertThat(dos).isEqualTo(uno + 1);
        assertThat(tres).isEqualTo(dos + 1);
    }

    @Test
    @DisplayName("reenviar la misma venta no facturable es la misma (200); pedirla facturable con esa clave es 422")
    void idempotencia() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        String clave = clave();
        venderConMarca(token, clave, false, "100.00", linea(a, "1", "100.00")).andExpect(status().isCreated());

        JsonNode repetida = json(venderConMarca(token, clave, false, "100.00", linea(a, "1", "100.00"))
                .andExpect(status().isOk()));
        JsonNode rechazo = json(venderConMarca(token, clave, null, "100.00", linea(a, "1", "100.00"))
                .andExpect(status().is(422)));

        assertThat(repetida.get("facturable").asBoolean()).isFalse();
        assertThat(rechazo.get("motivo").asText()).isEqualTo("CLAVE_CON_OTRO_CONTENIDO");
        assertThat(comprobantes(t)).isEqualTo(1);
        assertThat(stock(a)).isEqualByComparingTo("9");
    }
}
