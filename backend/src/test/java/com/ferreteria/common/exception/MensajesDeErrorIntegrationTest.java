package com.ferreteria.common.exception;

import com.ferreteria.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Los mensajes de error que ve el usuario van en español rioplatense y con tildes.
 */
class MensajesDeErrorIntegrationTest extends IntegrationTestBase {

    @Test
    void contrasenaIncorrecta() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"dueno@tornillo.demo\",\"password\":\"otra\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message", is("Email o contraseña incorrectos")));
    }

    @Test
    void loginConDatosInvalidos() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"no-es-un-email\",\"password\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("Datos inválidos")))
                .andExpect(jsonPath("$.errors.email", is("El email no es válido")))
                .andExpect(jsonPath("$.errors.password", is("La contraseña es obligatoria")));
    }

    @Test
    void sinFerreteria() throws Exception {
        getResult("/api/rubros", login("admin@demo.local"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", is("Este recurso requiere una ferretería")));
    }

    @Test
    void parametroInvalido() throws Exception {
        getResult("/api/rubros/abc", login("dueno@tornillo.demo"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("Parámetro inválido: id")));
    }

    @Test
    void metodoNoPermitido() throws Exception {
        putJson("/api/rubros", login("dueno@tornillo.demo"), "{}")
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.message", is("Método no permitido")));
    }

    @Test
    void cuerpoIlegible() throws Exception {
        postJson("/api/rubros", login("dueno@tornillo.demo"), "{no es json")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("El cuerpo del pedido no es válido")));
    }
}
