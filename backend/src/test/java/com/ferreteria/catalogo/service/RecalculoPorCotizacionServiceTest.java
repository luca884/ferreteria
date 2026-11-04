package com.ferreteria.catalogo.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.ferreteria.catalogo.dto.RecalculoCotizacionResponse;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.cotizacion.api.DolarDePrecios;
import java.lang.reflect.Constructor;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.transaction.annotation.Transactional;

/**
 * «Recalcular ahora» y el recalculo del dia, sin base: el puerto del dolar y la cascada son dobles. La parte contra
 * MySQL esta en RecalculoPorCotizacionIntegrationTest y RecalculoDelDiaIntegrationTest.
 */
class RecalculoPorCotizacionServiceTest {

    private static final Long DUENO = 7L;
    private static final LocalDateTime LAS_OCHO = LocalDateTime.of(2026, 10, 16, 8, 0);

    private final DolarDePrecios dolar = mock(DolarDePrecios.class);
    private final RecalculoPreciosService recalculo = mock(RecalculoPreciosService.class);
    private final RecalculoPorCotizacionService service = new RecalculoPorCotizacionService(dolar, recalculo);

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    // ---- recalcularAhora ----

    @Test
    @DisplayName("recalcularAhora fija el ultimo dolar, recalcula con ese valor a nombre del Dueno y devuelve {cotizacion, cantidad}")
    void recalcularAhora() {
        when(dolar.fijarUltima(false, null)).thenReturn(bd("1100"));
        when(recalculo.porCotizacion(bd("1100"), DUENO)).thenReturn(new RecalculoPreciosService.ResultadoPorCotizacion(3, List.of()));

        RecalculoCotizacionResponse respuesta = service.recalcularAhora(DUENO, false);

        assertThat(respuesta.cotizacion()).isEqualByComparingTo("1100");
        assertThat(respuesta.articulosRecalculados()).isEqualTo(3);
        InOrder orden = inOrder(dolar, recalculo);
        orden.verify(dolar).fijarUltima(false, null);
        orden.verify(recalculo).porCotizacion(bd("1100"), DUENO);
    }

    @Test
    @DisplayName("recalcularAhora usa el valor que fijo el puerto, no uno fijo: con 950 recalcula con 950 y a nombre de otro Dueno")
    void recalcularAhoraConOtroValor() {
        when(dolar.fijarUltima(false, null)).thenReturn(bd("950"));
        when(recalculo.porCotizacion(bd("950"), 12L)).thenReturn(new RecalculoPreciosService.ResultadoPorCotizacion(0, List.of()));

        RecalculoCotizacionResponse respuesta = service.recalcularAhora(12L, false);

        assertThat(respuesta.cotizacion()).isEqualByComparingTo("950");
        assertThat(respuesta.articulosRecalculados()).isZero();
        verify(recalculo).porCotizacion(bd("950"), 12L);
    }

    @Test
    @DisplayName("con la confirmacion del Dueno se la pasa al puerto: acepta el dolar que se aleja mas del 20 % (H09)")
    void recalcularAhoraConfirmandoElDesvio() {
        when(dolar.fijarUltima(true, null)).thenReturn(bd("11"));
        when(recalculo.porCotizacion(bd("11"), DUENO)).thenReturn(new RecalculoPreciosService.ResultadoPorCotizacion(1, List.of()));

        RecalculoCotizacionResponse respuesta = service.recalcularAhora(DUENO, true);

        assertThat(respuesta.cotizacion()).isEqualByComparingTo("11");
        verify(dolar, never()).fijarUltima(false, null);
    }

    @Test
    @DisplayName("con la cotizacion apagada propaga el 409 y no toca ningun precio")
    void apagadaPropagaElConflicto() {
        when(dolar.fijarUltima(false, null)).thenThrow(new ConflictException("La cotización está apagada"));

        assertThatThrownBy(() -> service.recalcularAhora(DUENO, false))
                .isInstanceOf(ConflictException.class)
                .hasMessage("La cotización está apagada");
        verifyNoInteractions(recalculo);
    }

