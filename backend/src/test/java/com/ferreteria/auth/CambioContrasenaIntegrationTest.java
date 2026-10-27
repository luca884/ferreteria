package com.ferreteria.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** Cada usuario cambia la propia contraseña pidiendo la actual (R-012, R-013). Una actual mala es 400, nunca 401. */
class CambioContrasenaIntegrationTest extends UsuariosTestBase {

    private String email;
    private String token;

    @BeforeEach
    void armar() throws Exception {
        long ferreteria = crearFerreteria("Cambio de contraseña");
        email = unico("vendedor") + "@cambio.test";
        crearUsuario(ferreteria, email, "Vendedor", "VENDEDOR");
        token = login(email);
    }

    private ResultActions cambiar(String token, String cuerpo) throws Exception {
        return putJson("/api/auth/password", token, cuerpo);
    }

    @Test
    @DisplayName("con la actual correcta da 204 y entra con la nueva; la anterior ya no sirve")
    void cambiaLaContrasena() throws Exception {
        cambiar(token, json("passwordActual", "demo1234", "passwordNueva", "clave-nueva-99")).andExpect(status().isNoContent());

        intentarLogin(email, "clave-nueva-99").andExpect(status().isOk());
        intentarLogin(email, "demo1234").andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("cambiar la propia contraseña nunca prende la marca: un usuario sin marca sigue sin ella")
    void cambiarLaPropiaNoPrendeLaMarca() throws Exception {
        intentarLogin(email, "demo1234").andExpect(jsonPath("$.sesion.debeCambiarContrasena", is(false)));

        cambiar(token, json("passwordActual", "demo1234", "passwordNueva", "clave-nueva-99")).andExpect(status().isNoContent());

        intentarLogin(email, "clave-nueva-99").andExpect(jsonPath("$.sesion.debeCambiarContrasena", is(false)));
        assertThat(marca()).isFalse();
    }

    @Test
    @DisplayName("con la marca prendida, el login y /api/auth/me la devuelven, el resto de la API sigue andando y cambiarla la apaga")
    void marcaEnLaSesion() throws Exception {
        jdbc.update("update usuario set debe_cambiar_contrasena = true where email = ?", email);
        intentarLogin(email, "demo1234").andExpect(status().isOk())
                .andExpect(jsonPath("$.sesion.debeCambiarContrasena", is(true)));
        getResult("/api/auth/me", token).andExpect(jsonPath("$.debeCambiarContrasena", is(true)));
        getResult("/api/rubros", token).andExpect(status().isOk());

        cambiar(token, json("passwordActual", "demo1234", "passwordNueva", "clave-nueva-99")).andExpect(status().isNoContent());

        getResult("/api/auth/me", token).andExpect(jsonPath("$.debeCambiarContrasena", is(false)));
        assertThat(marca()).isFalse();
    }

    @Test
    @DisplayName("con la actual incorrecta da 400 en passwordActual y el mismo token sigue sirviendo")
    void actualIncorrecta() throws Exception {
        cambiar(token, json("passwordActual", "equivocada", "passwordNueva", "clave-nueva-99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.passwordActual", is("La contraseña actual no es correcta.")));

        getResult("/api/auth/me", token).andExpect(status().isOk());
        intentarLogin(email, "demo1234").andExpect(status().isOk());
    }

    @Test
    @DisplayName("una nueva demasiado corta o larga da 400 en passwordNueva y no cambia nada")
    void nuevaInvalida() throws Exception {
        for (String nueva : new String[] {"corta", "a".repeat(73), "ñ".repeat(37)}) {
            cambiar(token, json("passwordActual", "demo1234", "passwordNueva", nueva))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.passwordNueva").exists());
        }
        intentarLogin(email, "demo1234").andExpect(status().isOk());
    }

    @Test
    @DisplayName("una nueva de solo espacios da 400 en passwordNueva con el mensaje exacto y no cambia nada (W3)")
    void nuevaEnBlanco() throws Exception {
        cambiar(token, json("passwordActual", "demo1234", "passwordNueva", " ".repeat(8)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.passwordNueva", is("La contraseña no puede ser solo espacios.")));

        assertThat(jdbcHash()).isEqualTo(HASH_DEMO);
        intentarLogin(email, "demo1234").andExpect(status().isOk());
    }

    @Test
    @DisplayName("sin passwordActual da 400")
    void actualAusente() throws Exception {
        cambiar(token, json("passwordNueva", "clave-nueva-99"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.passwordActual").exists());
        cambiar(token, json("passwordActual", "  ", "passwordNueva", "clave-nueva-99"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.passwordActual").exists());
    }

    @Test
    @DisplayName("sin passwordNueva da 400")
    void nuevaAusente() throws Exception {
        cambiar(token, json("passwordActual", "demo1234"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.passwordNueva").exists());
    }

    @Test
    @DisplayName("el super admin, sin ferretería, también puede cambiar la suya")
    void superAdmin() throws Exception {
        String admin = unico("admin") + "@cambio.test";
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) values (null, ?, ?, 'Admin', 'SUPER_ADMIN')",
                admin, HASH_DEMO);

        cambiar(login(admin), json("passwordActual", "demo1234", "passwordNueva", "clave-nueva-99"))
                .andExpect(status().isNoContent());

        intentarLogin(admin, "clave-nueva-99").andExpect(status().isOk());
    }

    @Test
    @DisplayName("sin token da 401")
    void sinToken() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/auth/password")
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(json("passwordActual", "demo1234", "passwordNueva", "clave-nueva-99")))
                .andExpect(status().isUnauthorized());
        assertThat(jdbcHash()).isEqualTo(HASH_DEMO);
    }

    private boolean marca() {
        return jdbc.queryForObject("select debe_cambiar_contrasena from usuario where email = ?", Boolean.class, email);
    }

    private String jdbcHash() {
        return jdbc.queryForObject("select password_hash from usuario where email = ?", String.class, email);
    }
}
