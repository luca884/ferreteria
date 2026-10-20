package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.ferreteria.common.exception.SistemaOcupadoException;
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
            1L);

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
        assertThat(VentaService.class.getMethod("cobrar", ConfirmarVenta.class, Long.class, boolean.class)
                .isAnnotationPresent(Transactional.class)).isFalse();
    }

    @Test
    @DisplayName("una venta que sale bien se devuelve tal cual, con un solo intento")
    void caminoFeliz() {
        when(registroVenta.registrar(pedido, USUARIO, false)).thenReturn(nueva);

        assertThat(service.cobrar(pedido, USUARIO)).isSameAs(nueva);
        verify(registroVenta, times(1)).registrar(pedido, USUARIO, false);
    }

    @Test
    @DisplayName("un deadlock se reintenta y la venta sale en el segundo intento")
    void reintentaUnDeadlock() {
        when(registroVenta.registrar(pedido, USUARIO, false))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenReturn(nueva);

        assertThat(service.cobrar(pedido, USUARIO)).isSameAs(nueva);
        verify(registroVenta, times(2)).registrar(pedido, USUARIO, false);
    }

    @Test
    @DisplayName("tres deadlocks o timeouts seguidos: 3 intentos y despues avisa que el sistema esta ocupado (503), con el ultimo error como causa")
    void tresIntentosYSistemaOcupado() {
        CannotAcquireLockException ultimo = new CannotAcquireLockException("deadlock");
        when(registroVenta.registrar(pedido, USUARIO, false)).thenThrow(ultimo);

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO)).isInstanceOf(SistemaOcupadoException.class)
                .hasCause(ultimo).hasMessage("El sistema está ocupado, reintentá.");
        verify(registroVenta, times(3)).registrar(pedido, USUARIO, false);
    }

    @Test
    @DisplayName("un error que no es de lock se relanza tal cual, sin reintentos y sin disfrazarlo de sistema ocupado (sigue siendo 500)")
    void otroErrorNoEsSistemaOcupado() {
        IllegalStateException otro = new IllegalStateException("otra cosa");
        when(registroVenta.registrar(pedido, USUARIO, false)).thenThrow(otro);

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO)).isSameAs(otro);
        verify(registroVenta, times(1)).registrar(pedido, USUARIO, false);
    }

    @Test
    @DisplayName("un rechazo de la venta no se reintenta")
    void rechazoNoSeReintenta() {
        VentaRechazadaException rechazo = new VentaRechazadaException(Motivo.PRECIOS_CAMBIADOS, "cambio", List.of());
        when(registroVenta.registrar(pedido, USUARIO, false)).thenThrow(rechazo);

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO)).isSameAs(rechazo);
        verify(registroVenta, times(1)).registrar(pedido, USUARIO, false);
    }

    @Test
    @DisplayName("perder la carrera de la clave: busca la venta por clave y la devuelve repetida")
    void carreraDeClave() {
        when(registroVenta.registrar(pedido, USUARIO, false)).thenThrow(new DataIntegrityViolationException("uk_comprobante_clave"));
        when(registroVenta.repetida(pedido)).thenReturn(Optional.of(repetida));

        assertThat(service.cobrar(pedido, USUARIO)).isSameAs(repetida);
        verify(registroVenta, times(1)).registrar(pedido, USUARIO, false);
    }

    @Test
    @DisplayName("una violacion de integridad sin venta con esa clave se relanza")
    void integridadSinVenta() {
        DataIntegrityViolationException error = new DataIntegrityViolationException("otra restriccion");
        when(registroVenta.registrar(pedido, USUARIO, false)).thenThrow(error);
        when(registroVenta.repetida(pedido)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO)).isSameAs(error);
    }

    @Test
    @DisplayName("un valor fuera de rango (1264) no es una carrera de clave: se relanza sin buscar la venta")
    void fueraDeRangoNoEsCarrera() {
        DataIntegrityViolationException error = new DataIntegrityViolationException("could not execute statement",
                new java.sql.SQLException("Out of range value for column 'total'", "22003", 1264));
        when(registroVenta.registrar(pedido, USUARIO, false)).thenThrow(error);

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO)).isSameAs(error);
        verify(registroVenta, never()).repetida(any());
    }

    @Test
    @DisplayName("la carrera con otro contenido propaga el 422 de la busqueda por clave")
    void carreraConOtroContenido() {
        when(registroVenta.registrar(pedido, USUARIO, false)).thenThrow(new DataIntegrityViolationException("uk"));
        VentaRechazadaException otroContenido = new VentaRechazadaException(
                Motivo.CLAVE_CON_OTRO_CONTENIDO, "Esta venta ya se registró con otros datos", List.of());
        when(registroVenta.repetida(pedido)).thenThrow(otroContenido);

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO)).isSameAs(otroContenido);
    }

    @Test
    @DisplayName("un deadlock y despues la carrera de clave: reintenta y despues resuelve por clave")
    void deadlockYLuegoCarrera() {
        when(registroVenta.registrar(pedido, USUARIO, false))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenThrow(new DataIntegrityViolationException("uk"));
        when(registroVenta.repetida(pedido)).thenReturn(Optional.of(repetida));

        assertThat(service.cobrar(pedido, USUARIO)).isSameAs(repetida);
        verify(registroVenta, times(2)).registrar(eq(pedido), any(), eq(false));
    }

    // ---------------------------------------------------------------- cuenta corriente (CC-005, CC-009)

    static VentaRechazadaException limite() {
        return VentaRechazadaException.limiteCuentaCorriente(VentaRechazadaException.ExcesoLimite.sinCifras());
    }

    @Test
    @DisplayName("el rol del Dueño llega a RegistroVenta; la sobrecarga de 2 argumentos es como Vendedor")
    void rolDelUsuario() {
        when(registroVenta.registrar(pedido, USUARIO, true)).thenReturn(nueva);
        when(registroVenta.registrar(pedido, USUARIO, false)).thenReturn(repetida);

        assertThat(service.cobrar(pedido, USUARIO, true)).isSameAs(nueva);
        assertThat(service.cobrar(pedido, USUARIO)).isSameAs(repetida);
    }

    @Test
    @DisplayName("un rechazo por LIMITE con la clave ya registrada (el doble envio con limite justo) devuelve la venta original")
    void limiteConLaVentaYaRegistrada() {
        when(registroVenta.registrar(pedido, USUARIO, false)).thenThrow(limite());
        when(registroVenta.repetida(pedido)).thenReturn(Optional.of(repetida));

        assertThat(service.cobrar(pedido, USUARIO)).isSameAs(repetida);
        verify(registroVenta, times(1)).registrar(pedido, USUARIO, false);
    }

    @Test
    @DisplayName("un rechazo por LIMITE sin venta con esa clave se relanza tal cual: es un rechazo de verdad")
    void limiteSinVentaPrevia() {
        VentaRechazadaException rechazo = limite();
        when(registroVenta.registrar(pedido, USUARIO, true)).thenThrow(rechazo);
        when(registroVenta.repetida(pedido)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO, true)).isSameAs(rechazo);
        verify(registroVenta, times(1)).registrar(pedido, USUARIO, true);
    }

    @Test
    @DisplayName("un rechazo por LIMITE con la clave usada con otro contenido propaga CLAVE_CON_OTRO_CONTENIDO")
    void limiteConClaveDeOtroContenido() {
        when(registroVenta.registrar(pedido, USUARIO, false)).thenThrow(limite());
        VentaRechazadaException otroContenido = new VentaRechazadaException(
                Motivo.CLAVE_CON_OTRO_CONTENIDO, "Esta venta ya se registró con otros datos", List.of());
        when(registroVenta.repetida(pedido)).thenThrow(otroContenido);

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO)).isSameAs(otroContenido);
    }

    @Test
    @DisplayName("los demás rechazos (cuenta no habilitada, precios) NO buscan la venta repetida")
    void otrosMotivosNoDisparanElFallback() {
        VentaRechazadaException noHabilitada = VentaRechazadaException.cuentaNoHabilitada("Norte SRL");
        when(registroVenta.registrar(pedido, USUARIO, false)).thenThrow(noHabilitada);

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO)).isSameAs(noHabilitada);

        VentaRechazadaException precios = new VentaRechazadaException(Motivo.PRECIOS_CAMBIADOS, "cambio", List.of());
        when(registroVenta.registrar(pedido, USUARIO, true)).thenThrow(precios);

        assertThatThrownBy(() -> service.cobrar(pedido, USUARIO, true)).isSameAs(precios);
        verify(registroVenta, never()).repetida(any());
    }

    @Test
    @DisplayName("un deadlock y despues un rechazo por LIMITE con la venta ya registrada: reintenta y despues devuelve la original")
    void deadlockYLuegoLimite() {
        when(registroVenta.registrar(pedido, USUARIO, false))
                .thenThrow(new CannotAcquireLockException("deadlock"))
                .thenThrow(limite());
        when(registroVenta.repetida(pedido)).thenReturn(Optional.of(repetida));

        assertThat(service.cobrar(pedido, USUARIO)).isSameAs(repetida);
        verify(registroVenta, times(2)).registrar(pedido, USUARIO, false);
    }
}
