package com.ferreteria.auth;

import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CU-07: el tope de descuento de cada usuario lo carga el Dueño en Usuarios. El Dueño no tiene tope. */
class UsuariosTopeDescuentoIntegrationTest extends UsuariosTestBase {

    private long ferreteria;
    private String dueno;

    @BeforeEach
    void armar() throws Exception {
        ferreteria = crearFerreteria("Tope");
        String email = unico("dueno") + "@tope.test";
        crearUsuario(ferreteria, email, "Dueño Tope", "DUENO");
        dueno = login(email);
    }

    private long crearVendedor(String email, String tope) throws Exception {
        String cuerpo = postJson("/api/usuarios", dueno, json("nombre", "Vendedor", "email", email, "rol", "VENDEDOR",
                "password", "demo1234", "topeDescuento", tope))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(cuerpo).get("id").asLong();
    }

    private BigDecimal topeGuardado(long id) {
        return jdbc.queryForObject("select tope_descuento from usuario where id = ?", BigDecimal.class, id);
    }

    @Test
    @DisplayName("el alta guarda el tope, la lista lo muestra y la sesión del vendedor lo trae")
    void altaConTope() throws Exception {
        String email = unico("v") + "@tope.test";
        long id = crearVendedor(email, "12.5");

        org.assertj.core.api.Assertions.assertThat(topeGuardado(id)).isEqualByComparingTo("12.5");
        getResult("/api/usuarios", dueno).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == " + id + ")].topeDescuento").value(12.5));
        getResult("/api/auth/me", login(email)).andExpect(status().isOk())
                .andExpect(jsonPath("$.topeDescuento", is(12.5)));
    }

    @Test
    @DisplayName("sin el campo, el usuario queda sin tope (null en la respuesta y en la sesión)")
    void altaSinTope() throws Exception {
        String email = unico("v") + "@tope.test";
        String cuerpo = postJson("/api/usuarios", dueno, json("nombre", "Vendedor", "email", email, "rol", "VENDEDOR",
                "password", "demo1234")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.topeDescuento", nullValue()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        org.assertj.core.api.Assertions.assertThat(topeGuardado(objectMapper.readTree(cuerpo).get("id").asLong()))
                .isNull();
        getResult("/api/auth/me", login(email)).andExpect(jsonPath("$.topeDescuento", nullValue()));
    }

    @Test
    @DisplayName("PUT cambia el tope, y vacío lo saca")
    void editarTope() throws Exception {
        String email = unico("v") + "@tope.test";
        long id = crearVendedor(email, "10");

        putJson("/api/usuarios/" + id, dueno, json("nombre", "Vendedor", "email", email, "rol", "VENDEDOR",
                "topeDescuento", "0")).andExpect(status().isOk()).andExpect(jsonPath("$.topeDescuento", is(0)));
        org.assertj.core.api.Assertions.assertThat(topeGuardado(id)).isEqualByComparingTo("0");

        putJson("/api/usuarios/" + id, dueno, json("nombre", "Vendedor", "email", email, "rol", "VENDEDOR",
                "topeDescuento", null)).andExpect(status().isOk()).andExpect(jsonPath("$.topeDescuento", nullValue()));
        org.assertj.core.api.Assertions.assertThat(topeGuardado(id)).isNull();
    }

    @Test
    @DisplayName("el tope fuera de 0 a 99,99 o con más de 2 decimales da 400 en topeDescuento y no se crea nada")
    void topeInvalido() throws Exception {
        for (String tope : new String[] {"100", "-1", "12.345"}) {
            String email = unico("inv") + "@tope.test";
            postJson("/api/usuarios", dueno, json("nombre", "Vendedor", "email", email, "rol", "VENDEDOR",
                    "password", "demo1234", "topeDescuento", tope))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.topeDescuento",
                            is("El tope de descuento va de 0 a 99,99 %, con hasta 2 decimales.")));
            org.assertj.core.api.Assertions.assertThat(
                    jdbc.queryForObject("select count(*) from usuario where email = ?", Integer.class, email)).isZero();
        }
    }

    @Test
    @DisplayName("un Dueño no guarda tope: ni al crearlo ni al pasar un Vendedor con tope a Dueño")
    void duenoSinTope() throws Exception {
        String emailDueno = unico("d") + "@tope.test";
        String cuerpo = postJson("/api/usuarios", dueno, json("nombre", "Otro Dueño", "email", emailDueno, "rol",
                "DUENO", "password", "demo1234", "topeDescuento", "5")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.topeDescuento", nullValue()))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        org.assertj.core.api.Assertions.assertThat(topeGuardado(objectMapper.readTree(cuerpo).get("id").asLong()))
                .isNull();

        String email = unico("v") + "@tope.test";
        long id = crearVendedor(email, "10");
        putJson("/api/usuarios/" + id, dueno, json("nombre", "Vendedor", "email", email, "rol", "DUENO",
                "topeDescuento", "10")).andExpect(status().isOk()).andExpect(jsonPath("$.topeDescuento", nullValue()));
        org.assertj.core.api.Assertions.assertThat(topeGuardado(id)).isNull();
    }

    @Test
    @DisplayName("el Dueño de otra ferretería no puede cambiar el tope: 404 y el tope queda igual")
    void otraFerreteria() throws Exception {
        String email = unico("v") + "@tope.test";
        long id = crearVendedor(email, "10");
        long otra = crearFerreteria("TopeOtra");
        String emailOtro = unico("otro") + "@tope.test";
        crearUsuario(otra, emailOtro, "Dueño Otro", "DUENO");

        putJson("/api/usuarios/" + id, login(emailOtro), json("nombre", "Vendedor", "email", email, "rol", "VENDEDOR",
                "topeDescuento", "50")).andExpect(status().isNotFound());
        org.assertj.core.api.Assertions.assertThat(topeGuardado(id)).isEqualByComparingTo("10");
    }
}
