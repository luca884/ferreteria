package com.ferreteria.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.support.CodigoDeLaApp;
import com.ferreteria.support.RelojControlable;
import com.ferreteria.support.RelojControlableConfig;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Base de los tests del login del super admin con el código de la app (totp-super-admin). Cada test crea su propio super
 * admin, con demo1234 y sin el código activado: la base se comparte entre tests y no se revierte, y {@code admin@demo.local}
 * lo usan los demás tests por {@code IntegrationTestBase.login} (S9).
 *
 * <p>El reloj es el {@link RelojControlable}: el código de la app, el vencimiento del {@code tokenTotp} y el límite de
 * intentos lo leen de él. El límite de códigos mal por usuario es el de producción ({@code max-fallos-por-email-e-ip=5},
 * S5d); los del login por email y por IP siguen altos como en el resto de la suite, porque todo MockMvc viene de 127.0.0.1
 * y con el reloj quieto la ventana de la IP no cerraría nunca.
 */
@Import(RelojControlableConfig.class)
@TestPropertySource(properties = "app.login.max-fallos-por-email-e-ip=5")
abstract class SegundoFactorTestBase extends UsuariosTestBase {

    /** En el borde de un paso de 30 s. */
    static final Instant AHORA = Instant.parse("2026-11-09T15:00:00Z");

    static final String TOTP_VENCIDO = "Pasaron más de 5 minutos. Volvé a ingresar tu email y contraseña.";
    static final String CODIGO_INCORRECTO = "El código no es correcto. Fijate en la app y probá de nuevo.";

    @Autowired
    RelojControlable reloj;

    @BeforeEach
    void fijarElReloj() {
        reloj.fijar(AHORA);
    }

    /** Un super admin nuevo, con la contraseña demo1234 y sin el código de la app; devuelve su email. */
    String crearSuperAdmin() {
        String email = unico("admin") + "@totp.test";
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (null, ?, ?, 'Admin', 'SUPER_ADMIN')", email, HASH_DEMO);
        return email;
    }

    JsonNode cuerpo(ResultActions respuesta) throws Exception {
        return objectMapper.readTree(respuesta.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** El login con email y contraseña del super admin: 200 con el segundo paso. */
    JsonNode primerPaso(String email) throws Exception {
        return cuerpo(intentarLogin(email, "demo1234").andExpect(status().isOk()));
    }

    String tokenTotp(String email) throws Exception {
        JsonNode tokenTotp = primerPaso(email).get("tokenTotp");
        assertThat(tokenTotp.isTextual()).as("el login trae el tokenTotp").isTrue();
        return tokenTotp.asText();
    }

    ResultActions alta(String tokenTotp) throws Exception {
        return mvc.perform(post("/api/auth/totp/alta").contentType(MediaType.APPLICATION_JSON)
                .content(json("tokenTotp", tokenTotp)));
    }

    ResultActions verificar(String tokenTotp, String codigo) throws Exception {
        return mvc.perform(post("/api/auth/totp/verificar").contentType(MediaType.APPLICATION_JSON)
                .content(json("tokenTotp", tokenTotp, "codigo", codigo)));
    }

    /** La clave del alta (200) con ese tokenTotp. */
    String clave(String tokenTotp) throws Exception {
        return cuerpo(alta(tokenTotp).andExpect(status().isOk())).get("clave").asText();
    }

    /** El código que muestra la app para esa clave a la hora del reloj. */
    String codigo(String clave) {
        return CodigoDeLaApp.para(clave, reloj.instant());
    }

    /** Un código de 6 números que la app no muestra a la hora del reloj. */
    String incorrecto(String clave) {
        return CodigoDeLaApp.incorrecto(clave, reloj.instant());
    }

    /** Login, alta y primer código: el super admin queda con el código de la app activado. Devuelve la clave. */
    String activar(String email) throws Exception {
        String tokenTotp = tokenTotp(email);
        String clave = clave(tokenTotp);
        verificar(tokenTotp, codigo(clave)).andExpect(status().isOk());
        return clave;
    }

    /** El campo vale null o no está, como pide el contrato para lo que no corresponde. */
    static boolean nuloOAusente(JsonNode nodo, String campo) {
        return !nodo.hasNonNull(campo);
    }
}
