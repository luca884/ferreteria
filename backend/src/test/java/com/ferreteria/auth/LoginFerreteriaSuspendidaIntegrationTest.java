package com.ferreteria.auth;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * R-111 y R-114: el login avisa que la ferretería está suspendida SOLO cuando el email y la contraseña son correctos.
 * Con cualquier otro dato el mensaje es el genérico de siempre. Siempre 401.
 */
class LoginFerreteriaSuspendidaIntegrationTest extends UsuariosTestBase {

    private static final String SUSPENDIDA = "Tu ferretería está suspendida. Contactá al proveedor.";
    private static final String GENERICO = "Email o contraseña incorrectos";

    private long ferreteria;
    private String email;

    @BeforeEach
    void armar() {
        ferreteria = crearFerreteria("Suspendida");
        email = unico("dueno") + "@suspendida.test";
        crearUsuario(ferreteria, email, "Dueño", "DUENO");
        jdbc.update("update ferreteria set estado = 'SUSPENDIDA' where id = ?", ferreteria);
    }

    @Test
    @DisplayName("con email y contraseña correctos da 401 con el mensaje de ferretería suspendida")
    void contrasenaBuena() throws Exception {
        intentarLogin(email, "demo1234").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is(SUSPENDIDA)))
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    @DisplayName("con la contraseña mala da 401 con el mensaje genérico, sin revelar el estado")
    void contrasenaMala() throws Exception {
        intentarLogin(email, "equivocada").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is(GENERICO)));
    }

    @Test
    @DisplayName("con un email inexistente da 401 genérico")
    void emailInexistente() throws Exception {
        intentarLogin(unico("nadie") + "@suspendida.test", "demo1234").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is(GENERICO)));
    }

    @Test
    @DisplayName("un usuario inactivo con contraseña buena sigue dando el genérico")
    void usuarioInactivo() throws Exception {
        jdbc.update("update usuario set activo = false where email = ?", email);

        intentarLogin(email, "demo1234").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is(GENERICO)));
    }

    @Test
    @DisplayName("reactivada la ferretería el mismo usuario entra")
    void reactivada() throws Exception {
        jdbc.update("update ferreteria set estado = 'ACTIVA' where id = ?", ferreteria);

        intentarLogin(email, "demo1234").andExpect(status().isOk());
    }

    @Test
    @DisplayName("el super admin, sin ferretería, entra")
    void superAdmin() throws Exception {
        intentarLogin("admin@demo.local", "demo1234").andExpect(status().isOk());
    }
}
