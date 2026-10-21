package com.ferreteria.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/** CU-02: el Dueño gestiona los usuarios de su ferretería (R-002 a R-005, R-007 a R-011). */
class UsuariosIntegrationTest extends UsuariosTestBase {

    private long ferreteria;
    private long duenoId;
    private String emailDueno;
    private String dueno;

    @BeforeEach
    void armar() throws Exception {
        ferreteria = crearFerreteria("Usuarios");
        emailDueno = unico("dueno") + "@usuarios.test";
        duenoId = crearUsuario(ferreteria, emailDueno, "Dueño Prueba", "DUENO");
        dueno = login(emailDueno);
    }

    private ResultActions crear(String nombre, String email, String rol, String password) throws Exception {
        return postJson("/api/usuarios", dueno, json("nombre", nombre, "email", email, "rol", rol, "password", password));
    }

    private long crearVendedor(String email) throws Exception {
        String cuerpo = crear("Vendedor " + email, email, "VENDEDOR", "demo1234")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(cuerpo).get("id").asLong();
    }

    private ResultActions activar(long id, boolean activo, String token) throws Exception {
        return mvc.perform(patch("/api/usuarios/" + id + "/activo").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(json("activo", activo)));
    }

    // ---- R-005: el flujo de cierre ----

    @Test
    @DisplayName("crear un Vendedor, entra, se lo desactiva (su token da 401 y no puede entrar) y se lo reactiva")
    void flujoDeCierre() throws Exception {
        String email = unico("vendedor") + "@usuarios.test";
        long id = crearVendedor(email);
        String tokenVendedor = login(email);
        getResult("/api/rubros", tokenVendedor).andExpect(status().isOk());

        activar(id, false, dueno).andExpect(status().isOk()).andExpect(jsonPath("$.activo", is(false)));

        getResult("/api/rubros", tokenVendedor).andExpect(status().isUnauthorized());
        intentarLogin(email, "demo1234").andExpect(status().isUnauthorized());

        activar(id, true, dueno).andExpect(status().isOk()).andExpect(jsonPath("$.activo", is(true)));
        login(email);
    }

    // ---- listar ----

    @Test
    @DisplayName("la lista trae solo los activos, ordenados por nombre, y sin el hash de la contraseña")
    void listaActivos() throws Exception {
        long zeta = crearUsuario(ferreteria, unico("z") + "@usuarios.test", "Zeta", "VENDEDOR");
        crearUsuario(ferreteria, unico("a") + "@usuarios.test", "Alfa", "VENDEDOR");
        long baja = crearUsuario(ferreteria, unico("b") + "@usuarios.test", "Baja", "VENDEDOR");
        jdbc.update("update usuario set activo = false where id = ?", baja);

        String cuerpo = getResult("/api/usuarios", dueno).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        JsonNode lista = objectMapper.readTree(cuerpo);

        assertThat(nombres(lista)).containsExactly("Alfa", "Dueño Prueba", "Zeta");
        assertThat(cuerpo).doesNotContain("passwordHash").doesNotContain("password");
        assertThat(lista.get(0).fieldNames()).toIterable().containsExactlyInAnyOrder("id", "nombre", "email", "rol", "activo");
        assertThat(lista.findValuesAsText("id")).contains(String.valueOf(zeta)).doesNotContain(String.valueOf(baja));
    }

    @Test
    @DisplayName("con incluirInactivos=true trae también los desactivados")
    void listaConInactivos() throws Exception {
        long baja = crearUsuario(ferreteria, unico("b") + "@usuarios.test", "Baja", "VENDEDOR");
        jdbc.update("update usuario set activo = false where id = ?", baja);

        JsonNode lista = getJson("/api/usuarios?incluirInactivos=true", dueno);

        assertThat(nombres(lista)).containsExactly("Baja", "Dueño Prueba");
    }

    // ---- crear: validaciones ----

