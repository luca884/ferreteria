package com.ferreteria.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.ResultActions;

/** Aislamiento del CU-02 (R-001, R-006): el Dueño de una ferretería no ve ni toca los usuarios de otra, y solo el Dueño entra. */
class UsuariosAislamientoIntegrationTest extends UsuariosTestBase {

    private long usuarioDeB;
    private String duenoA;
    private String vendedorA;

    @BeforeEach
    void armar() throws Exception {
        long a = crearFerreteria("Aislamiento A");
        long b = crearFerreteria("Aislamiento B");
        String emailDuenoA = unico("duenoA") + "@aislamiento.test";
        String emailVendedorA = unico("vendedorA") + "@aislamiento.test";
        crearUsuario(a, emailDuenoA, "Dueño A", "DUENO");
        crearUsuario(a, emailVendedorA, "Vendedor A", "VENDEDOR");
        usuarioDeB = crearUsuario(b, unico("vendedorB") + "@aislamiento.test", "Vendedor B", "VENDEDOR");
        duenoA = login(emailDuenoA);
        vendedorA = login(emailVendedorA);
    }

    private ResultActions pedir(MockHttpServletRequestBuilder pedido, String token, String cuerpo) throws Exception {
        if (token != null) {
            pedido = pedido.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(pedido.contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private String cuerpoDe(ResultActions r) throws Exception {
        return r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** Los 5 endpoints, todos con un cuerpo que no valida, sobre un id dado. */
    private List<ResultActions> losCincoEndpoints(String token, long id) throws Exception {
        return List.of(
                pedir(get("/api/usuarios"), token, ""),
                pedir(post("/api/usuarios"), token, "{}"),
                pedir(put("/api/usuarios/" + id), token, "{}"),
                pedir(patch("/api/usuarios/" + id + "/activo"), token, "{}"),
                pedir(put("/api/usuarios/" + id + "/password"), token, "{}"));
    }

    @Test
    @DisplayName("el Dueño de A sobre un usuario de B da 404 en PUT, PATCH y reset, con el mismo cuerpo que un id inexistente")
    void usuarioDeOtraFerreteria() throws Exception {
        long inexistente = 987654321L;
        String put = json("nombre", "Hackeado", "email", unico("h") + "@aislamiento.test", "rol", "VENDEDOR");

        for (var par : List.of(
                new Object[] {"PUT", "/api/usuarios/%d", put},
                new Object[] {"PATCH", "/api/usuarios/%d/activo", json("activo", false)},
                new Object[] {"RESET", "/api/usuarios/%d/password", json("password", "clave-nueva-99")})) {
            String ruta = (String) par[1];
            String cuerpo = (String) par[2];
            MockHttpServletRequestBuilder ajeno = "PATCH".equals(par[0]) ? patch(ruta.formatted(usuarioDeB))
                    : put(ruta.formatted(usuarioDeB));
            MockHttpServletRequestBuilder ninguno = "PATCH".equals(par[0]) ? patch(ruta.formatted(inexistente))
                    : put(ruta.formatted(inexistente));

            String deB = cuerpoDe(pedir(ajeno, duenoA, cuerpo).andExpect(status().isNotFound()));
            String deNadie = cuerpoDe(pedir(ninguno, duenoA, cuerpo).andExpect(status().isNotFound()));

            assertThat(sinPathNiTimestamp(deB)).as(par[0].toString()).isEqualTo(sinPathNiTimestamp(deNadie));
            assertThat(sinPathNiTimestamp(deB).get("message").asText()).isEqualTo("Usuario no encontrado");
        }
        assertThat(jdbc.queryForObject("select activo from usuario where id = ?", Boolean.class, usuarioDeB)).isTrue();
        assertThat(jdbc.queryForObject("select nombre from usuario where id = ?", String.class, usuarioDeB))
                .isEqualTo("Vendedor B");
    }

    /** El cuerpo del error sin los campos que dependen del pedido (la ruta y la hora). */
    private JsonNode sinPathNiTimestamp(String cuerpo) throws Exception {
        return ((com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(cuerpo)).deepCopy()
                .without(List.of("path", "timestamp"));
    }

    @Test
    @DisplayName("la lista del Dueño de A no trae a los de B ni al super admin")
    void listaSoloDeLaFerreteria() throws Exception {
        JsonNode lista = getJson("/api/usuarios?incluirInactivos=true", duenoA);

        assertThat(nombres(lista)).containsExactly("Dueño A", "Vendedor A");
        assertThat(lista.toString()).doesNotContain("admin@demo.local");
    }

    @Test
    @DisplayName("el Vendedor recibe 403 en los 5 endpoints aunque el cuerpo no valide")
    void vendedorNoEntra() throws Exception {
        for (ResultActions r : losCincoEndpoints(vendedorA, usuarioDeB)) {
            r.andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("el super admin recibe 403 en los 5 endpoints")
    void superAdminNoEntra() throws Exception {
        for (ResultActions r : losCincoEndpoints(login("admin@demo.local"), usuarioDeB)) {
            r.andExpect(status().isForbidden());
        }
    }

    @Test
    @DisplayName("sin token da 401 en los 5 endpoints")
    void sinToken() throws Exception {
        for (ResultActions r : losCincoEndpoints(null, usuarioDeB)) {
            r.andExpect(status().isUnauthorized());
        }
    }
}
