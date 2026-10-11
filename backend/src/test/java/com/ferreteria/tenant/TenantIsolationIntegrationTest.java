package com.ferreteria.tenant;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Criterio de terminado de la fase 2 (docs/spec.md): un usuario de cada una de dos
 * ferreterias entra y no ve nada de la otra. Corre contra MySQL real con Testcontainers.
 * Necesita Docker corriendo: si no hay Docker, falla (a proposito: no se saltea en silencio).
 * Datos de db/dev-data: El Tornillo (Buloneria, Herramientas) y La Tuerca (Pintureria).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class TenantIsolationIntegrationTest {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4");

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

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

    private String login(String email) throws Exception {
        String body = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"demo1234\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("token").asText();
    }

    private JsonNode getJson(String url, String token) throws Exception {
        String body = mvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    private static java.util.List<String> nombres(JsonNode array) {
        java.util.List<String> nombres = new java.util.ArrayList<>();
        array.forEach(n -> nombres.add(n.get("nombre").asText()));
        return nombres;
    }
}
