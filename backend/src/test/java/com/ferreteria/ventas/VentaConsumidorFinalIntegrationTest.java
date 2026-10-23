package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * GDS-32 (G88): desde el tope de la ferreteria, la venta facturable a Consumidor Final necesita un cliente identificado
 * (con DNI, CUIT o CUIL). El rechazo es 422 CONSUMIDOR_FINAL_SIN_DATOS y no guarda nada.
 */
class VentaConsumidorFinalIntegrationTest extends VentasTestBase {

    private static final String MENSAJE = "Desde $ 1.000,00 la venta a Consumidor Final necesita identificar al "
            + "comprador: elegí un cliente con DNI, CUIT o CUIL.";

    private Tienda conTope(String tope) {
        Tienda t = tienda();
        jdbc.update("update ferreteria set tope_consumidor_final = ? where id = ?", new java.math.BigDecimal(tope),
                t.id());
        return t;
    }

    private long cliente(Tienda t, String razonSocial, String tipo, String numero) {
        jdbc.update("insert into cliente (ferreteria_id, razon_social, tipo_documento, numero_documento) "
                + "values (?, ?, ?, ?)", t.id(), razonSocial, tipo, numero);
        return jdbc.queryForObject("select id from cliente where ferreteria_id = ? and razon_social = ?", Long.class,
                t.id(), razonSocial);
    }

    private ResultActions venderA(Tienda t, Long clienteId, Boolean facturable, String medio, String total,
                                  Linea... lineas) throws Exception {
        Map<String, Object> cuerpo = mapa(clave(), null, total, lineas);
        cuerpo.put("formaPagoId", formaDe(t, medio));
        if (clienteId != null) {
            cuerpo.put("clienteId", clienteId);
        }
        if (facturable != null) {
            cuerpo.put("facturable", facturable);
        }
        return postJson("/api/ventas", login(t.emailVendedor()), objectMapper.writeValueAsString(cuerpo));
    }

    @Test
    @DisplayName("desde el tope justo y sin cliente es 422 con el tope en el mensaje, y no guarda nada")
    void enElTopeSinCliente() throws Exception {
        Tienda t = conTope("1000.00");
        long a = articulo(t, "Taladro", "UNIDAD", "500.00", "10");

        JsonNode rechazo = json(venderA(t, null, null, "EFECTIVO", "1000.00", linea(a, "2", "500.00"))
                .andExpect(status().is(422)));

        assertThat(rechazo.get("motivo").asText()).isEqualTo("CONSUMIDOR_FINAL_SIN_DATOS");
        assertThat(rechazo.get("message").asText()).isEqualTo(MENSAJE);
        assertThat(comprobantes(t)).isZero();
        assertThat(movimientos(t)).isZero();
        assertThat(stock(a)).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("el rechazo no gasta la clave ni el número: la misma clave con un cliente con DNI vende el N.º 1")
    void elRechazoNoGastaClaveNiNumero() throws Exception {
        Tienda t = conTope("1000.00");
        long a = articulo(t, "Taladro", "UNIDAD", "1500.00", "10");
        long conDni = cliente(t, "Juan Perez", "DNI", "30111222");
        Map<String, Object> cuerpo = mapa(clave(), null, "1500.00", linea(a, "1", "1500.00"));
        cuerpo.put("formaPagoId", formaDe(t, "EFECTIVO"));

        postJson("/api/ventas", login(t.emailVendedor()), objectMapper.writeValueAsString(cuerpo))
                .andExpect(status().is(422));
        cuerpo.put("clienteId", conDni);
        JsonNode venta = json(postJson("/api/ventas", login(t.emailVendedor()), objectMapper.writeValueAsString(cuerpo))
                .andExpect(status().isCreated()));

        assertThat(venta.get("numero").asLong()).isEqualTo(1);
        assertThat(comprobantes(t)).isEqualTo(1);
    }

    @Test
    @DisplayName("un centavo debajo del tope se vende sin cliente")
    void debajoDelTope() throws Exception {
        Tienda t = conTope("1000.00");
        long a = articulo(t, "Taladro", "UNIDAD", "999.99", "10");

        venderA(t, null, null, "EFECTIVO", "999.99", linea(a, "1", "999.99")).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("el recargo de la forma de pago cuenta: 900 con 20 % de recargo llega al tope")
    void cuentaElRecargo() throws Exception {
        Tienda t = conTope("1000.00");
        long a = articulo(t, "Taladro", "UNIDAD", "900.00", "10");
        jdbc.update("update forma_pago set recargo_porcentaje = 20 where id = ?", formaDe(t, "CREDITO"));

        JsonNode rechazo = json(venderA(t, null, null, "CREDITO", "1080.00", linea(a, "1", "900.00"))
                .andExpect(status().is(422)));

        assertThat(rechazo.get("motivo").asText()).isEqualTo("CONSUMIDOR_FINAL_SIN_DATOS");
    }

    @Test
    @DisplayName("un cliente sin documento no identifica; uno con DNI o CUIT si")
    void segunElCliente() throws Exception {
        Tienda t = conTope("1000.00");
        long a = articulo(t, "Taladro", "UNIDAD", "1500.00", "10");
        long sinDocumento = cliente(t, "Juan sin documento", null, null);
        long conDni = cliente(t, "Ana con DNI", "DNI", "30123456");
        long conCuit = cliente(t, "Obras SRL", "CUIT", "30712345671");

        JsonNode rechazo = json(venderA(t, sinDocumento, null, "EFECTIVO", "1500.00", linea(a, "1", "1500.00"))
                .andExpect(status().is(422)));
        assertThat(rechazo.get("motivo").asText()).isEqualTo("CONSUMIDOR_FINAL_SIN_DATOS");

        venderA(t, conDni, null, "EFECTIVO", "1500.00", linea(a, "1", "1500.00")).andExpect(status().isCreated());
        venderA(t, conCuit, null, "EFECTIVO", "1500.00", linea(a, "1", "1500.00")).andExpect(status().isCreated());
        assertThat(comprobantes(t)).isEqualTo(2);
    }

    @Test
    @DisplayName("la venta no facturable no se controla")
    void noFacturable() throws Exception {
        Tienda t = conTope("1000.00");
        long a = articulo(t, "Taladro", "UNIDAD", "1500.00", "10");

        venderA(t, null, false, "EFECTIVO", "1500.00", linea(a, "1", "1500.00")).andExpect(status().isCreated());
    }

    @Test
    @DisplayName("con el tope por defecto de ARCA ($ 10.000.000) se vende sin cliente hasta un centavo menos")
    void topePorDefecto() throws Exception {
        Tienda t = tienda();
        long debajo = articulo(t, "Grupo electrogeno", "UNIDAD", "9999999.99", "10");
        long justo = articulo(t, "Autoelevador", "UNIDAD", "10000000.00", "10");

        venderA(t, null, null, "EFECTIVO", "9999999.99", linea(debajo, "1", "9999999.99"))
                .andExpect(status().isCreated());
        JsonNode rechazo = json(venderA(t, null, null, "EFECTIVO", "10000000.00", linea(justo, "1", "10000000.00"))
                .andExpect(status().is(422)));
        assertThat(rechazo.get("message").asText()).startsWith("Desde $ 10.000.000,00 la venta");
    }
}
