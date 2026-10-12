package com.ferreteria.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Criterio de terminado de la fase 2 (docs/spec.md): un usuario de cada una de dos
 * ferreterias entra y no ve nada de la otra. Corre contra MySQL real con Testcontainers
 * (el contenedor y los helpers estan en IntegrationTestBase).
 * Datos de db/dev-data: El Tornillo (Buloneria, Herramientas) y La Tuerca (Pintureria).
 */
class TenantIsolationIntegrationTest extends IntegrationTestBase {

    @Test
    void cadaFerreteriaVeSoloSusRubros() throws Exception {
        String tornillo = login("dueno@tornillo.demo");
        String tuerca = login("dueno@tuerca.demo");

        JsonNode rubrosTornillo = getJson("/api/rubros", tornillo);
        JsonNode rubrosTuerca = getJson("/api/rubros", tuerca);

        assertThat(nombres(rubrosTornillo)).contains("Buloneria", "Herramientas").doesNotContain("Pintureria");
        assertThat(nombres(rubrosTuerca)).contains("Pintureria").doesNotContain("Buloneria", "Herramientas");
    }

    @Test
    void unRubroDeOtraFerreteriaDa404() throws Exception {
        String tornillo = login("dueno@tornillo.demo");
        String tuerca = login("dueno@tuerca.demo");
        long idPintureria = getJson("/api/rubros", tuerca).get(0).get("id").asLong();

        mvc.perform(get("/api/rubros/" + idPintureria).header("Authorization", "Bearer " + tornillo))
                .andExpect(status().isNotFound());
    }

    @Test
    void unRubroCreadoQuedaEnLaFerreteriaDelToken() throws Exception {
        String tuerca = login("dueno@tuerca.demo");
        String tornillo = login("dueno@tornillo.demo");

        mvc.perform(post("/api/rubros")
                        .header("Authorization", "Bearer " + tuerca)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Electricidad\",\"margenPorcentaje\":45}"))
                .andExpect(status().isCreated());

        assertThat(nombres(getJson("/api/rubros", tuerca))).contains("Electricidad");
        assertThat(nombres(getJson("/api/rubros", tornillo))).doesNotContain("Electricidad");
    }

    @Test
    void elVendedorNoPuedeCrearRubros() throws Exception {
        String vendedor = login("vendedor@tornillo.demo");

        mvc.perform(post("/api/rubros")
                        .header("Authorization", "Bearer " + vendedor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Sanitarios\",\"margenPorcentaje\":30}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void sinTokenDa401() throws Exception {
        mvc.perform(get("/api/rubros")).andExpect(status().isUnauthorized());
    }

    @Test
    void contrasenaIncorrectaDa401() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"dueno@tornillo.demo\",\"password\":\"otra\"}"))
                .andExpect(status().isUnauthorized());
    }
}
