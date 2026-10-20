package com.ferreteria.ventas.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * CCT3-403 (CC-028): el rol Dueño del controlador. {@code SecurityConfig} ya lo exige por ruta (antes de leer el cuerpo); el
 * {@code @PreAuthorize} de clase es la segunda barrera y este test impide que alguien la saque sin que se note.
 */
class CobranzaControllerTest {

    @Test
    @DisplayName("el controlador es solo del Dueño a nivel clase y expone POST /api/cobranzas y GET /api/recibos/{id}")
    void soloDueno() throws NoSuchMethodException {
        PreAuthorize rol = CobranzaController.class.getAnnotation(PreAuthorize.class);
        Method cobrar = CobranzaController.class.getMethod("cobrar", CobranzaRequest.class,
                com.ferreteria.auth.security.AuthUser.class);
        Method recibo = CobranzaController.class.getMethod("recibo", Long.class);

        assertThat(rol).isNotNull();
        assertThat(rol.value()).isEqualTo("hasRole('DUENO')");
        assertThat(cobrar.getAnnotation(PostMapping.class).value()).containsExactly("/api/cobranzas");
        assertThat(recibo.getAnnotation(GetMapping.class).value()).containsExactly("/api/recibos/{id}");
    }
}
