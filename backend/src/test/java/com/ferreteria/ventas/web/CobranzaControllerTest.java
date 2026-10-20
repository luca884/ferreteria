package com.ferreteria.ventas.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

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

    /**
     * CCT3B1-601 (CC-055, CC-091): la anulación cuelga del mismo controlador (Dueño por clase) y NO es transaccional: la fachada
     * {@code AnulacionService} se niega a correr dentro de una transacción ajena y {@code open-in-view} está apagado, así que una
     * {@code @Transactional} en el controlador (o en la clase) la rompería.
     */
    @Test
    @DisplayName("POST /api/recibos/{id}/anulacion existe, el cuerpo es opcional (el motivo ausente da 400 en motivo) y ni el método ni la clase son @Transactional")
    void anular() throws NoSuchMethodException {
        Method anular = CobranzaController.class.getMethod("anular", Long.class, AnulacionRequest.class,
                com.ferreteria.auth.security.AuthUser.class);
        RequestBody cuerpo = anular.getParameters()[1].getAnnotation(RequestBody.class);

        assertThat(anular.getAnnotation(PostMapping.class).value()).containsExactly("/api/recibos/{id}/anulacion");
        assertThat(cuerpo).isNotNull();
        assertThat(cuerpo.required()).as("cuerpo ausente = motivo ausente = 400 en motivo").isFalse();
        assertThat(CobranzaController.class.getAnnotation(PreAuthorize.class).value()).isEqualTo("hasRole('DUENO')");
        assertThat(anular.getAnnotation(Transactional.class)).isNull();
        assertThat(CobranzaController.class.getAnnotation(Transactional.class)).isNull();
    }
}
