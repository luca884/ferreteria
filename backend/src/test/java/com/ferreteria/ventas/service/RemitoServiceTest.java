package com.ferreteria.ventas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ferreteria.common.exception.SistemaOcupadoException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.transaction.annotation.Transactional;

/**
 * R-001 (RM-004): la fachada de emitir un remito, SIN transacción. Reintenta los deadlocks (3 intentos), agotados avisa que el
 * sistema está ocupado (503) y resuelve la carrera de la clave única con una transacción nueva. Colaborador simulado.
 */
@ExtendWith(MockitoExtension.class)
class RemitoServiceTest {

    static final Long USUARIO = 9L;
    static final String CLAVE = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

    @Mock
    RegistroRemito registroRemito;

    RemitoService service;

    EmitirRemito pedido = new EmitirRemito(CLAVE, List.of(), null, null, null, null);

    RemitoEmitido nuevo = mock(RemitoEmitido.class);
    RemitoEmitido repetido = mock(RemitoEmitido.class);

    @BeforeEach
    void preparar() {
        service = new RemitoService(registroRemito);
    }

    @Test
    @DisplayName("la fachada no tiene transacción: cada intento abre la suya en RegistroRemito")
    void sinTransaccion() throws NoSuchMethodException {
        assertThat(RemitoService.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(RemitoService.class.getMethod("emitir", EmitirRemito.class, Long.class)
                .isAnnotationPresent(Transactional.class)).isFalse();
    }

    @Test
    @DisplayName("un remito que sale bien se devuelve tal cual, con un solo intento")
    void caminoFeliz() {
        when(registroRemito.emitir(pedido, USUARIO)).thenReturn(nuevo);

        assertThat(service.emitir(pedido, USUARIO)).isSameAs(nuevo);
        verify(registroRemito, times(1)).emitir(pedido, USUARIO);
    }

    @Test
    @DisplayName("un deadlock se reintenta y el remito sale en el segundo intento")
    void reintentaUnDeadlock() {
        when(registroRemito.emitir(pedido, USUARIO))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenReturn(nuevo);

        assertThat(service.emitir(pedido, USUARIO)).isSameAs(nuevo);
        verify(registroRemito, times(2)).emitir(pedido, USUARIO);
    }

    @Test
    @DisplayName("R-001: tres intentos con deadlock o timeout y se avisa que el sistema está ocupado (503), con el último error como causa; no hay un cuarto")
    void tresIntentosYSistemaOcupado() {
        DeadlockLoserDataAccessException ultimo = new DeadlockLoserDataAccessException("deadlock", null);
        when(registroRemito.emitir(pedido, USUARIO))
                .thenThrow(new CannotAcquireLockException("timeout"))
                .thenThrow(new CannotAcquireLockException("timeout"))
                .thenThrow(ultimo);

        assertThatThrownBy(() -> service.emitir(pedido, USUARIO)).isInstanceOf(SistemaOcupadoException.class)
                .hasCause(ultimo).hasMessage("El sistema está ocupado, reintentá.");
        verify(registroRemito, times(3)).emitir(pedido, USUARIO);
        assertThat(RemitoService.MAX_INTENTOS).isEqualTo(3);
    }

    @Test
    @DisplayName("R-001: un error que no es de lock se relanza tal cual, sin reintentos y sin disfrazarlo de «sistema ocupado»")
    void otroErrorNoEsSistemaOcupado() {
        IllegalStateException otro = new IllegalStateException("otra cosa");
        when(registroRemito.emitir(pedido, USUARIO)).thenThrow(otro);

        assertThatThrownBy(() -> service.emitir(pedido, USUARIO)).isSameAs(otro);
        verify(registroRemito, times(1)).emitir(pedido, USUARIO);
    }

    @Test
    @DisplayName("perder la carrera de la clave única: busca el remito en una transacción nueva y lo devuelve")
    void carreraDeClave() {
        when(registroRemito.emitir(pedido, USUARIO)).thenThrow(new DataIntegrityViolationException("uk_remito_clave",
                new java.sql.SQLException("dup", "23000", 1062)));
        when(registroRemito.repetido(pedido)).thenReturn(Optional.of(repetido));

        assertThat(service.emitir(pedido, USUARIO)).isSameAs(repetido);
        verify(registroRemito, times(1)).emitir(pedido, USUARIO);
        verify(registroRemito, times(1)).repetido(pedido);
    }

    @Test
    @DisplayName("un valor fuera de rango (1264) no es una carrera de clave: se relanza sin buscar el remito")
    void fueraDeRangoNoEsCarrera() {
        DataIntegrityViolationException error = new DataIntegrityViolationException("could not execute statement",
                new java.sql.SQLException("Out of range value for column 'total'", "22003", 1264));
        when(registroRemito.emitir(pedido, USUARIO)).thenThrow(error);

        assertThatThrownBy(() -> service.emitir(pedido, USUARIO)).isSameAs(error);
        verify(registroRemito, times(1)).emitir(pedido, USUARIO);
        verify(registroRemito, never()).repetido(pedido);
    }
}
