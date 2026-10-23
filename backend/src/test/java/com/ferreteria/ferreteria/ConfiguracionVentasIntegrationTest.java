package com.ferreteria.ferreteria;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ferreteria.support.IntegrationTestBase;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.Test;

/**
 * El tope de venta a Consumidor Final sin identificar (GDS-32, G88) de la ferreteria del token: arranca en el vigente de
 * ARCA ($ 10.000.000) y lo cambia solo el Dueño.
 */
class ConfiguracionVentasIntegrationTest extends IntegrationTestBase {

    private static final String URL = "/api/configuracion-ventas";

    /** Hash de demo1234, el mismo de db/dev-data. */
    private static final String HASH_DEMO = "$2a$10$ElilOUcrECf1crPXSQ9j3eXlR8GWOg4usWY6nWK.XHsCTXJ.3Q4.i";

    /** Ferreteria nueva, con lo que trae por defecto, y su dueno. Devuelve el token. */
    private String ferreteriaNueva() throws Exception {
        String cuit = "30-" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000)) + "-3";
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
                "Ventas " + cuit, cuit);
        Long id = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        String email = "config-ventas-" + cuit + "@test.local";
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, ?, 'Dueno ventas', 'DUENO')", id, email, HASH_DEMO);
        return login(email);
    }

    private static String tope(String valor) {
        return "{\"topeConsumidorFinal\": " + valor + "}";
    }

    @Test
    void arrancaEnElTopeDeArca() throws Exception {
        getResult(URL, ferreteriaNueva())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topeConsumidorFinal", is(10000000.0)));
    }

    @Test
    void elDuenoLoCambiaYNoTocaAOtraFerreteria() throws Exception {
        String dueno = ferreteriaNueva();
        String otra = ferreteriaNueva();

        putJson(URL, dueno, tope("250000.50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topeConsumidorFinal", is(250000.50)));

        getResult(URL, dueno).andExpect(jsonPath("$.topeConsumidorFinal", is(250000.50)));
        getResult(URL, otra).andExpect(jsonPath("$.topeConsumidorFinal", is(10000000.0)));
    }

    @Test
    void soloElDueno() throws Exception {
        String vendedor = login("vendedor@tornillo.demo");
        getResult(URL, vendedor).andExpect(status().isForbidden());
        putJson(URL, vendedor, tope("1")).andExpect(status().isForbidden());
        getResult(URL, login("admin@demo.local")).andExpect(status().isForbidden());
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
    }

    @Test
    void validaElTope() throws Exception {
        String dueno = ferreteriaNueva();

        putJson(URL, dueno, tope("0")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.topeConsumidorFinal", is("El tope tiene que ser mayor que $ 0.")));
        putJson(URL, dueno, tope("-5")).andExpect(status().isBadRequest());
        putJson(URL, dueno, tope("100.005")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.topeConsumidorFinal", is("El tope admite hasta 2 decimales.")));
        putJson(URL, dueno, tope("null")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.topeConsumidorFinal", is("El tope es obligatorio.")));
        putJson(URL, dueno, tope("10000000000000")).andExpect(status().isBadRequest());
        getResult(URL, dueno).andExpect(jsonPath("$.topeConsumidorFinal", is(10000000.0)));
    }
}
