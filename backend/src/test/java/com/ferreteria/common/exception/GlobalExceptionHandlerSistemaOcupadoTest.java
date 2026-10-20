package com.ferreteria.common.exception;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.common.ApiError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/**
 * S9 del verify de cuenta-corriente-tramo-3a: cuando una fachada agota los reintentos por deadlock o por timeout de lock, la
 * API responde 503 «reintentá» (transitorio, con {@code Retry-After}) y no 500 «Error interno». Se llama directo, sin servidor.
 */
class GlobalExceptionHandlerSistemaOcupadoTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    @DisplayName("el sistema ocupado da 503 con el mensaje de reintentar, sin errors, y Retry-After de 2 segundos")
    void sistemaOcupadoEs503() {
        ResponseEntity<ApiError> respuesta = handler.sistemaOcupado(
                new SistemaOcupadoException(new DeadlockLoserDataAccessException("deadlock", null)));

        assertThat(respuesta.getStatusCode().value()).isEqualTo(503);
        assertThat(respuesta.getBody().status()).isEqualTo(503);
        assertThat(respuesta.getBody().message()).isEqualTo("El sistema está ocupado, reintentá.");
        assertThat(respuesta.getBody().errors()).isNullOrEmpty();
        assertThat(respuesta.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("2");
    }

    @Test
    @DisplayName("la excepción conserva la causa (el deadlock o el timeout original) para el log")
    void conservaLaCausa() {
        CannotAcquireLockException causa = new CannotAcquireLockException("Lock wait timeout exceeded");

        SistemaOcupadoException ocupado = new SistemaOcupadoException(causa);

        assertThat(ocupado).hasCause(causa).hasMessage("El sistema está ocupado, reintentá.");
    }

    @Test
    @DisplayName("cualquier otra excepción sigue siendo 500 «Error interno», sin Retry-After")
    void otrosErroresSiguenSiendo500() {
        ResponseEntity<ApiError> respuesta = handler.unexpected(new IllegalStateException("otra cosa"));

        assertThat(respuesta.getStatusCode().value()).isEqualTo(500);
        assertThat(respuesta.getBody().message()).isEqualTo("Error interno");
        assertThat(respuesta.getHeaders().containsKey(HttpHeaders.RETRY_AFTER)).isFalse();
    }
}
