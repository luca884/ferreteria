package com.ferreteria.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** mejoras-ferreteros-bloque-1 (S5): el Dueño carga el teléfono de cada usuario, opcional. */
class UsuariosTelefonoIntegrationTest extends UsuariosTestBase {

    private long ferreteria;
    private String dueno;

    @BeforeEach
    void armar() throws Exception {
        ferreteria = crearFerreteria("Telefono");
        String email = unico("dueno") + "@telefono.test";
        crearUsuario(ferreteria, email, "Dueño Teléfono", "DUENO");
        dueno = login(email);
    }

    private long crearVendedor(String email, String telefono) throws Exception {
        String cuerpo = postJson("/api/usuarios", dueno, json("nombre", "Vendedor", "email", email, "rol", "VENDEDOR",
                "password", "demo1234", "telefono", telefono))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(cuerpo).get("id").asLong();
    }

    private String telefonoGuardado(long id) {
        return jdbc.queryForObject("select telefono from usuario where id = ?", String.class, id);
    }

    @Test
    @DisplayName("el alta guarda el teléfono sin espacios sobrantes y la lista lo muestra")
    void altaConTelefono() throws Exception {
        long id = crearVendedor(unico("v") + "@telefono.test", " 223 555-1234 ");

        assertThat(telefonoGuardado(id)).isEqualTo("223 555-1234");
        getResult("/api/usuarios", dueno)
                .andExpect(jsonPath("$[?(@.id == " + id + ")].telefono").value("223 555-1234"));
    }

    @Test
    @DisplayName("sin teléfono, o en blanco, queda vacío")
    void altaSinTelefono() throws Exception {
        long id = crearVendedor(unico("v") + "@telefono.test", "   ");

        assertThat(telefonoGuardado(id)).isNull();
    }

    @Test
    @DisplayName("la edición cambia el teléfono")
    void edicionCambiaElTelefono() throws Exception {
        String email = unico("v") + "@telefono.test";
        long id = crearVendedor(email, "111");

        putJson("/api/usuarios/" + id, dueno, json("nombre", "Vendedor", "email", email, "rol", "VENDEDOR",
                "telefono", "222"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.telefono", is("222")));

        assertThat(telefonoGuardado(id)).isEqualTo("222");
    }

    @Test
    @DisplayName("un teléfono de más de 30 caracteres da 400 en español")
    void telefonoLargo() throws Exception {
        postJson("/api/usuarios", dueno, json("nombre", "Vendedor", "email", unico("v") + "@telefono.test", "rol",
                "VENDEDOR", "password", "demo1234", "telefono", "1".repeat(31)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.telefono", is("El teléfono admite hasta 30 caracteres.")));
    }
}
