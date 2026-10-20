package com.ferreteria.ventas.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.common.exception.SistemaOcupadoException;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.service.CobranzaRechazadaException.Motivo;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.transaction.annotation.Transactional;

/**
 * CCT3-310 (CC-038, CC-049): la fachada de cobrar, SIN transacción. Pre-chequea la clave en una transacción propia y sin locks,
 * reintenta los deadlocks (3 intentos) y resuelve la carrera de la clave única con una transacción nueva. Colaborador simulado.
 */
@ExtendWith(MockitoExtension.class)
class CobranzaServiceTest {

    static final Long USUARIO = 9L;
    static final String CLAVE = "7c9e6679-7425-40de-944b-e07fc1f90ae7";

    @Mock
    RegistroCobranza registroCobranza;

    CobranzaService service;

    CobranzaPedido pedido = new CobranzaPedido(CLAVE, 5L, MedioPago.EFECTIVO, new BigDecimal("100.00"), List.of());

    CobroRegistrado nuevo = new CobroRegistrado(null, List.of(), "Marta", false);
    CobroRegistrado repetido = new CobroRegistrado(null, List.of(), "Marta", true);

    @BeforeEach
    void preparar() {
        service = new CobranzaService(registroCobranza);
        // Por defecto el pre-chequeo no encuentra nada (cada test lo cambia). Lenient: algunos cortan antes.
        org.mockito.Mockito.lenient().when(registroCobranza.repetida(pedido)).thenReturn(Optional.empty());
    }

