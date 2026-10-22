package com.ferreteria.catalogo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ferreteria.catalogo.dto.RecalculoCotizacionResponse;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.cotizacion.api.DolarDePrecios;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

/**
 * El recalculo por cotizacion con reintento acotado (lote 5b, T-245, R-228): hasta 3 intentos COMPLETOS, cada uno en su
 * transaccion, cuando un articulo cambia mientras se recalcula. Cualquier otra excepcion sale en el primer intento.
 */
class RecalculoConReintentoTest {

    private static final LocalDateTime LAS_OCHO = LocalDateTime.of(2026, 10, 16, 8, 0);

    private final RecalculoPorCotizacionService servicio = mock(RecalculoPorCotizacionService.class);
    private final RecalculoConReintento reintento = new RecalculoConReintento(servicio);

    private static RecalculoCotizacionResponse respuesta(int articulos) {
        return new RecalculoCotizacionResponse(new BigDecimal("1100"), articulos, java.util.List.of());
    }

    private static ObjectOptimisticLockingFailureException choque() {
        return new ObjectOptimisticLockingFailureException("Articulo", 1L);
    }

    @Test
    @DisplayName("recalcularAhora: un choque y despues un exito: 2 llamadas y el resultado del segundo")
    void ahoraUnChoqueYUnExito() {
        when(servicio.recalcularAhora(9L, false)).thenThrow(choque()).thenReturn(respuesta(2));

        assertThat(reintento.recalcularAhora(9L, false)).isEqualTo(respuesta(2));

        verify(servicio, times(2)).recalcularAhora(9L, false);
    }

    @Test
    @DisplayName("recalcularAhora: 3 choques seguidos: sale el OptimisticLockingFailureException despues de 3 llamadas")
    void ahoraTresChoques() {
        when(servicio.recalcularAhora(9L, false)).thenThrow(choque());

        assertThatThrownBy(() -> reintento.recalcularAhora(9L, false))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        verify(servicio, times(3)).recalcularAhora(9L, false);
    }

    @Test
    @DisplayName("recalcularAhora: otra excepcion, por ejemplo el 409 de sin cotizacion, no se reintenta")
    void ahoraOtraExcepcionNoSeReintenta() {
        when(servicio.recalcularAhora(9L, false)).thenThrow(new ConflictException(DolarDePrecios.SIN_COTIZACION));

        assertThatThrownBy(() -> reintento.recalcularAhora(9L, false)).isInstanceOf(ConflictException.class);

        verify(servicio, times(1)).recalcularAhora(9L, false);
    }

    @Test
    @DisplayName("recalcularAhora: sin choque, una sola llamada")
    void ahoraSinChoque() {
        when(servicio.recalcularAhora(9L, false)).thenReturn(respuesta(1));

        assertThat(reintento.recalcularAhora(9L, false)).isEqualTo(respuesta(1));

        verify(servicio, times(1)).recalcularAhora(9L, false);
    }

    @Test
    @DisplayName("recalcularDelDia: un choque y despues un exito: 2 llamadas y el resultado del segundo")
    void delDiaUnChoqueYUnExito() {
        when(servicio.recalcularDelDia(any())).thenThrow(choque()).thenReturn(Optional.of(respuesta(2)));

        assertThat(reintento.recalcularDelDia(LAS_OCHO)).contains(respuesta(2));

        verify(servicio, times(2)).recalcularDelDia(LAS_OCHO);
    }

    @Test
    @DisplayName("recalcularDelDia: 3 choques seguidos: sale el OptimisticLockingFailureException")
    void delDiaTresChoques() {
        when(servicio.recalcularDelDia(any())).thenThrow(choque());

        assertThatThrownBy(() -> reintento.recalcularDelDia(LAS_OCHO))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        verify(servicio, times(3)).recalcularDelDia(LAS_OCHO);
    }

    @Test
    @DisplayName("recalcularDelDia: otra excepcion no se reintenta, y un dia que no toca (vacio) tampoco")
    void delDiaOtraExcepcionYVacio() {
        when(servicio.recalcularDelDia(any())).thenThrow(new IllegalStateException("otra")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> reintento.recalcularDelDia(LAS_OCHO)).isInstanceOf(IllegalStateException.class);
        assertThat(reintento.recalcularDelDia(LAS_OCHO)).isEmpty();

        verify(servicio, times(2)).recalcularDelDia(LAS_OCHO);
    }

    @Test
    @DisplayName("el envoltorio NO es transaccional: cada intento abre la suya en el servicio")
    void noEsTransaccional() throws NoSuchMethodException {
        assertThat(RecalculoConReintento.class.getAnnotation(Transactional.class)).isNull();
        for (Method metodo : new Method[] {
                RecalculoConReintento.class.getMethod("recalcularAhora", Long.class, boolean.class),
                RecalculoConReintento.class.getMethod("recalcularDelDia", LocalDateTime.class)}) {
            assertThat(metodo.getAnnotation(Transactional.class)).isNull();
        }
    }

    @Test
    @DisplayName("dentro de una transaccion activa lanza IllegalStateException y no llama al servicio")
    void dentroDeUnaTransaccionNoSePuedeUsar() {
        org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);
        try {
            assertThatThrownBy(() -> reintento.recalcularAhora(9L, false)).isInstanceOf(IllegalStateException.class);
            assertThatThrownBy(() -> reintento.recalcularDelDia(LAS_OCHO)).isInstanceOf(IllegalStateException.class);
        } finally {
            org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(false);
        }

        verify(servicio, times(0)).recalcularAhora(9L, false);
    }
}
