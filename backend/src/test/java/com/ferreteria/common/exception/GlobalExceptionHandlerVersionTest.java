package com.ferreteria.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.common.ApiError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.ResponseEntity;

/** Red global del bloqueo optimista del articulo (lote 5b, C-222): se llama directo, sin servidor. */
class GlobalExceptionHandlerVersionTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("un OptimisticLockingFailureException da 409 con el mensaje de la version y sin errors")
    void choqueDeVersionEs409() {
        ResponseEntity<ApiError> respuesta =
                handler.versionChocada(new OptimisticLockingFailureException("Row was updated or deleted by another transaction"));

        assertThat(respuesta.getStatusCode().value()).isEqualTo(409);
        assertThat(respuesta.getBody().message()).isEqualTo("Otro cambio modificó este artículo. Recargá y volvé a intentar.");
        assertThat(respuesta.getBody().errors()).isNullOrEmpty();
    }
}
