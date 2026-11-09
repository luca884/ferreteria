package com.ferreteria.auth;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Límite de códigos mal del super admin (totp-super-admin, S5d), con el límite de producción por usuario
 * ({@link SegundoFactorTestBase}): 5 seguidos lo bloquean con la escalera de {@code LimiteDeIntentos}.
 */
class SegundoFactorLimiteIntegrationTest extends SegundoFactorTestBase {

    private static final String AVISO_UN_MINUTO = "Demasiados intentos. Probá de nuevo en 1 minuto.";

    /** 5 códigos mal: cada uno da 401, y el siguiente, aunque sea el bueno, 429 con esos segundos en Retry-After. */
    private void cincoMalYBloqueado(String tokenTotp, String clave, long segundos) throws Exception {
        for (int i = 0; i < 5; i++) {
            verificar(tokenTotp, incorrecto(clave)).andExpect(status().isUnauthorized());
        }
        verificar(tokenTotp, codigo(clave)).andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", String.valueOf(segundos)))
                .andExpect(jsonPath("$.status", is(429)));
    }

    @Test
    @DisplayName("S5d: 5 códigos mal seguidos del mismo usuario dan 429 con la escalera 1, 5, 15 y 30 minutos")
    void escaleraDeBloqueos() throws Exception {
        String email = crearSuperAdmin();
        String tokenTotp = tokenTotp(email);
        String clave = clave(tokenTotp);

        cincoMalYBloqueado(tokenTotp, clave, 60);
        verificar(tokenTotp, codigo(clave)).andExpect(jsonPath("$.message", is(AVISO_UN_MINUTO)));
        reloj.avanzar(Duration.ofMinutes(1));
        cincoMalYBloqueado(tokenTotp, clave, 300);
        reloj.avanzar(Duration.ofMinutes(5));
        cincoMalYBloqueado(tokenTotp(email), clave, 900);
        reloj.avanzar(Duration.ofMinutes(15));
        cincoMalYBloqueado(tokenTotp(email), clave, 1800);
        reloj.avanzar(Duration.ofMinutes(30));
        cincoMalYBloqueado(tokenTotp(email), clave, 1800);
    }

    @Test
    @DisplayName("S5d: el código bien reinicia la racha")
    void elCodigoBienReiniciaLaRacha() throws Exception {
        String email = crearSuperAdmin();
        String tokenTotp = tokenTotp(email);
        String clave = clave(tokenTotp);
        for (int i = 0; i < 4; i++) {
            verificar(tokenTotp, incorrecto(clave)).andExpect(status().isUnauthorized());
        }
        verificar(tokenTotp, codigo(clave)).andExpect(status().isOk());
        reloj.avanzar(Duration.ofSeconds(30));

        String otraVez = tokenTotp(email);
        // Sin el reinicio, el primero de estos ya daría 429: el código bien fue el quinto intento de la racha.
        for (int i = 0; i < 5; i++) {
            verificar(otraVez, incorrecto(clave)).andExpect(status().isUnauthorized());
        }
        verificar(otraVez, codigo(clave)).andExpect(status().isTooManyRequests());
    }
}
