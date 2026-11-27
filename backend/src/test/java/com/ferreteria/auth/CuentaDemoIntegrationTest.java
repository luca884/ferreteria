package com.ferreteria.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

class CuentaDemoIntegrationTest extends UsuariosTestBase {

    private static final String BLOQUEO = "Es una cuenta de demostración: no se puede cambiar la contraseña ni el email.";
    private static final String ACTUAL = "demo1234";
    private static final String NUEVA = "clave-propia-99";
    private static final String RESETEADA = "clave-reseteada-99";

    private long ferreteria;
    private long usuarioId;
    private String email;
    private String token;

    @BeforeEach
    void armar() throws Exception {
        ferreteria = crearFerreteria("Protección demo");
        email = unico("dueno") + "@cuenta-demo.test";
        usuarioId = crearUsuario(ferreteria, email, "Dueño Original", "DUENO");
        token = login(email);
    }

    @Test
    void demoNoPuedeCambiarLaContrasenaPropia() throws Exception {
        marcarDemo();
        var antes = estadoGuardado();

        rechazada(cambiarPropia(ACTUAL));
        // Tampoco se verifica la contraseña actual: insistir no consume intentos ni termina en 429.
        for (int i = 0; i < 10; i++) {
            rechazada(cambiarPropia("actual-incorrecta"));
        }

        sinCambios(antes);
    }

    @Test
    void noSePuedeEditarElEmailDeLaDemo() throws Exception {
        marcarDemo();
        var antes = estadoGuardado();

        rechazada(editar(token, unico("nuevo") + "@cuenta-demo.test", "DUENO"));

        sinCambios(antes);
    }

    @Test
    void noSePuedeResetearLaContrasenaDeLaDemo() throws Exception {
        marcarDemo();
        var antes = estadoGuardado();

        rechazada(resetear(token));
        rechazada(resetear(tokenDeOtroDueno()));

        sinCambios(antes);
    }

    @Test
    void conElMismoEmailSePuedeEditarLaDemoSinSaltarseLasReglasDelRol() throws Exception {
        marcarDemo();
        var antes = estadoGuardado();
        editar(token, email, "VENDEDOR").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", is("No podés cambiar tu propio rol.")));
        sinCambios(antes);

        editar(tokenDeOtroDueno(), " " + email.toUpperCase(Locale.ROOT) + " ", "VENDEDOR")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is(email)))
                .andExpect(jsonPath("$.nombre", is("Nombre Editado")))
                .andExpect(jsonPath("$.rol", is("VENDEDOR")))
                .andExpect(jsonPath("$.topeDescuento", is(15)));
        intentarLogin(email, ACTUAL).andExpect(status().isOk());
    }

    @Test
    void laMarcaDemoSaleEnElLoginYEnLaSesionActual() throws Exception {
        marcarDemo();

        intentarLogin(email, ACTUAL).andExpect(status().isOk()).andExpect(jsonPath("$.sesion.cuentaDemo", is(true)));
        getResult("/api/auth/me", token).andExpect(status().isOk()).andExpect(jsonPath("$.cuentaDemo", is(true)));
    }

    @Test
    void sinMarcaSePuedenCambiarContrasenaEmailYResetear() throws Exception {
        cambiarPropia(ACTUAL).andExpect(status().isNoContent()).andExpect(content().string(""));
        intentarLogin(email, ACTUAL).andExpect(status().isUnauthorized());
        intentarLogin(email, NUEVA).andExpect(status().isOk());

        String nuevoEmail = unico("nuevo") + "@cuenta-demo.test";
        editar(token, nuevoEmail, "DUENO").andExpect(status().isOk()).andExpect(jsonPath("$.email", is(nuevoEmail)));
        intentarLogin(email, NUEVA).andExpect(status().isUnauthorized());
        intentarLogin(nuevoEmail, NUEVA).andExpect(status().isOk());

        resetear(token).andExpect(status().isNoContent()).andExpect(content().string(""));
        intentarLogin(nuevoEmail, NUEVA).andExpect(status().isUnauthorized());
        intentarLogin(nuevoEmail, RESETEADA).andExpect(status().isOk()).andExpect(jsonPath("$.sesion.cuentaDemo", is(false)));
        getResult("/api/auth/me", token).andExpect(status().isOk()).andExpect(jsonPath("$.cuentaDemo", is(false)));
    }

    private void marcarDemo() {
        jdbc.update("update usuario set cuenta_demo = true where id = ?", usuarioId);
    }

    private ResultActions cambiarPropia(String actual) throws Exception {
        return putJson("/api/auth/password", token, json("passwordActual", actual, "passwordNueva", NUEVA));
    }

    private ResultActions editar(String actor, String emailNuevo, String rol) throws Exception {
        return putJson("/api/usuarios/" + usuarioId, actor,
                json("nombre", "Nombre Editado", "email", emailNuevo, "rol", rol, "topeDescuento", 15));
    }

    private ResultActions resetear(String actor) throws Exception {
        return putJson("/api/usuarios/" + usuarioId + "/password", actor, json("password", RESETEADA));
    }

    private String tokenDeOtroDueno() throws Exception {
        String otroEmail = unico("otro") + "@cuenta-demo.test";
        crearUsuario(ferreteria, otroEmail, "Otro Dueño", "DUENO");
        return login(otroEmail);
    }

    private static void rechazada(ResultActions respuesta) throws Exception {
        respuesta.andExpect(status().isForbidden()).andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.message", is(BLOQUEO))).andExpect(jsonPath("$.errors").doesNotExist());
    }

    private Map<String, Object> estadoGuardado() {
        return jdbc.queryForMap("select * from usuario where id = ?", usuarioId);
    }

    private void sinCambios(Map<String, Object> antes) throws Exception {
        assertThat(estadoGuardado()).isEqualTo(antes);
        intentarLogin(email, ACTUAL).andExpect(status().isOk());
    }
}