    @Test
    @DisplayName("sin ninguna cotizacion guardada propaga el 409 y no toca ningun precio")
    void sinValorPropagaElConflicto() {
        when(dolar.fijarUltima(false, null))
                .thenThrow(new ConflictException("Todavía no hay cotización del dólar. Probá en unos minutos"));

        assertThatThrownBy(() -> service.recalcularAhora(DUENO, false))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Todavía no hay cotización del dólar. Probá en unos minutos");
        verify(recalculo, never()).porCotizacion(any(), anyLong());
    }

    // ---- recalcularDelDia ----

    @Test
    @DisplayName("recalcularDelDia reclama el dia ANTES de recalcular y recalcula sin usuario (Automatico)")
    void recalcularDelDia() {
        when(dolar.reclamarRecalculoDelDia(LAS_OCHO)).thenReturn(Optional.of(bd("1100")));
        when(recalculo.porCotizacion(bd("1100"), null)).thenReturn(new RecalculoPreciosService.ResultadoPorCotizacion(2, List.of()));

        Optional<RecalculoCotizacionResponse> respuesta = service.recalcularDelDia(LAS_OCHO);

        assertThat(respuesta).hasValueSatisfying(r -> {
            assertThat(r.cotizacion()).isEqualByComparingTo("1100");
            assertThat(r.articulosRecalculados()).isEqualTo(2);
        });
        InOrder orden = inOrder(dolar, recalculo);
        orden.verify(dolar).reclamarRecalculoDelDia(LAS_OCHO);
        orden.verify(recalculo).porCotizacion(bd("1100"), null);
    }

    @Test
    @DisplayName("si el reclamo vuelve vacio (no es la hora, ya se hizo hoy, apagada) termina sin recalcular nada")
    void sinReclamoNoHaceNada() {
        when(dolar.reclamarRecalculoDelDia(LAS_OCHO)).thenReturn(Optional.empty());

        assertThat(service.recalcularDelDia(LAS_OCHO)).isEmpty();

        verify(dolar).reclamarRecalculoDelDia(LAS_OCHO);
        verifyNoInteractions(recalculo);
    }

    @Test
    @DisplayName("si el recalculo falla la excepcion sale (para que la transaccion revierta el reclamo del dia)")
    void unaFallaSale() {
        when(dolar.reclamarRecalculoDelDia(LAS_OCHO)).thenReturn(Optional.of(bd("1100")));
        when(recalculo.porCotizacion(bd("1100"), null)).thenThrow(new IllegalStateException("fallo"));

        assertThatThrownBy(() -> service.recalcularDelDia(LAS_OCHO))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("fallo");
    }

    // ---- estructura ----

    @Test
    @DisplayName("cada caso de uso corre en UNA transaccion (el reclamo y el recalculo se confirman o revierten juntos)")
    void sonTransaccionales() throws NoSuchMethodException {
        assertThat(RecalculoPorCotizacionService.class.getMethod("recalcularAhora", Long.class, boolean.class)
                .getAnnotation(Transactional.class)).isNotNull();
        assertThat(RecalculoPorCotizacionService.class.getMethod("recalcularDelDia", LocalDateTime.class)
                .getAnnotation(Transactional.class)).isNotNull();
    }

    @Test
    @DisplayName("solo depende del puerto del dolar y del recalculo de precios: no consulta tickets, ventas ni stock (decision 16)")
    void noConsultaTicketsNiVentas() {
        Constructor<?>[] constructores = RecalculoPorCotizacionService.class.getConstructors();

        assertThat(constructores).hasSize(1);
        List<Class<?>> dependencias = Arrays.asList(constructores[0].getParameterTypes());
        assertThat(dependencias).containsExactlyInAnyOrder(DolarDePrecios.class, RecalculoPreciosService.class);
    }
}
