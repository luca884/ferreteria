package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Regla 7 del CLAUDE.md para los endpoints de cuenta corriente de clientes (CC-008): con dos ferreterías, lo que pide B sobre un
 * cliente de A es 404 (igual que un cliente inexistente), el cliente de A no aparece en las listas de B, y ni el saldo, ni el
 * límite, ni el estado, ni el libro de A cambian por acciones de B (ni al revés).
 */
class CuentaClienteAislamientoIntegrationTest extends CuentaClienteWebTestBase {

    @Override
    protected String bloqueCuit() {
        return "8304";
    }

    private record Estado(BigDecimal saldo, BigDecimal limite, boolean activo, long movimientos) {}

    private Estado estado(long clienteId) {
        Boolean activo = jdbc.queryForObject("select activo from cliente where id = ?", Boolean.class, clienteId);
        return new Estado(saldo(clienteId), limite(clienteId), activo, movimientos(clienteId));
    }

    @Test
    @DisplayName("B no ve, no consulta, no cambia el límite ni desactiva a un cliente de A: 404, y A queda igual")
    void bNoToca() throws Exception {
        Tienda a = tienda();
        Tienda b = tienda();
        long deA = cliente(a, "1000.00", "400.00");
        Estado antes = estado(deA);
        String duenoB = login(b.emailDueno());
        String vendedorB = login(b.emailVendedor());

        getResult("/api/clientes/" + deA + "/cuenta-corriente", duenoB).andExpect(status().isNotFound());
        getResult("/api/clientes/" + deA + "/cuenta-corriente?importe=10", vendedorB).andExpect(status().isNotFound());
        putJson("/api/clientes/" + deA + "/cuenta-corriente/limite", duenoB, "{\"limite\": 99999}")
                .andExpect(status().isNotFound());
        putJson("/api/clientes/" + deA + "/cuenta-corriente/limite", duenoB, "{\"limite\": null}")
                .andExpect(status().isNotFound());
        patchActivo(duenoB, deA, false).andExpect(status().isNotFound());
        getResult("/api/clientes/" + deA, duenoB).andExpect(status().isNotFound());
        getResult("/api/clientes/" + deA, vendedorB).andExpect(status().isNotFound());
        putJson("/api/clientes/" + deA, duenoB, "{\"razonSocial\": \"Hackeado\"}").andExpect(status().isNotFound());

        assertThat(getJson("/api/clientes", duenoB).get("items")).isEmpty();
        assertThat(estado(deA)).isEqualTo(antes);
        assertThat(jdbc.queryForObject("select razon_social from cliente where id = ?", String.class, deA))
                .isNotEqualTo("Hackeado");
        assertInvariante(deA);
    }

    @Test
    @DisplayName("el cliente de A no aparece en la lista de B ni la de A trae al de B, cada uno con sus cifras")
    void listasSeparadas() throws Exception {
        Tienda a = tienda();
        Tienda b = tienda();
        long deA = cliente(a, "1000.00", "400.00");
        long deB = cliente(b, "2000.00", "700.00");

        JsonNode listaA = getJson("/api/clientes", login(a.emailDueno()));
        JsonNode listaB = getJson("/api/clientes", login(b.emailDueno()));

        assertThat(listaA.get("items")).hasSize(1);
        assertThat(listaA.get("items").get(0).get("id").asLong()).isEqualTo(deA);
        assertThat(listaA.get("items").get(0).get("saldo").decimalValue()).isEqualByComparingTo("400");
        assertThat(listaB.get("items")).hasSize(1);
        assertThat(listaB.get("items").get(0).get("id").asLong()).isEqualTo(deB);
        assertThat(listaB.get("items").get(0).get("saldo").decimalValue()).isEqualByComparingTo("700");
    }

    @Test
    @DisplayName("cambiar el límite y la cuenta de un cliente de A no toca al cliente homónimo de B")
    void aNoTocaAB() throws Exception {
        Tienda a = tienda();
        Tienda b = tienda();
        long deA = cliente(a, "1000.00", "0.00");
        long deB = cliente(b, "1000.00", "0.00");
        Estado bAntes = estado(deB);

        putJson("/api/clientes/" + deA + "/cuenta-corriente/limite", login(a.emailDueno()), "{\"limite\": 5}")
                .andExpect(status().isOk());
        patchActivo(login(a.emailDueno()), deA, false).andExpect(status().isOk());

        assertThat(limite(deA)).isEqualByComparingTo("5");
        assertThat(estado(deB)).isEqualTo(bAntes);
        assertInvariante(deA);
        assertInvariante(deB);
    }
}