    @Test
    @DisplayName("nombre vacío, email inválido y rol ausente dan 400 por campo")
    void validacionesDeCampo() throws Exception {
        crear("  ", "ok@usuarios.test", "VENDEDOR", "demo1234")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.nombre").exists());
        crear("Ana", "no-es-un-mail", "VENDEDOR", "demo1234")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.email").exists());
        crear("Ana", "ok@usuarios.test", null, "demo1234")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.rol").exists());
    }

    @Test
    @DisplayName("el rol SUPER_ADMIN y un rol desconocido dan 400 en rol, y el mensaje no nombra a SUPER_ADMIN")
    void rolInvalido() throws Exception {
        crear("Ana", unico("a") + "@usuarios.test", "SUPER_ADMIN", "demo1234")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.rol", is("El rol tiene que ser Dueño o Vendedor.")));
        crear("Ana", unico("a") + "@usuarios.test", "GERENTE", "demo1234")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.rol", is("El rol tiene que ser Dueño o Vendedor.")));
    }

    @Test
    @DisplayName("la contraseña de 7 caracteres, de 73 y de 37 «ñ» (74 bytes) dan 400 en password; no se crea nada")
    void contrasenaInvalida() throws Exception {
        for (String password : List.of("a".repeat(7), "a".repeat(73), "ñ".repeat(37))) {
            crear("Ana", unico("a") + "@usuarios.test", "VENDEDOR", password)
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.password").exists());
        }
        assertThat(jdbc.queryForObject("select count(*) from usuario where ferreteria_id = ?", Long.class, ferreteria))
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("la contraseña de 72 caracteres es válida y el usuario entra con ella")
    void contrasenaDeSetentaYDos() throws Exception {
        String email = unico("largo") + "@usuarios.test";
        crear("Largo", email, "VENDEDOR", "a".repeat(72)).andExpect(status().isCreated());

        intentarLogin(email, "a".repeat(72)).andExpect(status().isOk());
    }

    // ---- crear: ferretería del JWT, email repetido ----

    @Test
    @DisplayName("el usuario nuevo queda en la ferretería del JWT aunque el cuerpo traiga otra")
    void ferreteriaDelJwt() throws Exception {
        long otra = crearFerreteria("Otra");
        String email = unico("nuevo") + "@usuarios.test";

        postJson("/api/usuarios", dueno, "{\"nombre\":\"Nuevo\",\"email\":\"" + email + "\",\"rol\":\"VENDEDOR\","
                + "\"password\":\"demo1234\",\"ferreteriaId\":" + otra + "}").andExpect(status().isCreated());

        assertThat(jdbc.queryForObject("select ferreteria_id from usuario where email = ?", Long.class, email))
                .isEqualTo(ferreteria);
    }

    @Test
    @DisplayName("el email se guarda recortado y en minúsculas")
    void emailNormalizado() throws Exception {
        String email = unico("Mixto") + "@Usuarios.TEST";

        crear("Mixto", "  " + email + " ", "VENDEDOR", "demo1234").andExpect(status().isCreated())
                .andExpect(jsonPath("$.email", is(email.toLowerCase())));
    }

    @Test
    @DisplayName("un email de otra ferretería (en mayúsculas y con espacios) da 409 en email, sin revelar de quién es")
    void emailDeOtraFerreteria() throws Exception {
        long otra = crearFerreteria("Otra");
        String ajeno = unico("ajeno") + "@usuarios.test";
        crearUsuario(otra, ajeno, "Ajeno", "VENDEDOR");

        crear("Copia", "  " + ajeno.toUpperCase() + "  ", "VENDEDOR", "demo1234")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.email", is("Ese email ya está en uso.")));
    }

    // ---- editar ----

    @Test
    @DisplayName("PUT cambia nombre, email y rol; con el propio email no choca")
    void editar() throws Exception {
        String email = unico("edit") + "@usuarios.test";
        long id = crearVendedor(email);

        putJson("/api/usuarios/" + id, dueno, json("nombre", "Otro Nombre", "email", email, "rol", "VENDEDOR"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nombre", is("Otro Nombre")));
        putJson("/api/usuarios/" + id, dueno, json("nombre", "Otro Nombre", "email", unico("nuevo") + "@usuarios.test",
                "rol", "DUENO")).andExpect(status().isOk()).andExpect(jsonPath("$.rol", is("DUENO")));
    }

    @Test
    @DisplayName("PUT con el email de otro usuario da 409")
    void editarConEmailDeOtro() throws Exception {
        long id = crearVendedor(unico("v") + "@usuarios.test");

        putJson("/api/usuarios/" + id, dueno, json("nombre", "X", "email", emailDueno, "rol", "VENDEDOR"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.errors.email").exists());
    }

    @Test
    @DisplayName("el Dueño puede editar su propio nombre y email")
    void editarElPropioNombre() throws Exception {
        putJson("/api/usuarios/" + duenoId, dueno, json("nombre", "Nombre Nuevo", "email", emailDueno, "rol", "DUENO"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.nombre", is("Nombre Nuevo")));
    }

    // ---- auto-protección ----

    @Test
    @DisplayName("el Dueño no puede desactivarse (422) y sigue pudiendo operar")
    void noSePuedeDesactivar() throws Exception {
        activar(duenoId, false, dueno).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", is("No podés desactivar tu propio usuario.")));

        getResult("/api/usuarios", dueno).andExpect(status().isOk());
    }

    @Test
    @DisplayName("el Dueño no puede cambiarse el rol (422)")
    void noSePuedeCambiarElRol() throws Exception {
        putJson("/api/usuarios/" + duenoId, dueno, json("nombre", "Dueño Prueba", "email", emailDueno, "rol", "VENDEDOR"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message", is("No podés cambiar tu propio rol.")));

        assertThat(jdbc.queryForObject("select rol from usuario where id = ?", String.class, duenoId)).isEqualTo("DUENO");
    }

    @Test
    @DisplayName("con dos Dueños, uno puede desactivar al otro mientras quede él")
    void unDuenoDesactivaAlOtro() throws Exception {
        long otro = crearUsuario(ferreteria, unico("dueno2") + "@usuarios.test", "Otro Dueño", "DUENO");

        activar(otro, false, dueno).andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select count(*) from usuario where ferreteria_id = ? and rol = 'DUENO' and activo = true",
                Long.class, ferreteria)).isEqualTo(1L);
    }

    // ---- R-004: cambio de rol ----

    @Test
    @DisplayName("al cambiarle el rol a un Vendedor, su token viejo da 401")
    void cambioDeRolCortaElToken() throws Exception {
        String email = unico("v") + "@usuarios.test";
        long id = crearVendedor(email);
        String tokenViejo = login(email);

        putJson("/api/usuarios/" + id, dueno, json("nombre", "Ahora Dueño", "email", email, "rol", "DUENO"))
                .andExpect(status().isOk());

        getResult("/api/rubros", tokenViejo).andExpect(status().isUnauthorized());
        getResult("/api/rubros", login(email)).andExpect(status().isOk());
    }

    // ---- resetear contraseña ----

    @Test
    @DisplayName("resetear la contraseña: entra con la nueva y la anterior ya no sirve")
    void resetear() throws Exception {
        String email = unico("v") + "@usuarios.test";
        long id = crearVendedor(email);

        putJson("/api/usuarios/" + id + "/password", dueno, json("password", "clave-nueva-99"))
                .andExpect(status().isNoContent());

        intentarLogin(email, "clave-nueva-99").andExpect(status().isOk());
        intentarLogin(email, "demo1234").andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("resetear con una contraseña inválida da 400 en password")
    void resetearInvalida() throws Exception {
        long id = crearVendedor(unico("v") + "@usuarios.test");

        putJson("/api/usuarios/" + id + "/password", dueno, json("password", "corta"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.password").exists());
    }

    @Test
    @DisplayName("PATCH sin el campo activo da 400")
    void activoAusente() throws Exception {
        long id = crearVendedor(unico("v") + "@usuarios.test");

        mvc.perform(patch("/api/usuarios/" + id + "/activo").header("Authorization", "Bearer " + dueno)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.activo").exists());
    }
}
