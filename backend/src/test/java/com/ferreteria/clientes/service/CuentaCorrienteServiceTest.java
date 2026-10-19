package com.ferreteria.clientes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.ferreteria.clientes.CuentaFalsa;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta.AsientoVenta;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta.CargoPago;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta.CupoCuenta;
import com.ferreteria.clientes.model.MovimientoCuentaCliente;
import com.ferreteria.clientes.model.TipoMovimientoCuenta;
import com.ferreteria.clientes.repository.ClienteRepository;
import com.ferreteria.clientes.repository.MovimientoCuentaClienteRepository;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** CC-001, CC-002, CC-004, CC-005, CC-006, CC-008, CC-009: el servicio que escribe el saldo y el libro, con mocks. */
@ExtendWith(MockitoExtension.class)
class CuentaCorrienteServiceTest {

    private static final long FERRETERIA = 7L;
    private static final ZoneId ZONA = ZoneId.of("America/Argentina/Buenos_Aires");
    private static final Clock RELOJ = Clock.fixed(Instant.parse("2026-10-18T14:30:00Z"), ZONA);
    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 18, 11, 30);

    @Mock
    ClienteRepository clientes;

    @Mock
    MovimientoCuentaClienteRepository libro;

    @Captor
    ArgumentCaptor<List<MovimientoCuentaCliente>> guardados;

    CuentaCorrienteService servicio;

    @BeforeEach
    void preparar() {
        servicio = new CuentaCorrienteService(clientes, libro, RELOJ);
        TenantContext.setCurrentFerreteriaId(FERRETERIA);
    }

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    private static BigDecimal d(String valor) {
        return new BigDecimal(valor);
    }

    // ---- verificarCupo ----

    @Test
    @DisplayName("sin límite la cuenta no está habilitada: sin disponible y sin exceso")
    void noHabilitada() {
        when(clientes.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de(null, "0.00")));

        CupoCuenta cupo = servicio.verificarCupo(5L, d("100.00"));

        assertThat(cupo.habilitada()).isFalse();
        assertThat(cupo.limite()).isNull();
        assertThat(cupo.disponible()).isNull();
        assertThat(cupo.excede()).isFalse();
    }

    @Test
    @DisplayName("la venta que llega justo al límite alcanza; por un centavo más excede y dice cuánto")
    void justoYPorUnCentavo() {
        when(clientes.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de("1000.00", "400.00")));

        CupoCuenta justo = servicio.verificarCupo(5L, d("600.00"));
        CupoCuenta pasado = servicio.verificarCupo(5L, d("600.01"));

        assertThat(justo.habilitada()).isTrue();
        assertThat(justo.excede()).isFalse();
        assertThat(justo.exceso()).isEqualByComparingTo("0");
        assertThat(justo.disponible()).isEqualByComparingTo("600.00");
        assertThat(justo.saldo()).isEqualByComparingTo("400.00");
        assertThat(justo.limite()).isEqualByComparingTo("1000.00");
        assertThat(pasado.excede()).isTrue();
        assertThat(pasado.exceso()).isEqualByComparingTo("0.01");
    }

    @Test
    @DisplayName("con saldo a favor el disponible supera al límite; con límite 0 cualquier venta excede")
    void saldoAFavorYLimiteCero() {
        when(clientes.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de("1000.00", "-200.00")));
        when(clientes.bloquearCuentaScoped(6L)).thenReturn(Optional.of(CuentaFalsa.de("0.00", "0.00")));

        CupoCuenta aFavor = servicio.verificarCupo(5L, d("1300.00"));
        CupoCuenta sinCredito = servicio.verificarCupo(6L, d("0.01"));

        assertThat(aFavor.disponible()).isEqualByComparingTo("1200.00");
        assertThat(aFavor.excede()).isTrue();
        assertThat(aFavor.exceso()).isEqualByComparingTo("100.00");
        assertThat(sinCredito.habilitada()).isTrue();
        assertThat(sinCredito.excede()).isTrue();
        assertThat(sinCredito.exceso()).isEqualByComparingTo("0.01");
    }

    @Test
    @DisplayName("un cliente que no existe o es de otra ferretería es 404")
    void clienteAjeno() {
        when(clientes.bloquearCuentaScoped(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.verificarCupo(99L, d("1.00"))).isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- asentarVenta ----

    @Test
    @DisplayName("asentarVenta relee la cuenta bloqueada, guarda UN movimiento VENTA por pago y suma el total UNA vez")
    void asentarUnMovimientoPorPago() {
        when(clientes.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de("1000.00", "0.00")));
        when(clientes.sumarSaldoScoped(anyLong(), any())).thenReturn(1);

        servicio.asentarVenta(new AsientoVenta(5L, 20L,
                List.of(new CargoPago(31L, d("300.00")), new CargoPago(32L, d("150.50"))), 9L, null));

        var orden = inOrder(clientes, libro);
        orden.verify(clientes).bloquearCuentaScoped(5L);
        orden.verify(libro).saveAll(guardados.capture());
        orden.verify(clientes).sumarSaldoScoped(5L, d("450.50"));
        verify(clientes, org.mockito.Mockito.times(1)).sumarSaldoScoped(anyLong(), any());
        assertThat(guardados.getValue()).hasSize(2);
        MovimientoCuentaCliente primero = guardados.getValue().get(0);
        assertThat(primero.getTipo()).isEqualTo(TipoMovimientoCuenta.VENTA);
        assertThat(primero.getFerreteriaId()).isEqualTo(FERRETERIA);
        assertThat(primero.getClienteId()).isEqualTo(5L);
        assertThat(primero.getComprobanteId()).isEqualTo(20L);
        assertThat(primero.getPagoId()).isEqualTo(31L);
        assertThat(primero.getDebe()).isEqualByComparingTo("300.00");
        assertThat(primero.getHaber()).isEqualByComparingTo("0");
        assertThat(primero.getFecha()).isEqualTo(AHORA);
        assertThat(primero.getUsuarioId()).isEqualTo(9L);
        assertThat(guardados.getValue().get(1).getPagoId()).isEqualTo(32L);
        assertThat(guardados.getValue().get(1).getDebe()).isEqualByComparingTo("150.50");
    }

    @Test
    @DisplayName("sin autorizador el libro queda sin marca de exceso; con autorizador, marcado y firmado")
    void autorizadoPor() {
        when(clientes.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de("100.00", "0.00")));
        when(clientes.sumarSaldoScoped(anyLong(), any())).thenReturn(1);

        servicio.asentarVenta(new AsientoVenta(5L, 20L, List.of(new CargoPago(31L, d("50.00"))), 9L, null));
        servicio.asentarVenta(new AsientoVenta(5L, 21L, List.of(new CargoPago(32L, d("500.00"))), 9L, 3L));

        verify(libro, org.mockito.Mockito.times(2)).saveAll(guardados.capture());
        MovimientoCuentaCliente sinExceso = guardados.getAllValues().get(0).get(0);
        MovimientoCuentaCliente conExceso = guardados.getAllValues().get(1).get(0);
        assertThat(sinExceso.isExcedioLimite()).isFalse();
        assertThat(sinExceso.getAutorizadoPor()).isNull();
        assertThat(conExceso.isExcedioLimite()).isTrue();
        assertThat(conExceso.getAutorizadoPor()).isEqualTo(3L);
    }

    @Test
    @DisplayName("pagos vacíos, nulos o con monto no positivo son un error de programación: IllegalArgumentException sin tocar nada")
    void pagosInvalidos() {
        assertThatThrownBy(() -> servicio.asentarVenta(new AsientoVenta(5L, 20L, List.of(), 9L, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servicio.asentarVenta(new AsientoVenta(5L, 20L, null, 9L, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servicio.asentarVenta(
                new AsientoVenta(5L, 20L, List.of(new CargoPago(31L, d("0.00"))), 9L, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servicio.asentarVenta(
                new AsientoVenta(5L, 20L, List.of(new CargoPago(31L, d("10.00")), new CargoPago(32L, d("-1.00"))), 9L, null)))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoMoreInteractions(clientes, libro);
    }

    @Test
    @DisplayName("asentar con un cliente de otra ferretería es 404 y no guarda ni suma nada")
    void asentarClienteAjeno() {
        when(clientes.bloquearCuentaScoped(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.asentarVenta(
                new AsientoVenta(99L, 20L, List.of(new CargoPago(31L, d("10.00"))), 9L, null)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(libro, never()).saveAll(any());
        verify(clientes, never()).sumarSaldoScoped(anyLong(), any());
    }
}
