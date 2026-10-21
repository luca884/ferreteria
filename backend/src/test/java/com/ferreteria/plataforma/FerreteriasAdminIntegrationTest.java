package com.ferreteria.plataforma;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/** R-101 a R-108 y R-110: el super admin da de alta, edita, suspende y ve el estado de las ferreterías. */
class FerreteriasAdminIntegrationTest extends PlataformaTestBase {

    private String admin;

    @BeforeEach
    void entrar() throws Exception {
        admin = tokenSuperAdmin();
    }

    private String emailNuevo() {
        return unico("dueno") + "@alta.test";
    }

    private JsonNode cuerpo(ResultActions r) throws Exception {
        return objectMapper.readTree(r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private long altaOk(String cuit, String email) throws Exception {
        return cuerpo(alta(admin, altaValida(cuit, email)).andExpect(status().isCreated())).get("id").asLong();
    }

    private long duenoDe(long ferreteria) {
        return jdbc.queryForObject("select min(id) from usuario where ferreteria_id = ? and rol = 'DUENO'", Long.class,
                ferreteria);
    }

    // ---- alta ----

    @Test
    @DisplayName("alta: 201, queda activa con margen 30.00 y redondeo 0.00, CUIT con guiones, y el Dueño entra al mostrador")
    void altaCompleta() throws Exception {
        String cuit = cuitNuevo();
        String email = emailNuevo();

        ResultActions r = alta(admin, altaValida(sinGuiones(cuit), email)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.cuit", is(cuit)))
                .andExpect(jsonPath("$.estado", is("ACTIVA")))
                .andExpect(jsonPath("$.duenosActivos", is(1)))
                .andExpect(jsonPath("$.vendedoresActivos", is(0)))
                .andExpect(jsonPath("$.emailDueno", is(email)));
        long id = cuerpo(r).get("id").asLong();

        assertThat(jdbc.queryForObject("select cuit from ferreteria where id = ?", String.class, id)).isEqualTo(cuit);
        assertThat(jdbc.queryForObject("select margen_defecto_porcentaje from ferreteria where id = ?", String.class, id))
                .isEqualTo("30.00");
        assertThat(jdbc.queryForObject("select redondeo_multiplo from ferreteria where id = ?", String.class, id))
                .isEqualTo("0.00");
        assertThat(jdbc.queryForObject("select count(*) from usuario where ferreteria_id = ? and rol = 'DUENO' and activo = true",
                Long.class, id)).isEqualTo(1L);
        String dueno = login(email);
        getResult("/api/rubros", dueno).andExpect(status().isOk());
        getResult("/api/formas-pago", dueno).andExpect(status().isOk());
    }

    @Test
    @DisplayName("alta: 400 con la clave de cada campo malo")
    void validaciones() throws Exception {
        String cuit = cuitNuevo();
        String email = emailNuevo();
        record Caso(String clave, java.util.function.Consumer<ObjectNode> rompe) {}
        List<Caso> casos = List.of(
                new Caso("razonSocial", c -> c.put("razonSocial", "   ")),
                new Caso("cuit", c -> c.put("cuit", "")),
                new Caso("cuit", c -> c.put("cuit", "30-12345678-2")),
                new Caso("cuit", c -> c.put("cuit", "10-12345678-0")),
                new Caso("condicionIva", c -> c.putNull("condicionIva")),
                new Caso("condicionIva", c -> c.put("condicionIva", "INVENTADA")),
                new Caso("dueno.nombre", c -> ((ObjectNode) c.get("dueno")).put("nombre", " ")),
                new Caso("dueno.email", c -> ((ObjectNode) c.get("dueno")).put("email", "no-es-email")),
                new Caso("dueno.password", c -> ((ObjectNode) c.get("dueno")).put("password", "corta")),
                new Caso("dueno.password", c -> ((ObjectNode) c.get("dueno")).put("password", "        ")));

        for (Caso caso : casos) {
            ObjectNode c = altaValida(cuit, email);
            caso.rompe().accept(c);
            alta(admin, c).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors['" + caso.clave() + "']").exists());
        }
        assertThat(jdbc.queryForObject("select count(*) from ferreteria where cuit = ?", Long.class, cuit)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from usuario where email = ?", Long.class, email)).isZero();
    }

    @Test
    @DisplayName("alta: sin el objeto dueno da 400 en dueno")
    void sinDueno() throws Exception {
        ObjectNode c = altaValida(cuitNuevo(), emailNuevo());
        c.remove("dueno");

        alta(admin, c).andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.dueno").exists());
    }

    @Test
    @DisplayName("alta: un CUIT ya registrado (activa o suspendida) da 409 en cuit y no crea nada")
    void cuitRepetido() throws Exception {
        String cuit = cuitNuevo();
        long id = altaOk(cuit, emailNuevo());
        jdbc.update("update ferreteria set estado = 'SUSPENDIDA' where id = ?", id);
        String emailNuevo = emailNuevo();

        alta(admin, altaValida(sinGuiones(cuit), emailNuevo)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors.cuit").exists());

        assertThat(jdbc.queryForObject("select count(*) from usuario where email = ?", Long.class, emailNuevo)).isZero();
    }

    @Test
    @DisplayName("alta: un email que ya usa otro usuario da 409 en dueno.email y no queda la ferretería")
    void emailRepetido() throws Exception {
        String cuit = cuitNuevo();

        alta(admin, altaValida(cuit, "admin@demo.local")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("Ese email ya está en uso.")))
                .andExpect(jsonPath("$.errors['dueno.email']").exists());

        assertThat(jdbc.queryForObject("select count(*) from ferreteria where cuit = ?", Long.class, cuit)).isZero();
    }

    // ---- editar ----

    @Test
    @DisplayName("PUT cambia razón social e IVA; un cuit en el cuerpo no cambia el guardado")
    void editar() throws Exception {
        String cuit = cuitNuevo();
        long id = altaOk(cuit, emailNuevo());

        putJson(BASE + "/" + id, admin,
                json("razonSocial", "Nueva Razón SA", "condicionIva", "MONOTRIBUTISTA", "cuit", "30-99999999-9"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.razonSocial", is("Nueva Razón SA")))
                .andExpect(jsonPath("$.condicionIva", is("MONOTRIBUTISTA"))).andExpect(jsonPath("$.cuit", is(cuit)));

        assertThat(jdbc.queryForObject("select razon_social from ferreteria where id = ?", String.class, id))
                .isEqualTo("Nueva Razón SA");
        assertThat(jdbc.queryForObject("select condicion_iva from ferreteria where id = ?", String.class, id))
                .isEqualTo("MONOTRIBUTISTA");
        assertThat(jdbc.queryForObject("select cuit from ferreteria where id = ?", String.class, id)).isEqualTo(cuit);
    }

    @Test
    @DisplayName("PUT valida razón social e IVA, y una ferretería inexistente da 404")
    void editarInvalidoOInexistente() throws Exception {
        long id = altaOk(cuitNuevo(), emailNuevo());

        putJson(BASE + "/" + id, admin, json("razonSocial", " ", "condicionIva", "EXENTO"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.razonSocial").exists());
        putJson(BASE + "/" + id, admin, json("razonSocial", "X"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.condicionIva").exists());
        putJson(BASE + "/999999999", admin, json("razonSocial", "X", "condicionIva", "EXENTO"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message", is("Ferretería no encontrada")));
    }

    // ---- suspender y reactivar ----

    @Test
    @DisplayName("suspender corta el token y el login del Dueño; reactivar vuelve todo y no toca ningún dato")
    void suspenderYReactivar() throws Exception {
        String email = emailNuevo();
        long id = altaOk(cuitNuevo(), email);
        String dueno = login(email);
        long usuariosAntes = jdbc.queryForObject("select count(*) from usuario where ferreteria_id = ?", Long.class, id);
        long formasAntes = jdbc.queryForObject("select count(*) from forma_pago where ferreteria_id = ?", Long.class, id);
        getResult("/api/formas-pago", dueno).andExpect(status().isOk());

        patchJson(BASE + "/" + id + "/activo", admin, json("activo", false)).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado", is("SUSPENDIDA")));
        patchJson(BASE + "/" + id + "/activo", admin, json("activo", false)).andExpect(status().isOk());

        getResult("/api/rubros", dueno).andExpect(status().isUnauthorized());
        intentarLogin(email, "demo1234").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is("Tu ferretería está suspendida. Contactá al proveedor.")));

        patchJson(BASE + "/" + id + "/activo", admin, json("activo", true)).andExpect(status().isOk())
                .andExpect(jsonPath("$.estado", is("ACTIVA")));
        patchJson(BASE + "/" + id + "/activo", admin, json("activo", true)).andExpect(status().isOk());

        getResult("/api/rubros", dueno).andExpect(status().isOk());
        intentarLogin(email, "demo1234").andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from usuario where ferreteria_id = ?", Long.class, id))
                .isEqualTo(usuariosAntes);
        assertThat(jdbc.queryForObject("select count(*) from forma_pago where ferreteria_id = ?", Long.class, id))
                .isGreaterThanOrEqualTo(formasAntes);
    }

    @Test
    @DisplayName("PATCH activo sin el campo da 400, y sobre una ferretería inexistente 404")
    void cambiarActivoInvalido() throws Exception {
        long id = altaOk(cuitNuevo(), emailNuevo());

        patchJson(BASE + "/" + id + "/activo", admin, "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.activo").exists());
        patchJson(BASE + "/999999999/activo", admin, json("activo", false)).andExpect(status().isNotFound());
    }

    // ---- reset de la contraseña del Dueño ----

    @Test
    @DisplayName("reset del Dueño: 204 y entra con la nueva")
    void resetDelDueno() throws Exception {
        String email = emailNuevo();
        long id = altaOk(cuitNuevo(), email);

        putJson(BASE + "/" + id + "/duenos/" + duenoDe(id) + "/password", admin, json("password", "nueva-clave-1"))
                .andExpect(status().isNoContent());

        intentarLogin(email, "nueva-clave-1").andExpect(status().isOk());
        intentarLogin(email, "demo1234").andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("reset: un Vendedor, el Dueño de otra ferretería o un id inexistente dan 404; una contraseña mala, 400 en password")
    void resetInvalido() throws Exception {
        long id = altaOk(cuitNuevo(), emailNuevo());
        long otra = altaOk(cuitNuevo(), emailNuevo());
        long vendedor = crearUsuario(id, emailNuevo(), "Vendedor", "VENDEDOR");
        String clave = json("password", "nueva-clave-1");

        putJson(BASE + "/" + id + "/duenos/" + vendedor + "/password", admin, clave).andExpect(status().isNotFound());
        putJson(BASE + "/" + id + "/duenos/" + duenoDe(otra) + "/password", admin, clave).andExpect(status().isNotFound());
        putJson(BASE + "/" + id + "/duenos/999999999/password", admin, clave).andExpect(status().isNotFound());
        putJson(BASE + "/999999999/duenos/" + duenoDe(id) + "/password", admin, clave).andExpect(status().isNotFound());
        putJson(BASE + "/" + id + "/duenos/" + duenoDe(id) + "/password", admin, json("password", "corta"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.password").exists());
        putJson(BASE + "/" + id + "/duenos/" + duenoDe(id) + "/password", admin, json("password", "        "))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors.password").exists());
    }

    // ---- roles ----

    @Test
    @DisplayName("el Dueño y el Vendedor reciben 403 en los 5 endpoints aun con el cuerpo inválido; sin token, 401")
    void soloElSuperAdmin() throws Exception {
        String dueno = login("dueno@tornillo.demo");
        String vendedor = login("vendedor@tornillo.demo");

        for (String token : List.of(dueno, vendedor)) {
            getResult(BASE, token).andExpect(status().isForbidden());
            postJson(BASE, token, "{}").andExpect(status().isForbidden());
            putJson(BASE + "/1", token, "{}").andExpect(status().isForbidden());
            patchJson(BASE + "/1/activo", token, "{}").andExpect(status().isForbidden());
            putJson(BASE + "/1/duenos/1/password", token, "{}").andExpect(status().isForbidden());
        }
        mvc.perform(get(BASE)).andExpect(status().isUnauthorized());
        postJson(BASE, "token-roto", "{}").andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("el super admin recibe 403 en /api/usuarios")
    void superAdminNoGestionaUsuarios() throws Exception {
        getResult("/api/usuarios", admin).andExpect(status().isForbidden());
    }

    // ---- estado general ----

    @Test
    @DisplayName("estado general: incluye suspendidas, cuenta por rol, el Dueño es el de menor id y no hay ultimoAcceso")
    void estadoGeneral() throws Exception {
        long fid = crearFerreteria("Estado general");
        crearUsuario(fid, unico("d1") + "@estado.test", "Dueño uno", "DUENO");
        String emailAntiguo = jdbc.queryForObject("select email from usuario where ferreteria_id = ?", String.class, fid);
        crearUsuario(fid, unico("d2") + "@estado.test", "Dueño dos", "DUENO");
        crearUsuario(fid, unico("v1") + "@estado.test", "Vendedor", "VENDEDOR");
        long inactivo = crearUsuario(fid, unico("v2") + "@estado.test", "Vendedor inactivo", "VENDEDOR");
        jdbc.update("update usuario set activo = false where id = ?", inactivo);
        jdbc.update("update ferreteria set estado = 'SUSPENDIDA' where id = ?", fid);

        JsonNode estado = objectMapper.readTree(getResult(BASE, admin).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8));

        JsonNode item = null;
        for (JsonNode f : estado.get("ferreterias")) {
            if (f.get("id").asLong() == fid) {
                item = f;
            }
        }
        assertThat(item).as("la ferretería suspendida está en la lista").isNotNull();
        assertThat(item.get("estado").asText()).isEqualTo("SUSPENDIDA");
        assertThat(item.get("duenosActivos").asInt()).isEqualTo(2);
        assertThat(item.get("vendedoresActivos").asInt()).isEqualTo(1);
        assertThat(item.get("emailDueno").asText()).isEqualTo(emailAntiguo);
        assertThat(item.get("duenoId").asLong()).isEqualTo(duenoDe(fid));
        assertThat(item.has("ultimoAcceso")).isFalse();
        assertThat(estado.get("activas").asLong()).isEqualTo(
                jdbc.queryForObject("select count(*) from ferreteria where estado = 'ACTIVA'", Long.class));
        assertThat(estado.get("suspendidas").asLong()).isEqualTo(
                jdbc.queryForObject("select count(*) from ferreteria where estado = 'SUSPENDIDA'", Long.class));
        assertThat(estado.get("ferreterias").size()).isEqualTo(
                jdbc.queryForObject("select count(*) from ferreteria", Long.class).intValue());
    }

    @Test
    @DisplayName("estado general: orden estable por razón social, el mismo de la base")
    void ordenPorRazonSocial() throws Exception {
        JsonNode lista = getJson(BASE, admin).get("ferreterias");

        List<Long> ids = new java.util.ArrayList<>();
        lista.forEach(f -> ids.add(f.get("id").asLong()));
        assertThat(ids).containsExactlyElementsOf(
                jdbc.queryForList("select id from ferreteria order by razon_social, id", Long.class));
    }
}
