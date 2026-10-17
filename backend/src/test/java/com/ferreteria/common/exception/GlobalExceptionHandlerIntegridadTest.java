package com.ferreteria.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.common.ApiError;
import java.sql.SQLException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ResponseEntity;

/**
 * Un valor que no entra en la columna no es un duplicado: decir "El dato ya existe" manda al usuario a buscar algo
 * que no existe (auditoria H08). Se distingue por el codigo de error de MySQL en la cadena de causas.
 */
class GlobalExceptionHandlerIntegridadTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static DataIntegrityViolationException conCodigo(int codigo, String mensaje) {
        return new DataIntegrityViolationException("could not execute statement",
                new RuntimeException("envoltorio de Hibernate", new SQLException(mensaje, "23000", codigo)));
    }

    @Test
    @DisplayName("un UNIQUE repetido (1062) sigue siendo 409 con el mensaje de duplicado")
    void duplicadoEs409() {
        ResponseEntity<ApiError> respuesta = handler.integridad(conCodigo(1062, "Duplicate entry 'x' for key 'uk'"));

        assertThat(respuesta.getStatusCode().value()).isEqualTo(409);
        assertThat(respuesta.getBody().message()).isEqualTo("El dato ya existe o entra en conflicto con otro");
    }

    @Test
    @DisplayName("un valor fuera de rango (1264) es 400 y dice que es demasiado grande")
    void fueraDeRangoEs400() {
        ResponseEntity<ApiError> respuesta =
                handler.integridad(conCodigo(1264, "Out of range value for column 'precio_final' at row 1"));

        assertThat(respuesta.getStatusCode().value()).isEqualTo(400);
        assertThat(respuesta.getBody().message()).isEqualTo("Hay un valor demasiado grande.");
    }

    @Test
    @DisplayName("otra violacion de integridad sigue siendo 409")
    void otraEs409() {
        ResponseEntity<ApiError> respuesta = handler.integridad(new DataIntegrityViolationException("sin causa SQL"));

        assertThat(respuesta.getStatusCode().value()).isEqualTo(409);
    }
}
