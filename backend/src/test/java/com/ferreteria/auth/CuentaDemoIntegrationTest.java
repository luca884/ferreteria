package com.ferreteria.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

class CuentaDemoIntegrationTest extends UsuariosTestBase {

    private static final String BLOQUEO = "Es una cuenta de demostración: no se puede cambiar la contraseña ni el email.";
    private static final String BLOQUEO_CUENTA = "Es una cuenta de demostración: no se puede desactivar ni cambiar su rol.";
    private static final String LISTADO = "/api/usuarios?incluirInactivos=true";
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
    void otroDuenoNoPuedeDesactivarALaDemo() throws Exception {
        marcarDemo();
        String otro = tokenDeOtroDueno();
        var antes = getJson(LISTADO, otro);

        rechazada(cambiarActivo(otro, false), BLOQUEO_CUENTA);

        assertThat(getJson(LISTADO, otro)).isEqualTo(antes);
        getResult("/api/auth/me", token).andExpect(status().isOk());
        intentarLogin(email, ACTUAL).andExpect(status().isOk());
    }

    @Test
    void otroDuenoNoPuedeCambiarElRolDeLaDemo() throws Exception {
        marcarDemo();
        String otro = tokenDeOtroDueno();
        var antes = getJson(LISTADO, otro);

        rechazada(editar(otro, email, "VENDEDOR"), BLOQUEO_CUENTA);

        assertThat(getJson(LISTADO, otro)).isEqualTo(antes);
        getResult("/api/auth/me", token).andExpect(status().isOk());
        intentarLogin(email, ACTUAL).andExpect(status().isOk()).andExpect(jsonPath("$.sesion.rol", is("DUENO")));
    }

    @Test
    void elBloqueoDemoTienePrioridadAlEditarAlPropioUsuarioAunqueSeaElUnicoDueno() throws Exception {
        var antes = getJson(LISTADO, token);
        cambiarActivo(token, false).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status", is(422)))
                .andExpect(jsonPath("$.message", is("No podés desactivar tu propio usuario.")));
        editar(token, email, "VENDEDOR").andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status", is(422)))
                .andExpect(jsonPath("$.message", is("No podés cambiar tu propio rol.")));
        assertThat(getJson(LISTADO, token)).isEqualTo(antes);

        marcarDemo();
        rechazada(cambiarActivo(token, false), BLOQUEO_CUENTA);
        rechazada(editar(token, email, "VENDEDOR"), BLOQUEO_CUENTA);
        assertThat(getJson(LISTADO, token)).isEqualTo(antes);
    }

    @Test
    void sePuedeEditarElNombreDeUnDuenoDemoSinCambiarEmailNiRol() throws Exception {
        marcarDemo();

        editar(token, " " + email.toUpperCase(Locale.ROOT) + " ", "DUENO").andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is(email)))
                .andExpect(jsonPath("$.nombre", is("Nombre Editado")))
                .andExpect(jsonPath("$.rol", is("DUENO")))
                .andExpect(jsonPath("$.topeDescuento").doesNotExist());
        intentarLogin(email, ACTUAL).andExpect(status().isOk())
                .andExpect(jsonPath("$.sesion.nombre", is("Nombre Editado")));
    }

    @Test
    void sePuedeEditarElTopeDeUnVendedorDemoSinCambiarEmailNiRol() throws Exception {
        String vendedor = unico("vendedor") + "@cuenta-demo.test";
        long vendedorId = crearUsuario(ferreteria, vendedor, "Vendedor Original", "VENDEDOR");
        marcarDemo(vendedorId);

        editar(vendedorId, token, vendedor, "VENDEDOR").andExpect(status().isOk())
                .andExpect(jsonPath("$.email", is(vendedor)))
                .andExpect(jsonPath("$.rol", is("VENDEDOR")))
                .andExpect(jsonPath("$.topeDescuento", is(15)));
        intentarLogin(vendedor, ACTUAL).andExpect(status().isOk())
                .andExpect(jsonPath("$.sesion.topeDescuento", is(15), Integer.class));
    }

    @Test
    void sinMarcaOtroDuenoPuedeCambiarElRol() throws Exception {
        String otro = tokenDeOtroDueno();

        editar(otro, email, "VENDEDOR").andExpect(status().isOk()).andExpect(jsonPath("$.rol", is("VENDEDOR")));

        getResult("/api/auth/me", token).andExpect(status().isUnauthorized());
        intentarLogin(email, ACTUAL).andExpect(status().isOk()).andExpect(jsonPath("$.sesion.rol", is("VENDEDOR")));
    }

    @Test
    void sinMarcaOtroDuenoPuedeDesactivarYReactivar() throws Exception {
        String otro = tokenDeOtroDueno();

        cambiarActivo(otro, false).andExpect(status().isOk()).andExpect(jsonPath("$.activo", is(false)));
        getResult("/api/auth/me", token).andExpect(status().isUnauthorized());
        intentarLogin(email, ACTUAL).andExpect(status().isUnauthorized());

        cambiarActivo(otro, true).andExpect(status().isOk()).andExpect(jsonPath("$.activo", is(true)));
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
        marcarDemo(usuarioId);
    }

    private void marcarDemo(long id) {
        jdbc.update("update usuario set cuenta_demo = true where id = ?", id);
    }

    private ResultActions cambiarPropia(String actual) throws Exception {
        return putJson("/api/auth/password", token, json("passwordActual", actual, "passwordNueva", NUEVA));
    }

    private ResultActions editar(String actor, String emailNuevo, String rol) throws Exception {
        return editar(usuarioId, actor, emailNuevo, rol);
    }

    private ResultActions editar(long id, String actor, String emailNuevo, String rol) throws Exception {
        return putJson("/api/usuarios/" + id, actor,
                json("nombre", "Nombre Editado", "email", emailNuevo, "rol", rol, "topeDescuento", 15));
    }

    private ResultActions cambiarActivo(String actor, boolean activo) throws Exception {
        return mvc.perform(patch("/api/usuarios/" + usuarioId + "/activo").header("Authorization", "Bearer " + actor)
                .contentType(MediaType.APPLICATION_JSON).content(json("activo", activo)));
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
        rechazada(respuesta, BLOQUEO);
    }

    private static void rechazada(ResultActions respuesta, String mensaje) throws Exception {
        respuesta.andExpect(status().isForbidden()).andExpect(jsonPath("$.status", is(403)))
                .andExpect(jsonPath("$.message", is(mensaje))).andExpect(jsonPath("$.errors").doesNotExist());
    }

    private Map<String, Object> estadoGuardado() {
        return jdbc.queryForMap("select * from usuario where id = ?", usuarioId);
    }

    private void sinCambios(Map<String, Object> antes) throws Exception {
        assertThat(estadoGuardado()).isEqualTo(antes);
        intentarLogin(email, ACTUAL).andExpect(status().isOk());
    }
}
