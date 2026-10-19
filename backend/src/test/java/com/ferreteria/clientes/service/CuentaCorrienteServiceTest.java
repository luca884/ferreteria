package com.ferreteria.clientes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.ferreteria.clientes.CuentaFalsa;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta.AsientoVenta;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta.CargoPago;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta.CupoCuenta;
import com.ferreteria.clientes.model.Cliente;
import com.ferreteria.clientes.model.CondicionIvaCliente;
import com.ferreteria.clientes.model.MovimientoCuentaCliente;
import com.ferreteria.clientes.model.TipoMovimientoCuenta;
import com.ferreteria.clientes.repository.ClienteRepository;
import com.ferreteria.clientes.repository.MovimientoCuentaClienteRepository;
import com.ferreteria.common.exception.DatosInvalidosException;
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

/**
 * CC-001 a CC-009 y CC-003, CC-004, CC-007, CC-018: el servicio que escribe el saldo y el libro (con mocks), más la consulta
 * de la cuenta por rol y el cambio del límite.
 */
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

    // ---- CupoCuenta.alcanza (CC-004) ----

    @Test
    @DisplayName("alcanza = habilitada y no excede: sin habilitar o pasándose no alcanza; justo en el límite sí")
    void alcanzaDelCupo() {
        when(clientes.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de("1000.00", "400.00")));
        when(clientes.bloquearCuentaScoped(6L)).thenReturn(Optional.of(CuentaFalsa.de(null, "0.00")));

        assertThat(servicio.verificarCupo(5L, d("600.00")).alcanza()).isTrue();
        assertThat(servicio.verificarCupo(5L, d("600.01")).alcanza()).isFalse();
        assertThat(servicio.verificarCupo(6L, d("1.00")).alcanza()).isFalse();
    }

    // ---- consultar (CC-003, CC-004, CC-007) ----

    private static Cliente clienteCon(String limite, String saldo) {
        Cliente c = mock(Cliente.class);
        when(c.getLimiteCuentaCorriente()).thenReturn(limite == null ? null : new BigDecimal(limite));
        when(c.getSaldo()).thenReturn(new BigDecimal(saldo));
        return c;
    }

    @Test
    @DisplayName("consultar al Dueño con importe: habilitada, alcanza, límite, saldo y disponible")
    void consultarDueno() {
        Cliente cliente = clienteCon("1000.00", "400.00");
        when(clientes.findScoped(5L)).thenReturn(Optional.of(cliente));

        CuentaDelCliente alcanza = servicio.consultar(5L, d("500.00"), true);
        CuentaDelCliente noAlcanza = servicio.consultar(5L, d("700.00"), true);

        assertThat(alcanza.habilitada()).isTrue();
        assertThat(alcanza.alcanza()).isTrue();
        assertThat(alcanza.limite()).isEqualByComparingTo("1000.00");
        assertThat(alcanza.saldo()).isEqualByComparingTo("400.00");
        assertThat(alcanza.disponible()).isEqualByComparingTo("600.00");
        assertThat(noAlcanza.alcanza()).isFalse();
    }

    @Test
    @DisplayName("consultar al Vendedor: solo habilitada y alcanza, sin ninguna cifra")
    void consultarVendedor() {
        Cliente cliente = clienteCon("1000.00", "400.00");
        when(clientes.findScoped(5L)).thenReturn(Optional.of(cliente));

        CuentaDelCliente cuenta = servicio.consultar(5L, d("500.00"), false);

        assertThat(cuenta.habilitada()).isTrue();
        assertThat(cuenta.alcanza()).isTrue();
        assertThat(cuenta.limite()).isNull();
        assertThat(cuenta.saldo()).isNull();
        assertThat(cuenta.disponible()).isNull();
    }

    @Test
    @DisplayName("sin importe no hay «alcanza» (ni para el Dueño ni para el Vendedor)")
    void consultarSinImporte() {
        Cliente cliente = clienteCon("1000.00", "400.00");
        when(clientes.findScoped(5L)).thenReturn(Optional.of(cliente));

        assertThat(servicio.consultar(5L, null, true).alcanza()).isNull();
        assertThat(servicio.consultar(5L, null, false).alcanza()).isNull();
        assertThat(servicio.consultar(5L, null, true).disponible()).isEqualByComparingTo("600.00");
    }

    @Test
    @DisplayName("límite NULL: no habilitada y alcanza=false con importe; el Dueño ve el saldo y no el límite ni el disponible")
    void consultarNoHabilitada() {
        Cliente cliente = clienteCon(null, "250.00");
        when(clientes.findScoped(5L)).thenReturn(Optional.of(cliente));

        CuentaDelCliente dueno = servicio.consultar(5L, d("1.00"), true);
        CuentaDelCliente vendedor = servicio.consultar(5L, d("1.00"), false);

        assertThat(dueno.habilitada()).isFalse();
        assertThat(dueno.alcanza()).isFalse();
        assertThat(dueno.limite()).isNull();
        assertThat(dueno.disponible()).isNull();
        assertThat(dueno.saldo()).isEqualByComparingTo("250.00");
        assertThat(vendedor.habilitada()).isFalse();
        assertThat(vendedor.alcanza()).isFalse();
        assertThat(vendedor.saldo()).isNull();
    }

    @Test
    @DisplayName("si el límite bajó del saldo el disponible es negativo y ninguna venta alcanza, ni la de importe 0")
    void consultarLimiteBajoElSaldo() {
        Cliente cliente = clienteCon("100.00", "300.00");
        when(clientes.findScoped(5L)).thenReturn(Optional.of(cliente));

        CuentaDelCliente cuenta = servicio.consultar(5L, d("0.00"), true);

        assertThat(cuenta.disponible()).isEqualByComparingTo("-200.00");
        assertThat(cuenta.alcanza()).isFalse();
    }

    @Test
    @DisplayName("consultar un cliente ajeno o inexistente es 404; un importe negativo es 400 en importe")
    void consultarAjenoYNegativo() {
        when(clientes.findScoped(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.consultar(99L, d("1.00"), true)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> servicio.consultar(5L, d("-1.00"), true))
                .isInstanceOfSatisfying(DatosInvalidosException.class,
                        e -> assertThat(e.errors()).containsKey("importe"));
    }

    // ---- cambiarLimite (CC-003, CC-018) ----

    private static Cliente clienteReal() {
        return Cliente.de(FERRETERIA, "Cliente", null, null, CondicionIvaCliente.CONSUMIDOR_FINAL, null, null, null, null);
    }

    @Test
    @DisplayName("cambiarLimite toma la cuenta, fija el límite y devuelve la vista del Dueño con el saldo FRESCO del bloqueo")
    void cambiarLimite() {
        Cliente c = clienteReal();
        when(clientes.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de("100.00", "300.00")));
        when(clientes.findScoped(5L)).thenReturn(Optional.of(c));

        CuentaDelCliente cuenta = servicio.cambiarLimite(5L, d("2000.00"));

        var orden = inOrder(clientes);
        orden.verify(clientes).bloquearCuentaScoped(5L);
        orden.verify(clientes).findScoped(5L);
        assertThat(c.getLimiteCuentaCorriente()).isEqualByComparingTo("2000.00");
        assertThat(cuenta.habilitada()).isTrue();
        assertThat(cuenta.limite()).isEqualByComparingTo("2000.00");
        assertThat(cuenta.saldo()).isEqualByComparingTo("300.00");
        assertThat(cuenta.disponible()).isEqualByComparingTo("1700.00");
        assertThat(cuenta.alcanza()).isNull();
    }

    @Test
    @DisplayName("cambiarLimite(null) deshabilita la cuenta, también con saldo distinto de cero")
    void deshabilitar() {
        Cliente c = clienteReal();
        c.cambiarLimite(d("500.00"));
        when(clientes.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de("500.00", "300.00")));
        when(clientes.findScoped(5L)).thenReturn(Optional.of(c));

        CuentaDelCliente cuenta = servicio.cambiarLimite(5L, null);

        assertThat(c.getLimiteCuentaCorriente()).isNull();
        assertThat(cuenta.habilitada()).isFalse();
        assertThat(cuenta.limite()).isNull();
        assertThat(cuenta.disponible()).isNull();
        assertThat(cuenta.saldo()).isEqualByComparingTo("300.00");
    }

    @Test
    @DisplayName("cambiarLimite(0) habilita la cuenta sin crédito")
    void limiteCero() {
        Cliente c = clienteReal();
        when(clientes.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de(null, "0.00")));
        when(clientes.findScoped(5L)).thenReturn(Optional.of(c));

        CuentaDelCliente cuenta = servicio.cambiarLimite(5L, d("0.00"));

        assertThat(c.getLimiteCuentaCorriente()).isEqualByComparingTo("0");
        assertThat(cuenta.habilitada()).isTrue();
        assertThat(cuenta.disponible()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("bajar el límite por debajo del saldo está permitido: el disponible queda negativo")
    void bajarPorDebajoDelSaldo() {
        Cliente c = clienteReal();
        when(clientes.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de("1000.00", "600.00")));
        when(clientes.findScoped(5L)).thenReturn(Optional.of(c));

        CuentaDelCliente cuenta = servicio.cambiarLimite(5L, d("100.00"));

        assertThat(cuenta.disponible()).isEqualByComparingTo("-500.00");
    }

    @Test
    @DisplayName("un límite negativo es un error y no cambia nada")
    void limiteNegativo() {
        Cliente c = clienteReal();
        c.cambiarLimite(d("500.00"));
        when(clientes.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de("500.00", "0.00")));
        when(clientes.findScoped(5L)).thenReturn(Optional.of(c));

        assertThatThrownBy(() -> servicio.cambiarLimite(5L, d("-1.00"))).isInstanceOf(IllegalArgumentException.class);

        assertThat(c.getLimiteCuentaCorriente()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("cambiar el límite de un cliente ajeno o inexistente es 404 y no lee nada más")
    void cambiarLimiteAjeno() {
        when(clientes.bloquearCuentaScoped(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.cambiarLimite(99L, d("10.00"))).isInstanceOf(ResourceNotFoundException.class);

        verify(clientes, never()).findScoped(anyLong());
    }
}