    @Test
    @DisplayName("la fachada no tiene transacción: cada intento abre la suya en RegistroCobranza")
    void sinTransaccion() throws NoSuchMethodException {
        assertThat(CobranzaService.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(CobranzaService.class.getMethod("cobrar", CobranzaPedido.class, Long.class)
                .isAnnotationPresent(Transactional.class)).isFalse();
    }

    @Test
    @DisplayName("un cobro que sale bien se devuelve tal cual, con un solo intento (y el pre-chequeo antes)")
    void caminoFeliz() {
        when(registroCobranza.registrar(pedido, USUARIO)).thenReturn(nuevo);

        assertThat(service.cobrar(pedido, USUARIO)).isSameAs(nuevo);

        InOrder orden = inOrder(registroCobranza);
        orden.verify(registroCobranza).repetida(pedido);
        orden.verify(registroCobranza).registrar(pedido, USUARIO);
        orden.verifyNoMoreInteractions();
    }

    @Test
    @DisplayName("CC-038: si la clave ya está registrada el pre-chequeo la devuelve (200) y NO se llama a registrar (aunque la caja haya cerrado)")
    void preChequeoDevuelveLaOriginal() {
        when(registroCobranza.repetida(pedido)).thenReturn(Optional.of(repetido));

        assertThat(service.cobrar(pedido, USUARIO)).isSameAs(repetido);

        verify(registroCobranza, never()).registrar(any(), any());
    }

    @Test
    @DisplayName("el pre-chequeo con otro contenido propaga el 422 sin registrar")
    void preChequeoOtroContenido() {
        CobranzaRechazadaException otro = new CobranzaRechazadaException(Motivo.CLAVE_CON_OTRO_CONTENIDO, "otro");
        when(registroCobranza.repetida(pedido)).thenThrow(otro);

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO)).isSameAs(otro);
        verify(registroCobranza, never()).registrar(any(), any());
    }

    @Test
    @DisplayName("un pedido mal formado es 400 del pre-chequeo y no se registra")
    void preChequeoMalFormado() {
        DatosInvalidosException malo = new DatosInvalidosException(Map.of("clave", "mala"));
        when(registroCobranza.repetida(pedido)).thenThrow(malo);

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO)).isSameAs(malo);
        verify(registroCobranza, never()).registrar(any(), any());
    }

    @Test
    @DisplayName("un deadlock se reintenta y el cobro sale en el segundo intento")
    void reintentaUnDeadlock() {
        when(registroCobranza.registrar(pedido, USUARIO))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenReturn(nuevo);

        assertThat(service.cobrar(pedido, USUARIO)).isSameAs(nuevo);
        verify(registroCobranza, times(2)).registrar(pedido, USUARIO);
        verify(registroCobranza, times(1)).repetida(pedido);
    }

    @Test
    @DisplayName("tres intentos con deadlock o timeout y se avisa que el sistema está ocupado (503), con el último error como causa; no hay un cuarto")
    void tresIntentosYSistemaOcupado() {
        DeadlockLoserDataAccessException ultimo = new DeadlockLoserDataAccessException("deadlock", null);
        when(registroCobranza.registrar(pedido, USUARIO))
                .thenThrow(new CannotAcquireLockException("timeout"))
                .thenThrow(new CannotAcquireLockException("timeout"))
                .thenThrow(ultimo);

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO)).isInstanceOf(SistemaOcupadoException.class)
                .hasCause(ultimo).hasMessage("El sistema está ocupado, reintentá.");
        verify(registroCobranza, times(3)).registrar(pedido, USUARIO);
        assertThat(CobranzaService.MAX_INTENTOS).isEqualTo(3);
    }

    @Test
    @DisplayName("un error que no es de lock se relanza tal cual, sin reintentos y sin disfrazarlo de «sistema ocupado» (sigue siendo 500)")
    void otroErrorNoEsSistemaOcupado() {
        IllegalStateException otro = new IllegalStateException("otra cosa");
        when(registroCobranza.registrar(pedido, USUARIO)).thenThrow(otro);

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO)).isSameAs(otro);
        verify(registroCobranza, times(1)).registrar(pedido, USUARIO);
    }

    @Test
    @DisplayName("un rechazo de la cobranza (409/422) no se reintenta")
    void rechazoNoSeReintenta() {
        CobranzaRechazadaException rechazo = new CobranzaRechazadaException(Motivo.IMPUTACION_EXCEDE_DEUDA, "cambió", List.of());
        when(registroCobranza.registrar(pedido, USUARIO)).thenThrow(rechazo);

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO)).isSameAs(rechazo);
        verify(registroCobranza, times(1)).registrar(pedido, USUARIO);
    }

    @Test
    @DisplayName("otras excepciones (de programación, de forma) no se reintentan")
    void otrasNoSeReintentan() {
        IllegalStateException roto = new IllegalStateException("roto");
        when(registroCobranza.registrar(pedido, USUARIO)).thenThrow(roto);

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO)).isSameAs(roto);
        verify(registroCobranza, times(1)).registrar(pedido, USUARIO);
    }

    @Test
    @DisplayName("perder la carrera de la clave única: busca el cobro en una transacción nueva y lo devuelve como repetido")
    void carreraDeClave() {
        when(registroCobranza.registrar(pedido, USUARIO))
                .thenThrow(new DataIntegrityViolationException("uk_recibo_clave", new java.sql.SQLException("dup", "23000", 1062)));
        when(registroCobranza.repetida(pedido)).thenReturn(Optional.empty(), Optional.of(repetido));

        assertThat(service.cobrar(pedido, USUARIO)).isSameAs(repetido);
        verify(registroCobranza, times(1)).registrar(pedido, USUARIO);
        verify(registroCobranza, times(2)).repetida(pedido);
    }

    @Test
    @DisplayName("sin código de MySQL (violación genérica) también se trata como carrera de clave")
    void integridadSinCodigo() {
        when(registroCobranza.registrar(pedido, USUARIO)).thenThrow(new DataIntegrityViolationException("uk"));
        when(registroCobranza.repetida(pedido)).thenReturn(Optional.empty(), Optional.of(repetido));

        assertThat(service.cobrar(pedido, USUARIO)).isSameAs(repetido);
    }

    @Test
    @DisplayName("la misma clave con OTRO cliente (no comparte lock): la carrera propaga CLAVE_CON_OTRO_CONTENIDO")
    void carreraConOtroContenido() {
        when(registroCobranza.registrar(pedido, USUARIO)).thenThrow(new DataIntegrityViolationException("uk"));
        CobranzaRechazadaException otro = new CobranzaRechazadaException(Motivo.CLAVE_CON_OTRO_CONTENIDO, "otro");
        when(registroCobranza.repetida(pedido)).thenReturn(Optional.empty()).thenThrow(otro);

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO)).isSameAs(otro);
    }

    @Test
    @DisplayName("una violación de integridad sin recibo con esa clave se relanza")
    void integridadSinRecibo() {
        DataIntegrityViolationException error = new DataIntegrityViolationException("otra restricción");
        when(registroCobranza.registrar(pedido, USUARIO)).thenThrow(error);

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO)).isSameAs(error);
    }

    @Test
    @DisplayName("un valor fuera de rango (1264) no es una carrera de clave: se relanza sin buscar el recibo de nuevo")
    void fueraDeRangoNoEsCarrera() {
        DataIntegrityViolationException error = new DataIntegrityViolationException("could not execute statement",
                new java.sql.SQLException("Out of range value for column 'total'", "22003", 1264));
        when(registroCobranza.registrar(pedido, USUARIO)).thenThrow(error);

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO)).isSameAs(error);
        verify(registroCobranza, times(1)).repetida(pedido); // solo el pre-chequeo
    }

    @Test
    @DisplayName("un deadlock y después la carrera de clave: reintenta y después resuelve por clave")
    void deadlockYLuegoCarrera() {
        when(registroCobranza.registrar(pedido, USUARIO))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenThrow(new DataIntegrityViolationException("uk"));
        when(registroCobranza.repetida(pedido)).thenReturn(Optional.empty(), Optional.of(repetido));

        assertThat(service.cobrar(pedido, USUARIO)).isSameAs(repetido);
        verify(registroCobranza, times(2)).registrar(pedido, USUARIO);
        verifyNoMoreInteractions(registroCobranza);
    }

    @Test
    @DisplayName("recibo(id) delega en RegistroCobranza (que lo lee en su propia transacción) y no abre transacción")
    void reciboPorId() throws NoSuchMethodException {
        when(registroCobranza.recibo(501L)).thenReturn(repetido);

        assertThat(service.recibo(501L)).isSameAs(repetido);

        assertThat(CobranzaService.class.getMethod("recibo", Long.class).isAnnotationPresent(Transactional.class)).isFalse();
    }
}
