package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.service.ConfirmarVenta;
import com.ferreteria.ventas.service.ConfirmarVenta.Renglon;
import com.ferreteria.ventas.service.RegistroVenta;
import com.ferreteria.ventas.service.VentaRechazadaException;
import com.ferreteria.ventas.service.VentaRechazadaException.Motivo;
import com.ferreteria.ventas.service.VentaRegistrada;
import com.ferreteria.ventas.service.VentaService;
import java.math.BigDecimal;
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
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.transaction.annotation.Transactional;

/** La fachada sin transaccion: reintenta deadlocks y resuelve la carrera de la clave (C-009). */
@ExtendWith(MockitoExtension.class)
class VentaServiceTest {

    static final Long USUARIO = 9L;

    @Mock
    RegistroVenta registroVenta;

    VentaService service;

    ConfirmarVenta pedido = new ConfirmarVenta("7c9e6679-7425-40de-944b-e07fc1f90ae7",
            List.of(new Renglon(1L, BigDecimal.ONE, new BigDecimal("10.00"))), new BigDecimal("10.00"),
            MedioPago.EFECTIVO);

    VentaRegistrada nueva = new VentaRegistrada(null, List.of(), null, List.of(), false);
    VentaRegistrada repetida = new VentaRegistrada(null, List.of(), null, List.of(), true);

    @BeforeEach
    void preparar() {
        service = new VentaService(registroVenta);
    }

    @Test
    @DisplayName("la fachada no tiene transaccion: cada intento abre la suya en RegistroVenta")
    void sinTransaccion() throws NoSuchMethodException {
        assertThat(VentaService.class.isAnnotationPresent(Transactional.class)).isFalse();
        assertThat(VentaService.class.getMethod("cobrar", ConfirmarVenta.class, Long.class)
                .isAnnotationPresent(Transactional.class)).isFalse();
    }

    @Test
    @DisplayName("una venta que sale bien se devuelve tal cual, con un solo intento")
    void caminoFeliz() {
        when(registroVenta.registrar(pedido, USUARIO)).thenReturn(nueva);

        assertThat(service.cobrar(pedido, USUARIO)).isSameAs(nueva);
        verify(registroVenta, times(1)).registrar(pedido, USUARIO);
    }

    @Test
    @DisplayName("un deadlock se reintenta y la venta sale en el segundo intento")
    void reintentaUnDeadlock() {
        when(registroVenta.registrar(pedido, USUARIO))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenReturn(nueva);

        assertThat(service.cobrar(pedido, USUARIO)).isSameAs(nueva);
        verify(registroVenta, times(2)).registrar(pedido, USUARIO);
    }

    @Test
    @DisplayName("tres deadlocks seguidos: 3 intentos y despues propaga la excepcion")
    void tresIntentosYPropaga() {
        when(registroVenta.registrar(pedido, USUARIO)).thenThrow(new CannotAcquireLockException("deadlock"));

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO))
                .isInstanceOf(PessimisticLockingFailureException.class);
        verify(registroVenta, times(3)).registrar(pedido, USUARIO);
    }

    @Test
    @DisplayName("un rechazo de la venta no se reintenta")
    void rechazoNoSeReintenta() {
        VentaRechazadaException rechazo = new VentaRechazadaException(Motivo.PRECIOS_CAMBIADOS, "cambio", List.of());
        when(registroVenta.registrar(pedido, USUARIO)).thenThrow(rechazo);

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO)).isSameAs(rechazo);
        verify(registroVenta, times(1)).registrar(pedido, USUARIO);
    }

    @Test
    @DisplayName("perder la carrera de la clave: busca la venta por clave y la devuelve repetida")
    void carreraDeClave() {
        when(registroVenta.registrar(pedido, USUARIO)).thenThrow(new DataIntegrityViolationException("uk_comprobante_clave"));
        when(registroVenta.repetida(pedido)).thenReturn(Optional.of(repetida));

        assertThat(service.cobrar(pedido, USUARIO)).isSameAs(repetida);
        verify(registroVenta, times(1)).registrar(pedido, USUARIO);
    }

    @Test
    @DisplayName("una violacion de integridad sin venta con esa clave se relanza")
    void integridadSinVenta() {
        DataIntegrityViolationException error = new DataIntegrityViolationException("otra restriccion");
        when(registroVenta.registrar(pedido, USUARIO)).thenThrow(error);
        when(registroVenta.repetida(pedido)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO)).isSameAs(error);
    }

    @Test
    @DisplayName("un valor fuera de rango (1264) no es una carrera de clave: se relanza sin buscar la venta")
    void fueraDeRangoNoEsCarrera() {
        DataIntegrityViolationException error = new DataIntegrityViolationException("could not execute statement",
                new java.sql.SQLException("Out of range value for column 'total'", "22003", 1264));
        when(registroVenta.registrar(pedido, USUARIO)).thenThrow(error);

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO)).isSameAs(error);
        verify(registroVenta, never()).repetida(any());
    }

    @Test
    @DisplayName("la carrera con otro contenido propaga el 422 de la busqueda por clave")
    void carreraConOtroContenido() {
        when(registroVenta.registrar(pedido, USUARIO)).thenThrow(new DataIntegrityViolationException("uk"));
        VentaRechazadaException otroContenido = new VentaRechazadaException(
                Motivo.CLAVE_CON_OTRO_CONTENIDO, "Esta venta ya se registró con otros datos", List.of());
        when(registroVenta.repetida(pedido)).thenThrow(otroContenido);

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO)).isSameAs(otroContenido);
    }

    @Test
    @DisplayName("un deadlock y despues la carrera de clave: reintenta y despues resuelve por clave")
    void deadlockYLuegoCarrera() {
        when(registroVenta.registrar(pedido, USUARIO))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenThrow(new DataIntegrityViolationException("uk"));
        when(registroVenta.repetida(pedido)).thenReturn(Optional.of(repetida));

        assertThat(service.cobrar(pedido, USUARIO)).isSameAs(repetida);
        verify(registroVenta, times(2)).registrar(eq(pedido), any());
    }
}
