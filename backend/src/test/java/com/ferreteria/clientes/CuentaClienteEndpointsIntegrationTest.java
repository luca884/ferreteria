package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * CC-003, CC-007, CC-008, CC-018 contra MySQL real: {@code GET /api/clientes/{id}/cuenta-corriente?importe=} (el Dueño ve las
 * cifras, el Vendedor solo si alcanza) y {@code PUT .../cuenta-corriente/limite} (solo el Dueño). Cliente ajeno es 404.
 */
class CuentaClienteEndpointsIntegrationTest extends CuentaClienteWebTestBase {

    @Override
    protected String bloqueCuit() {
        return "8303";
    }

    private static String cuenta(long id) {
        return "/api/clientes/" + id + "/cuenta-corriente";
    }

    private static String limiteUrl(long id) {
        return cuenta(id) + "/limite";
    }

    private static List<String> claves(JsonNode nodo) {
        List<String> claves = new ArrayList<>();
        nodo.fieldNames().forEachRemaining(claves::add);
        return claves;
    }

    // ------------------------------------------------------------------ GET

    @Test
    @DisplayName("GET del Dueño con importe: habilitada, alcanza, límite, saldo y disponible")
    void consultaDueno() throws Exception {
        Tienda t = tienda();
        long id = cliente(t, "1000.00", "400.00");
        String dueno = login(t.emailDueno());

        JsonNode alcanza = getJson(cuenta(id) + "?importe=500", dueno);
        JsonNode noAlcanza = getJson(cuenta(id) + "?importe=700.00", dueno);

        assertThat(alcanza.get("habilitada").asBoolean()).isTrue();
        assertThat(alcanza.get("alcanza").asBoolean()).isTrue();
        assertThat(alcanza.get("limite").decimalValue()).isEqualByComparingTo("1000");
        assertThat(alcanza.get("saldo").decimalValue()).isEqualByComparingTo("400");
        assertThat(alcanza.get("disponible").decimalValue()).isEqualByComparingTo("600");
        assertThat(noAlcanza.get("alcanza").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("GET del Vendedor: solo habilitada y alcanza; el JSON no trae ninguna cifra")
    void consultaVendedor() throws Exception {
        Tienda t = tienda();
        long id = cliente(t, "1000.00", "400.00");
        String vendedor = login(t.emailVendedor());

        JsonNode alcanza = getJson(cuenta(id) + "?importe=500", vendedor);
        JsonNode noAlcanza = getJson(cuenta(id) + "?importe=700", vendedor);

        assertThat(claves(alcanza)).containsExactlyInAnyOrder("habilitada", "alcanza");
        assertThat(alcanza.get("alcanza").asBoolean()).isTrue();
        assertThat(claves(noAlcanza)).containsExactlyInAnyOrder("habilitada", "alcanza");
        assertThat(noAlcanza.get("alcanza").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("GET sin importe no trae «alcanza»; sin cuenta habilitada, alcanza es false y no hay límite ni disponible")
    void sinImporteYSinHabilitar() throws Exception {
        Tienda t = tienda();
        long habilitado = cliente(t, "1000.00", "0.00");
        long sinCuenta = cliente(t, null, "50.00");
        String dueno = login(t.emailDueno());
        String vendedor = login(t.emailVendedor());

        JsonNode sinImporte = getJson(cuenta(habilitado), dueno);
        JsonNode deshabilitadoDueno = getJson(cuenta(sinCuenta) + "?importe=1", dueno);
        JsonNode deshabilitadoVendedor = getJson(cuenta(sinCuenta) + "?importe=1", vendedor);

        assertThat(sinImporte.has("alcanza")).isFalse();
        assertThat(sinImporte.get("disponible").decimalValue()).isEqualByComparingTo("1000");
        assertThat(deshabilitadoDueno.get("habilitada").asBoolean()).isFalse();
        assertThat(deshabilitadoDueno.get("alcanza").asBoolean()).isFalse();
        assertThat(deshabilitadoDueno.has("limite")).isFalse();
        assertThat(deshabilitadoDueno.has("disponible")).isFalse();
        assertThat(deshabilitadoDueno.get("saldo").decimalValue()).isEqualByComparingTo("50");
        assertThat(claves(deshabilitadoVendedor)).containsExactlyInAnyOrder("habilitada", "alcanza");
        assertThat(deshabilitadoVendedor.get("alcanza").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("GET con un importe negativo es 400 en importe; de un cliente de otra ferretería, 404")
    void getNegativoYAjeno() throws Exception {
        Tienda a = tienda();
        Tienda b = tienda();
        long propio = cliente(a, "1000.00", "0.00");
        long ajeno = cliente(b, "1000.00", "0.00");
        String dueno = login(a.emailDueno());

        JsonNode error = json(getResult(cuenta(propio) + "?importe=-1", dueno).andExpect(status().isBadRequest()));
        getResult(cuenta(ajeno), dueno).andExpect(status().isNotFound());
        getResult(cuenta(ajeno) + "?importe=1", login(a.emailVendedor())).andExpect(status().isNotFound());

        assertThat(error.get("errors").get("importe").asText()).isEqualTo("El importe no puede ser negativo.");
    }

    // ------------------------------------------------------------------ PUT

    @Test
    @DisplayName("PUT del Dueño fija el límite y devuelve la vista del Dueño con el saldo")
    void duenoFijaElLimite() throws Exception {
        Tienda t = tienda();
        long id = cliente(t, null, "300.00");

        JsonNode respuesta = json(putJson(limiteUrl(id), login(t.emailDueno()), "{\"limite\": 2500.50}")
                .andExpect(status().isOk()));

        assertThat(limite(id)).isEqualByComparingTo("2500.50");
        assertThat(respuesta.get("habilitada").asBoolean()).isTrue();
        assertThat(respuesta.get("limite").decimalValue()).isEqualByComparingTo("2500.50");
        assertThat(respuesta.get("saldo").decimalValue()).isEqualByComparingTo("300");
        assertThat(respuesta.get("disponible").decimalValue()).isEqualByComparingTo("2200.50");
        assertThat(respuesta.has("alcanza")).isFalse();
        assertInvariante(id);
    }

    @Test
    @DisplayName("PUT del Vendedor es 403 y no cambia el límite")
    void vendedorNoPuede() throws Exception {
        Tienda t = tienda();
        long id = cliente(t, "1000.00", "0.00");

        putJson(limiteUrl(id), login(t.emailVendedor()), "{\"limite\": 99999}").andExpect(status().isForbidden());

        assertThat(limite(id)).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("PUT con null deshabilita la cuenta (límite NULL), también con saldo; con 0 la habilita sin crédito")
    void nullDeshabilitaYCeroHabilita() throws Exception {
        Tienda t = tienda();
        long id = cliente(t, "1000.00", "300.00");
        String dueno = login(t.emailDueno());

        JsonNode deshabilitada = json(putJson(limiteUrl(id), dueno, "{\"limite\": null}").andExpect(status().isOk()));
        assertThat(limite(id)).isNull();
        assertThat(deshabilitada.get("habilitada").asBoolean()).isFalse();
        assertThat(deshabilitada.has("limite")).isFalse();
        assertThat(deshabilitada.get("saldo").decimalValue()).isEqualByComparingTo("300");

        JsonNode sinCredito = json(putJson(limiteUrl(id), dueno, "{\"limite\": 0}").andExpect(status().isOk()));
        assertThat(limite(id)).isEqualByComparingTo("0");
        assertThat(sinCredito.get("habilitada").asBoolean()).isTrue();
        assertThat(sinCredito.get("disponible").decimalValue()).isEqualByComparingTo("-300");
        assertInvariante(id);
    }

    @Test
    @DisplayName("PUT con un límite negativo, con más de 2 decimales o sobre el tope es 400 en limite y no cambia nada")
    void limiteInvalido() throws Exception {
        Tienda t = tienda();
        long id = cliente(t, "1000.00", "0.00");
        String dueno = login(t.emailDueno());

        for (String invalido : List.of("-1", "-0.01", "10.001", "10000000000000.00")) {
            JsonNode error = json(putJson(limiteUrl(id), dueno, "{\"limite\": " + invalido + "}")
                    .andExpect(status().isBadRequest()));
            assertThat(error.get("errors").has("limite")).as("limite %s", invalido).isTrue();
        }

        assertThat(limite(id)).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("el límite más grande que entra (9.999.999.999.999,99) se acepta")
    void limiteEnElTope() throws Exception {
        Tienda t = tienda();
        long id = cliente(t, null, "0.00");

        putJson(limiteUrl(id), login(t.emailDueno()), "{\"limite\": 9999999999999.99}").andExpect(status().isOk());

        assertThat(limite(id)).isEqualByComparingTo("9999999999999.99");
    }

    @Test
    @DisplayName("bajar el límite por debajo del saldo se acepta: el disponible queda negativo")
    void bajarPorDebajoDelSaldo() throws Exception {
        Tienda t = tienda();
        long id = cliente(t, "5000.00", "3000.00");

        JsonNode respuesta = json(putJson(limiteUrl(id), login(t.emailDueno()), "{\"limite\": 1000}")
                .andExpect(status().isOk()));

        assertThat(respuesta.get("disponible").decimalValue()).isEqualByComparingTo("-2000");
        assertThat(getJson(cuenta(id) + "?importe=0", login(t.emailDueno())).get("alcanza").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("PUT del límite de un cliente de otra ferretería es 404 y no cambia el de la otra")
    void putAjeno() throws Exception {
        Tienda a = tienda();
        Tienda b = tienda();
        long ajeno = cliente(b, "1000.00", "0.00");

        putJson(limiteUrl(ajeno), login(a.emailDueno()), "{\"limite\": 1}").andExpect(status().isNotFound());
        putJson(limiteUrl(Long.MAX_VALUE), login(a.emailDueno()), "{\"limite\": 1}").andExpect(status().isNotFound());

        assertThat(limite(ajeno)).isEqualByComparingTo("1000.00");
    }

    @Test
    @DisplayName("sin token no se ve ni se cambia nada: 401")
    void sinToken() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(cuenta(1)))
                .andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(HttpMethod.PUT, limiteUrl(1))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content("{\"limite\": 1}"))
                .andExpect(status().isUnauthorized());
    }
}
