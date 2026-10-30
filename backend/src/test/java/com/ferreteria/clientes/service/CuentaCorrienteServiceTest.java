package com.ferreteria.clientes.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.ferreteria.clientes.CuentaFalsa;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.AnulacionAsentada;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.AsientoAnulacion;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.AsientoCobro;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionAsentada;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionInvalidaException;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionInvalidaException.DeudaActual;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.Imputar;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta.AsientoVenta;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta.CargoPago;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta.CupoCuenta;
import com.ferreteria.clientes.model.Cliente;
import com.ferreteria.clientes.model.CondicionIvaCliente;
import com.ferreteria.clientes.model.Imputacion;
import com.ferreteria.clientes.model.MovimientoCuentaCliente;
import com.ferreteria.clientes.model.TipoMovimientoCuenta;
import com.ferreteria.clientes.repository.ClienteRepository;
import com.ferreteria.clientes.repository.ImputacionRepository;
import com.ferreteria.clientes.repository.ImputacionRepository.FilaImputada;
import com.ferreteria.clientes.repository.MovimientoCuentaClienteRepository;
import com.ferreteria.clientes.repository.MovimientoCuentaClienteRepository.DebitoDeComprobante;
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

    @Mock
    ImputacionRepository imputaciones;

    @Captor
    ArgumentCaptor<List<MovimientoCuentaCliente>> guardados;

    @Captor
    ArgumentCaptor<List<Imputacion>> imputacionesGuardadas;

    @Captor
    ArgumentCaptor<List<Long>> ids;

    CuentaCorrienteService servicio;

    @BeforeEach
    void preparar() {
        servicio = new CuentaCorrienteService(clientes, libro, imputaciones, RELOJ);
        TenantContext.setCurrentFerreteriaId(FERRETERIA);
        // GDS-20: sin Descuentos ni Recargos salvo que un test los pida (un mock devolvería null en una suma)
        lenient().when(libro.creditoDeDescuentosScoped(anyLong())).thenReturn(BigDecimal.ZERO);
        lenient().when(libro.creditoDeNotasDeCreditoScoped(anyLong())).thenReturn(BigDecimal.ZERO);
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

        servicio.asentarVenta(new AsientoVenta(5L, 20L, 77L,
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
    @DisplayName("cada movimiento VENTA guarda la referencia «Venta N.º <número del comprobante>» y ningún recibo (CC-001)")
    void asentarGuardaLaReferenciaDeLaVenta() {
        when(clientes.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de("1000.00", "0.00")));
        when(clientes.sumarSaldoScoped(anyLong(), any())).thenReturn(1);

        servicio.asentarVenta(new AsientoVenta(5L, 20L, 4321L,
                List.of(new CargoPago(31L, d("300.00")), new CargoPago(32L, d("150.50"))), 9L, null));
        servicio.asentarVenta(new AsientoVenta(5L, 21L, 8L, List.of(new CargoPago(33L, d("10.00"))), 9L, null));

        verify(libro, org.mockito.Mockito.times(2)).saveAll(guardados.capture());
        List<MovimientoCuentaCliente> primera = guardados.getAllValues().get(0);
        assertThat(primera).hasSize(2).allSatisfy(m -> {
            assertThat(m.getReferencia()).isEqualTo("Venta N.º 4321");
            assertThat(m.getReciboId()).isNull();
        });
        assertThat(guardados.getAllValues().get(1).get(0).getReferencia()).isEqualTo("Venta N.º 8");
    }

    @Test
    @DisplayName("sin autorizador el libro queda sin marca de exceso; con autorizador, marcado y firmado")
    void autorizadoPor() {
        when(clientes.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de("100.00", "0.00")));
        when(clientes.sumarSaldoScoped(anyLong(), any())).thenReturn(1);

        servicio.asentarVenta(new AsientoVenta(5L, 20L, 77L, List.of(new CargoPago(31L, d("50.00"))), 9L, null));
        servicio.asentarVenta(new AsientoVenta(5L, 21L, 78L, List.of(new CargoPago(32L, d("500.00"))), 9L, 3L));

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
        assertThatThrownBy(() -> servicio.asentarVenta(new AsientoVenta(5L, 20L, 77L, List.of(), 9L, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servicio.asentarVenta(new AsientoVenta(5L, 20L, 77L, null, 9L, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servicio.asentarVenta(
                new AsientoVenta(5L, 20L, 77L, List.of(new CargoPago(31L, d("0.00"))), 9L, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servicio.asentarVenta(
                new AsientoVenta(5L, 20L, 77L, List.of(new CargoPago(31L, d("10.00")), new CargoPago(32L, d("-1.00"))), 9L, null)))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoMoreInteractions(clientes, libro);
    }

    @Test
    @DisplayName("asentar con un cliente de otra ferretería es 404 y no guarda ni suma nada")
    void asentarClienteAjeno() {
        when(clientes.bloquearCuentaScoped(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.asentarVenta(
                new AsientoVenta(99L, 20L, 77L, List.of(new CargoPago(31L, d("10.00"))), 9L, null)))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(libro, never()).saveAll(any());
        verify(clientes, never()).sumarSaldoScoped(anyLong(), any());
    }

    @Test
    @DisplayName("si el UPDATE del saldo no afecta exactamente una fila falla fuerte (IllegalStateException): libro y saldo no pueden divergir en silencio")
    void saldoQueNoSeActualizaFallaFuerte() {
        when(clientes.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de("1000.00", "0.00")));
        AsientoVenta asiento = new AsientoVenta(5L, 20L, 77L, List.of(new CargoPago(31L, d("100.00"))), 9L, null);

        when(clientes.sumarSaldoScoped(5L, d("100.00"))).thenReturn(0);
        assertThatThrownBy(() -> servicio.asentarVenta(asiento))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("saldo");

        when(clientes.sumarSaldoScoped(5L, d("100.00"))).thenReturn(2);
        assertThatThrownBy(() -> servicio.asentarVenta(asiento))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("saldo");
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

    // ---- asentarCobro (CCT3-206: CC-026, CC-027, CC-030, CC-033, CC-037, CC-042, CC-001) ----

    private record DebitoFalso(Long comprobanteId, Long primerMovimientoId, String referencia, LocalDateTime fecha,
            BigDecimal debe) implements DebitoDeComprobante {
        @Override public Long getComprobanteId() { return comprobanteId; }
        @Override public Long getAjusteId() { return null; } // GDS-20: estos tests son de ventas, sin Recargos
        @Override public Long getPrimerMovimientoId() { return primerMovimientoId; }
        @Override public String getReferencia() { return referencia; }
        @Override public LocalDateTime getFecha() { return fecha; }
        @Override public BigDecimal getDebe() { return debe; }
    }

    private record FilaFalsa(Long comprobanteId, BigDecimal importe) implements FilaImputada {
        @Override public Long getComprobanteId() { return comprobanteId; }
        @Override public BigDecimal getImporte() { return importe; }
    }

    private static DebitoFalso debito(long comprobante, long primerMovimiento, String debe) {
        return new DebitoFalso(comprobante, primerMovimiento, "Venta N.º " + comprobante, AHORA.minusDays(1), d(debe));
    }

    private static FilaFalsa fila(long comprobante, String importe) {
        return new FilaFalsa(comprobante, d(importe));
    }

    private static AsientoCobro cobro(String total, Imputar... imputaciones) {
        return new AsientoCobro(5L, 61L, 12L, d(total), List.of(imputaciones), 9L);
    }

    private static Imputar imputar(long comprobante, String importe) {
        return new Imputar(comprobante, d(importe));
    }

    /** Un cliente con cuenta, comprobantes 20 (debe 500) y 21 (debe 400), sin nada imputado todavía. */
    private void cuentaConDosComprobantes() {
        when(clientes.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de("1000.00", "900.00")));
        when(libro.debitosDeComprobantesScoped(eq(5L), any())).thenReturn(List.of(debito(20, 100, "500.00"), debito(21, 101, "400.00")));
        when(libro.idsDePartidasScoped(eq(5L), any())).thenReturn(List.of(100L, 101L));
        when(libro.bloquearPartidasScoped(eq(5L), any())).thenReturn(List.of(100L, 101L));
        when(imputaciones.imputadoBloqueadoScoped(eq(5L), any())).thenReturn(List.of());
        when(clientes.sumarSaldoScoped(anyLong(), any())).thenReturn(1);
    }

    @Test
    @DisplayName("asentarCobro respeta el orden: cuenta bloqueada → débitos → ids de partidas → partidas → imputado bloqueado → imputaciones → libro → saldo")
    void cobroEnOrden() {
        cuentaConDosComprobantes();

        servicio.asentarCobro(cobro("600.00", imputar(20, "300.00"), imputar(21, "200.00")));

        var orden = inOrder(clientes, libro, imputaciones);
        orden.verify(clientes).bloquearCuentaScoped(5L);
        orden.verify(libro).debitosDeComprobantesScoped(eq(5L), any());
        orden.verify(libro).idsDePartidasScoped(eq(5L), any());
        orden.verify(libro).bloquearPartidasScoped(eq(5L), any());
        orden.verify(imputaciones).imputadoBloqueadoScoped(eq(5L), any());
        orden.verify(imputaciones).saveAll(any());
        orden.verify(libro).save(any(MovimientoCuentaCliente.class));
        orden.verify(clientes).sumarSaldoScoped(5L, d("-600.00"));
    }

    @Test
    @DisplayName("imputaciones pedidas en orden DESCENDENTE: las partidas se bloquean con los ids ascendentes (sin deadlock entre cobranzas)")
    void partidasEnOrdenAscendente() {
        when(clientes.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de("1000.00", "900.00")));
        when(libro.debitosDeComprobantesScoped(eq(5L), any())).thenReturn(
                List.of(debito(22, 120, "100.00"), debito(21, 110, "100.00"), debito(20, 100, "100.00")));
        when(libro.idsDePartidasScoped(eq(5L), any())).thenReturn(List.of(120L, 110L, 100L)); // desordenados a propósito
        when(libro.bloquearPartidasScoped(eq(5L), any())).thenReturn(List.of(100L, 110L, 120L));
        when(imputaciones.imputadoBloqueadoScoped(eq(5L), any())).thenReturn(List.of());
        when(clientes.sumarSaldoScoped(anyLong(), any())).thenReturn(1);

        servicio.asentarCobro(cobro("300.00", imputar(22, "100.00"), imputar(21, "100.00"), imputar(20, "100.00")));

        verify(libro).bloquearPartidasScoped(eq(5L), ids.capture());
        assertThat(ids.getValue()).containsExactly(100L, 110L, 120L);
        verify(imputaciones).imputadoBloqueadoScoped(eq(5L), ids.capture());
        assertThat(ids.getValue()).as("también el imputado se bloquea por comprobante ascendente").containsExactly(20L, 21L, 22L);
    }

    @Test
    @DisplayName("las imputaciones se guardan por comprobante ascendente y UN solo COBRO (haber = total, aunque haya 3 imputaciones) baja el saldo UNA vez")
    void imputacionesYUnSoloCobro() {
        when(clientes.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de("1000.00", "900.00")));
        when(libro.debitosDeComprobantesScoped(eq(5L), any())).thenReturn(
                List.of(debito(22, 120, "100.00"), debito(21, 110, "100.00"), debito(20, 100, "100.00")));
        when(libro.idsDePartidasScoped(eq(5L), any())).thenReturn(List.of(100L, 110L, 120L));
        when(imputaciones.imputadoBloqueadoScoped(eq(5L), any())).thenReturn(List.of());
        when(clientes.sumarSaldoScoped(anyLong(), any())).thenReturn(1);

        List<ImputacionAsentada> asentadas = servicio
                .asentarCobro(cobro("700.00", imputar(22, "30.00"), imputar(20, "50.00"), imputar(21, "40.00")));

        verify(imputaciones).saveAll(imputacionesGuardadas.capture());
        assertThat(imputacionesGuardadas.getValue()).extracting(Imputacion::getComprobanteId).containsExactly(20L, 21L, 22L);
        assertThat(imputacionesGuardadas.getValue()).allSatisfy(i -> {
            assertThat(i.getFerreteriaId()).isEqualTo(FERRETERIA);
            assertThat(i.getClienteId()).isEqualTo(5L);
            assertThat(i.getReciboId()).isEqualTo(61L);
        });
        ArgumentCaptor<MovimientoCuentaCliente> cobroGuardado = ArgumentCaptor.forClass(MovimientoCuentaCliente.class);
        verify(libro, org.mockito.Mockito.times(1)).save(cobroGuardado.capture());
        MovimientoCuentaCliente movimiento = cobroGuardado.getValue();
        assertThat(movimiento.getTipo()).isEqualTo(TipoMovimientoCuenta.COBRO);
        assertThat(movimiento.getReciboId()).isEqualTo(61L);
        assertThat(movimiento.getReferencia()).isEqualTo("Recibo N.º 12");
        assertThat(movimiento.getHaber()).isEqualByComparingTo("700.00");
        assertThat(movimiento.getDebe()).isEqualByComparingTo("0");
        assertThat(movimiento.getClienteId()).isEqualTo(5L);
        assertThat(movimiento.getFerreteriaId()).isEqualTo(FERRETERIA);
        assertThat(movimiento.getFecha()).isEqualTo(AHORA);
        assertThat(movimiento.getUsuarioId()).isEqualTo(9L);
        verify(clientes, org.mockito.Mockito.times(1)).sumarSaldoScoped(anyLong(), any());
        verify(clientes).sumarSaldoScoped(5L, d("-700.00"));
        assertThat(asentadas).extracting(ImputacionAsentada::comprobanteId).containsExactly(20L, 21L, 22L);
        assertThat(asentadas).extracting(ImputacionAsentada::referencia)
                .containsExactly("Venta N.º 20", "Venta N.º 21", "Venta N.º 22");
        assertThat(asentadas).extracting(ImputacionAsentada::importe).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(d("50.00"), d("40.00"), d("30.00"));
    }

    @Test
    @DisplayName("si el UPDATE del saldo no afecta exactamente una fila, el cobro falla fuerte (IllegalStateException)")
    void cobroConSaldoQueNoSeActualiza() {
        cuentaConDosComprobantes();
        AsientoCobro asiento = cobro("100.00", imputar(20, "100.00"));

        when(clientes.sumarSaldoScoped(5L, d("-100.00"))).thenReturn(0);
        assertThatThrownBy(() -> servicio.asentarCobro(asiento)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("saldo");

        when(clientes.sumarSaldoScoped(5L, d("-100.00"))).thenReturn(2);
        assertThatThrownBy(() -> servicio.asentarCobro(asiento)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("saldo");
    }

    @Test
    @DisplayName("un comprobante sin débito en la cuenta (ajeno, de otro cliente, inexistente) es 404 y no escribe nada")
    void comprobanteSinDebitoEsNoEncontrado() {
        when(clientes.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de("1000.00", "900.00")));
        when(libro.debitosDeComprobantesScoped(eq(5L), any())).thenReturn(List.of(debito(20, 100, "500.00"))); // falta el 99

        assertThatThrownBy(() -> servicio.asentarCobro(cobro("100.00", imputar(20, "50.00"), imputar(99, "50.00"))))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("Comprobante no encontrado en la cuenta del cliente");

        verify(libro, never()).bloquearPartidasScoped(anyLong(), any());
        verify(imputaciones, never()).imputadoBloqueadoScoped(anyLong(), any());
        verify(imputaciones, never()).saveAll(any());
        verify(libro, never()).save(any(MovimientoCuentaCliente.class));
        verify(clientes, never()).sumarSaldoScoped(anyLong(), any());
    }

    @Test
    @DisplayName("pendiente menor que el importe (incluido pendiente 0) → EXCEDE_DEUDA con TODAS las deudas actuales de los afectados, sin escribir")
    void excedeLaDeuda() {
        when(clientes.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de("1000.00", "900.00")));
        when(libro.debitosDeComprobantesScoped(eq(5L), any())).thenReturn(
                List.of(debito(20, 100, "500.00"), debito(21, 101, "400.00"), debito(22, 102, "1000.00")));
        when(libro.idsDePartidasScoped(eq(5L), any())).thenReturn(List.of(100L, 101L, 102L));
        when(imputaciones.imputadoBloqueadoScoped(eq(5L), any())).thenReturn(
                List.of(fila(20, "400.00"), fila(21, "250.00"), fila(21, "150.00"))); // pendientes: 20 → 100, 21 → 0, 22 → 1000

        assertThatThrownBy(() -> servicio.asentarCobro(
                cobro("400.00", imputar(20, "300.00"), imputar(21, "50.00"), imputar(22, "50.00"))))
                .isInstanceOfSatisfying(ImputacionInvalidaException.class, e -> {
                    assertThat(e.motivo()).isEqualTo(ImputacionInvalidaException.Motivo.EXCEDE_DEUDA);
                    assertThat(e.deudas()).extracting(DeudaActual::comprobanteId).containsExactly(20L, 21L);
                    assertThat(e.deudas()).extracting(DeudaActual::referencia).containsExactly("Venta N.º 20", "Venta N.º 21");
                    assertThat(e.deudas()).extracting(DeudaActual::pendiente).usingElementComparator(BigDecimal::compareTo)
                            .containsExactly(d("100.00"), d("0.00"));
                });

        verify(imputaciones, never()).saveAll(any());
        verify(libro, never()).save(any(MovimientoCuentaCliente.class));
        verify(clientes, never()).sumarSaldoScoped(anyLong(), any());
    }

    @Test
    @DisplayName("un centavo de más sobre el pendiente excede; justo el pendiente (otra escala) no")
    void unCentavoDeMas() {
        when(clientes.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de("1000.00", "900.00")));
        when(libro.debitosDeComprobantesScoped(eq(5L), any())).thenReturn(List.of(debito(20, 100, "100.10")));
        when(libro.idsDePartidasScoped(eq(5L), any())).thenReturn(List.of(100L));
        when(imputaciones.imputadoBloqueadoScoped(eq(5L), any())).thenReturn(List.of());
        when(clientes.sumarSaldoScoped(anyLong(), any())).thenReturn(1);

        assertThatThrownBy(() -> servicio.asentarCobro(cobro("100.11", imputar(20, "100.11"))))
                .isInstanceOf(ImputacionInvalidaException.class);
        servicio.asentarCobro(cobro("100.1", imputar(20, "100.1"))); // 100,1 vs pendiente 100,10: mismo valor

        verify(libro, org.mockito.Mockito.times(1)).save(any(MovimientoCuentaCliente.class));
    }

    @Test
    @DisplayName("sin imputaciones (adelanto o todo a cuenta): no mira débitos ni partidas ni imputado; escribe el COBRO y baja el saldo, sin chequear saldo ni límite")
    void adelantoSinImputaciones() {
        when(clientes.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de(null, "-200.00"))); // límite NULL y saldo a favor
        when(clientes.sumarSaldoScoped(anyLong(), any())).thenReturn(1);

        List<ImputacionAsentada> asentadas = servicio.asentarCobro(cobro("500.00"));

        assertThat(asentadas).isEmpty();
        verify(clientes).bloquearCuentaScoped(5L);
        ArgumentCaptor<MovimientoCuentaCliente> cobroGuardado = ArgumentCaptor.forClass(MovimientoCuentaCliente.class);
        verify(libro).save(cobroGuardado.capture());
        assertThat(cobroGuardado.getValue().getHaber()).isEqualByComparingTo("500.00");
        verify(clientes).sumarSaldoScoped(5L, d("-500.00"));
        verify(libro, never()).debitosDeComprobantesScoped(anyLong(), any());
        verify(libro, never()).idsDePartidasScoped(anyLong(), any());
        verify(libro, never()).bloquearPartidasScoped(anyLong(), any());
        verify(imputaciones, never()).imputadoBloqueadoScoped(anyLong(), any());
        verify(imputaciones, never()).saveAll(any());
    }

    @Test
    @DisplayName("el cobro no depende del límite: con límite 0 y deuda cobra igual (CC-027)")
    void cobroConLimiteCero() {
        when(clientes.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de("0.00", "800.00")));
        when(clientes.sumarSaldoScoped(anyLong(), any())).thenReturn(1);

        servicio.asentarCobro(cobro("300.00"));

        verify(clientes).sumarSaldoScoped(5L, d("-300.00"));
    }

    @Test
    @DisplayName("argumentos mal formados son un error de programación (IllegalArgumentException) y no tocan nada")
    void argumentosMalFormados() {
        Imputar bien = imputar(20, "10.00");
        assertThatThrownBy(() -> servicio.asentarCobro(cobro("0.00"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servicio.asentarCobro(cobro("-5.00"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servicio.asentarCobro(new AsientoCobro(5L, 61L, 12L, null, List.of(), 9L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servicio.asentarCobro(cobro("100.001"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servicio.asentarCobro(cobro("100.00", imputar(20, "0.00")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servicio.asentarCobro(cobro("100.00", imputar(20, "-1.00")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servicio.asentarCobro(cobro("100.00", imputar(20, "1.005")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servicio.asentarCobro(cobro("100.00", bien, imputar(20, "5.00")))).as("comprobante repetido")
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servicio.asentarCobro(cobro("100.00", imputar(20, "60.00"), imputar(21, "40.01"))))
                .as("Σ imputaciones > total").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servicio.asentarCobro(new AsientoCobro(5L, 61L, 12L, d("100.00"), null, 9L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servicio.asentarCobro(new AsientoCobro(5L, 61L, 0L, d("100.00"), List.of(), 9L)))
                .as("número de recibo no positivo").isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(clientes, libro, imputaciones);
    }

    @Test
    @DisplayName("un cobro a un cliente de otra ferretería o inexistente es 404 y no escribe nada")
    void cobroClienteAjeno() {
        when(clientes.bloquearCuentaScoped(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.asentarCobro(cobro("100.00"))).isInstanceOf(ResourceNotFoundException.class);

        verify(libro, never()).save(any(MovimientoCuentaCliente.class));
        verify(clientes, never()).sumarSaldoScoped(anyLong(), any());
    }

    @Test
    @DisplayName("imputacionesDeRecibo devuelve cada imputación con la referencia de su comprobante, en el orden en que se asentaron")
    void imputacionesDeRecibo() {
        when(imputaciones.deReciboScoped(61L)).thenReturn(List.of(
                Imputacion.de(FERRETERIA, 5L, 61L, 21L, d("40.00")), Imputacion.de(FERRETERIA, 5L, 61L, 20L, d("50.00"))));
        when(libro.debitosDeComprobantesScoped(eq(5L), any())).thenReturn(List.of(debito(20, 100, "500.00"), debito(21, 101, "400.00")));

        List<ImputacionAsentada> asentadas = servicio.imputacionesDeRecibo(61L);

        assertThat(asentadas).extracting(ImputacionAsentada::comprobanteId).containsExactly(21L, 20L);
        assertThat(asentadas).extracting(ImputacionAsentada::referencia).containsExactly("Venta N.º 21", "Venta N.º 20");
        assertThat(asentadas).extracting(ImputacionAsentada::importe).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(d("40.00"), d("50.00"));
        verify(clientes, never()).bloquearCuentaScoped(anyLong());
    }

    @Test
    @DisplayName("imputacionesDeRecibo de un recibo sin imputaciones (a cuenta) es vacío y no consulta más")
    void imputacionesDeReciboVacio() {
        when(imputaciones.deReciboScoped(61L)).thenReturn(List.of());

        assertThat(servicio.imputacionesDeRecibo(61L)).isEmpty();

        verify(libro, never()).debitosDeComprobantesScoped(anyLong(), any());
    }

    // ---- asentarAnulacion (CCT3B1-205: CC-062, CC-063, CC-065, CC-068, CC-085, CC-102) ----

    private record FilaImputacionFalsa(Long id, Long comprobanteId, boolean vigente)
            implements ImputacionRepository.FilaDeImputacion {
        @Override public Long getId() { return id; }
        @Override public Long getComprobanteId() { return comprobanteId; }
        @Override public Long getAjusteDestinoId() { return null; } // GDS-20: estos tests son de comprobantes
        @Override public boolean getVigente() { return vigente; }
    }

    private static AsientoAnulacion anulacion(String total, String motivo) {
        return new AsientoAnulacion(5L, 61L, 12L, d(total), motivo, 9L);
    }

    private static MovimientoCuentaCliente cobroDelRecibo(String total) {
        return MovimientoCuentaCliente.cobro(FERRETERIA, 5L, 61L, "Recibo N.º 12", d(total), AHORA.minusDays(1), 9L);
    }

    /** Un cliente con el COBRO de 600 del recibo 61, sin ANULACION, y dos imputaciones (ids 31 y 30, a los comprobantes 21 y 20). */
    private void reciboConDosImputaciones() {
        when(clientes.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de("1000.00", "100.00")));
        when(libro.deReciboScoped(61L)).thenReturn(List.of(cobroDelRecibo("600.00")));
        when(imputaciones.idsDeReciboScoped(61L)).thenReturn(List.of(31L, 30L)); // desordenados a propósito
        // lo que sigue a la lectura bloqueada no se usa en los tests que rechazan antes: lenient
        lenient().when(imputaciones.bloquearDeReciboScoped(eq(5L), any()))
                .thenReturn(List.of(new FilaImputacionFalsa(30L, 20L, true), new FilaImputacionFalsa(31L, 21L, true)));
        lenient().when(imputaciones.desactivarDeReciboScoped(eq(5L), eq(61L), any())).thenReturn(2);
        stubGuardarElLibro();
        lenient().when(clientes.sumarSaldoScoped(anyLong(), any())).thenReturn(1);
    }

    /** {@code save} devuelve el movimiento con un id (como Spring Data tras el INSERT). */
    private void stubGuardarElLibro() {
        lenient().when(libro.save(any(MovimientoCuentaCliente.class))).thenAnswer(inv -> {
            MovimientoCuentaCliente m = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(m, "id", 99L);
            return m;
        });
    }

    @Test
    @DisplayName("asentarAnulacion respeta el orden: cuentaBloqueada → libro del recibo → ids → bloquear (ids ascendentes) → desactivar → save del libro → saldo +total")
    void anulacionEnOrden() {
        reciboConDosImputaciones();

        servicio.asentarAnulacion(anulacion("600.00", "Cobro mal cargado"));

        var orden = inOrder(clientes, libro, imputaciones);
        orden.verify(clientes).bloquearCuentaScoped(5L);
        orden.verify(libro).deReciboScoped(61L);
        orden.verify(imputaciones).idsDeReciboScoped(61L);
        orden.verify(imputaciones).bloquearDeReciboScoped(eq(5L), ids.capture());
        orden.verify(imputaciones).desactivarDeReciboScoped(eq(5L), eq(61L), any());
        orden.verify(libro).save(any(MovimientoCuentaCliente.class));
        orden.verify(clientes).sumarSaldoScoped(5L, d("600.00"));
        assertThat(ids.getValue()).as("se bloquea por PK en orden ascendente (sin deadlock entre anulaciones y cobranzas)")
                .containsExactly(30L, 31L);
        verify(imputaciones).desactivarDeReciboScoped(5L, 61L, List.of(30L, 31L));
    }

    @Test
    @DisplayName("un solo save del libro: ANULACION con debe = total, recibo, «Anulación Recibo N.º n», motivo recortado y usuario; el saldo sube UNA vez; devuelve el id y los comprobantes liberados")
    void anulacionAsientaUnContraAsiento() {
        reciboConDosImputaciones();

        AnulacionAsentada asentada = servicio.asentarAnulacion(anulacion("600.00", "  Cobro mal cargado  "));

        ArgumentCaptor<MovimientoCuentaCliente> guardado = ArgumentCaptor.forClass(MovimientoCuentaCliente.class);
        verify(libro, org.mockito.Mockito.times(1)).save(guardado.capture());
        MovimientoCuentaCliente m = guardado.getValue();
        assertThat(m.getTipo()).isEqualTo(TipoMovimientoCuenta.ANULACION);
        assertThat(m.getDebe()).isEqualByComparingTo("600.00");
        assertThat(m.getHaber()).isEqualByComparingTo("0");
        assertThat(m.getReciboId()).isEqualTo(61L);
        assertThat(m.getReferencia()).isEqualTo("Anulación Recibo N.º 12");
        assertThat(m.getMotivo()).isEqualTo("Cobro mal cargado");
        assertThat(m.getUsuarioId()).isEqualTo(9L);
        assertThat(m.getClienteId()).isEqualTo(5L);
        assertThat(m.getFerreteriaId()).isEqualTo(FERRETERIA);
        assertThat(m.getFecha()).isEqualTo(AHORA);
        verify(clientes, org.mockito.Mockito.times(1)).sumarSaldoScoped(anyLong(), any());
        verify(clientes).sumarSaldoScoped(5L, d("600.00"));
        assertThat(asentada.movimientoId()).isEqualTo(99L);
        assertThat(asentada.comprobantesLiberados()).containsExactly(20L, 21L);
    }

    @Test
    @DisplayName("comprobantesLiberados es distinct y ascendente aunque dos imputaciones del recibo apunten al mismo comprobante o lleguen desordenadas")
    void comprobantesLiberadosDistinctAscendente() {
        reciboConDosImputaciones();
        when(imputaciones.idsDeReciboScoped(61L)).thenReturn(List.of(32L, 30L, 31L));
        when(imputaciones.bloquearDeReciboScoped(eq(5L), any())).thenReturn(List.of(new FilaImputacionFalsa(30L, 25L, true),
                new FilaImputacionFalsa(31L, 20L, true), new FilaImputacionFalsa(32L, 25L, true)));
        when(imputaciones.desactivarDeReciboScoped(eq(5L), eq(61L), any())).thenReturn(3);

        AnulacionAsentada asentada = servicio.asentarAnulacion(anulacion("600.00", "Cobro mal cargado"));

        assertThat(asentada.comprobantesLiberados()).containsExactly(20L, 25L);
    }

    @Test
    @DisplayName("un adelanto (recibo sin imputaciones) no bloquea ni desactiva nada, pero asienta el contra-asiento y sube el saldo")
    void anulacionSinImputaciones() {
        when(clientes.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de(null, "-500.00")));
        when(libro.deReciboScoped(61L)).thenReturn(List.of(cobroDelRecibo("500.00")));
        when(imputaciones.idsDeReciboScoped(61L)).thenReturn(List.of());
        when(clientes.sumarSaldoScoped(anyLong(), any())).thenReturn(1);
        stubGuardarElLibro();

        AnulacionAsentada asentada = servicio.asentarAnulacion(anulacion("500.00", "Cobro mal cargado"));

        verify(imputaciones, never()).bloquearDeReciboScoped(anyLong(), any());
        verify(imputaciones, never()).desactivarDeReciboScoped(anyLong(), anyLong(), any());
        verify(libro).save(any(MovimientoCuentaCliente.class));
        verify(clientes).sumarSaldoScoped(5L, d("500.00"));
        assertThat(asentada.comprobantesLiberados()).isEmpty();
    }

    @Test
    @DisplayName("CC-065: la anulación no mira el límite ni si el cliente está activo (límite 0 y cliente desactivado: asienta igual)")
    void anulacionSinChequeoDeLimiteNiDeActivo() {
        when(clientes.bloquearCuentaScoped(5L)).thenReturn(Optional.of(new CuentaFalsa("Cliente", d("0.00"), d("0.00"), false)));
        when(libro.deReciboScoped(61L)).thenReturn(List.of(cobroDelRecibo("500.00")));
        when(imputaciones.idsDeReciboScoped(61L)).thenReturn(List.of());
        when(clientes.sumarSaldoScoped(anyLong(), any())).thenReturn(1);
        stubGuardarElLibro();

        servicio.asentarAnulacion(anulacion("500.00", "Cobro mal cargado"));

        verify(libro).save(any(MovimientoCuentaCliente.class));
        verify(clientes).sumarSaldoScoped(5L, d("500.00"));
    }

    private void verificarSinEscribir() {
        verify(libro, never()).save(any(MovimientoCuentaCliente.class));
        verify(imputaciones, never()).desactivarDeReciboScoped(anyLong(), anyLong(), any());
        verify(clientes, never()).sumarSaldoScoped(anyLong(), any());
    }

    @Test
    @DisplayName("CC-068: sin COBRO del recibo en la cuenta de ESE cliente → IllegalStateException sin escribir (recibo ajeno, inexistente)")
    void anulacionSinCobro() {
        when(clientes.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de("1000.00", "0.00")));
        when(libro.deReciboScoped(61L)).thenReturn(List.of());

        assertThatThrownBy(() -> servicio.asentarAnulacion(anulacion("600.00", "Cobro mal cargado")))
                .isInstanceOf(IllegalStateException.class);

        verificarSinEscribir();
        verify(imputaciones, never()).idsDeReciboScoped(anyLong());
    }

    @Test
    @DisplayName("CC-068: el COBRO es de OTRO cliente → IllegalStateException sin escribir")
    void anulacionDeCobroDeOtroCliente() {
        when(clientes.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de("1000.00", "0.00")));
        when(libro.deReciboScoped(61L)).thenReturn(List.of(
                MovimientoCuentaCliente.cobro(FERRETERIA, 6L, 61L, "Recibo N.º 12", d("600.00"), AHORA, 9L)));

        assertThatThrownBy(() -> servicio.asentarAnulacion(anulacion("600.00", "Cobro mal cargado")))
                .isInstanceOf(IllegalStateException.class);

        verificarSinEscribir();
    }

    @Test
    @DisplayName("CC-068: el haber del COBRO difiere del total pedido (un centavo alcanza) → IllegalStateException sin escribir; igual valor en otra escala pasa")
    void anulacionConOtroImporte() {
        when(clientes.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de("1000.00", "0.00")));
        when(libro.deReciboScoped(61L)).thenReturn(List.of(cobroDelRecibo("600.01")));

        assertThatThrownBy(() -> servicio.asentarAnulacion(anulacion("600.00", "Cobro mal cargado")))
                .isInstanceOf(IllegalStateException.class);
        verificarSinEscribir();

        when(libro.deReciboScoped(61L)).thenReturn(List.of(cobroDelRecibo("600.0")));
        when(imputaciones.idsDeReciboScoped(61L)).thenReturn(List.of());
        when(clientes.sumarSaldoScoped(anyLong(), any())).thenReturn(1);
        stubGuardarElLibro();
        servicio.asentarAnulacion(anulacion("600.00", "Cobro mal cargado"));
        verify(libro).save(any(MovimientoCuentaCliente.class));
    }

    @Test
    @DisplayName("CC-068: ya hay una ANULACION del recibo → IllegalStateException sin escribir (segunda anulación)")
    void anulacionRepetida() {
        when(clientes.bloquearCuentaScoped(5L)).thenReturn(Optional.of(CuentaFalsa.de("1000.00", "0.00")));
        when(libro.deReciboScoped(61L)).thenReturn(List.of(cobroDelRecibo("600.00"), MovimientoCuentaCliente.anulacion(
                FERRETERIA, 5L, 61L, "Anulación Recibo N.º 12", d("600.00"), "Cobro mal cargado", AHORA, 9L)));

        assertThatThrownBy(() -> servicio.asentarAnulacion(anulacion("600.00", "Cobro mal cargado")))
                .isInstanceOf(IllegalStateException.class);

        verificarSinEscribir();
    }

    @Test
    @DisplayName("CC-063/CC-085: una fila ya no vigente en la lectura bloqueada (otra anulación se adelantó) → IllegalStateException sin desactivar ni escribir")
    void anulacionConFilaNoVigente() {
        reciboConDosImputaciones();
        when(imputaciones.bloquearDeReciboScoped(eq(5L), any()))
                .thenReturn(List.of(new FilaImputacionFalsa(30L, 20L, true), new FilaImputacionFalsa(31L, 21L, false)));

        assertThatThrownBy(() -> servicio.asentarAnulacion(anulacion("600.00", "Cobro mal cargado")))
                .isInstanceOf(IllegalStateException.class);

        verificarSinEscribir();
    }

    @Test
    @DisplayName("si la lectura bloqueada devuelve menos filas que los ids (otro cliente, borrada) → IllegalStateException sin escribir")
    void anulacionConFilasFaltantes() {
        reciboConDosImputaciones();
        when(imputaciones.bloquearDeReciboScoped(eq(5L), any())).thenReturn(List.of(new FilaImputacionFalsa(30L, 20L, true)));

        assertThatThrownBy(() -> servicio.asentarAnulacion(anulacion("600.00", "Cobro mal cargado")))
                .isInstanceOf(IllegalStateException.class);

        verificarSinEscribir();
    }

    @Test
    @DisplayName("si desactivar devuelve un conteo distinto de ids.size() → IllegalStateException (rollback) y no se escribe el libro ni el saldo")
    void anulacionConDesactivacionIncompleta() {
        reciboConDosImputaciones();
        when(imputaciones.desactivarDeReciboScoped(eq(5L), eq(61L), any())).thenReturn(1);

        assertThatThrownBy(() -> servicio.asentarAnulacion(anulacion("600.00", "Cobro mal cargado")))
                .isInstanceOf(IllegalStateException.class);

        verify(libro, never()).save(any(MovimientoCuentaCliente.class));
        verify(clientes, never()).sumarSaldoScoped(anyLong(), any());
    }

    @Test
    @DisplayName("si el UPDATE del saldo no afecta exactamente una fila falla fuerte (IllegalStateException)")
    void anulacionConSaldoQueNoSeActualiza() {
        reciboConDosImputaciones();

        when(clientes.sumarSaldoScoped(5L, d("600.00"))).thenReturn(0);
        assertThatThrownBy(() -> servicio.asentarAnulacion(anulacion("600.00", "Cobro mal cargado")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("saldo");

        when(clientes.sumarSaldoScoped(5L, d("600.00"))).thenReturn(2);
        assertThatThrownBy(() -> servicio.asentarAnulacion(anulacion("600.00", "Cobro mal cargado")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("saldo");
    }

    @Test
    @DisplayName("argumentos mal formados son un error de programación (IllegalArgumentException): ids nulos, total ≤ 0, más de 2 decimales, motivo vacío o corto, número de recibo no positivo")
    void anulacionConArgumentosMalFormados() {
        assertThatThrownBy(() -> servicio.asentarAnulacion(new AsientoAnulacion(null, 61L, 12L, d("10.00"), "Cobro mal cargado", 9L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servicio.asentarAnulacion(new AsientoAnulacion(5L, null, 12L, d("10.00"), "Cobro mal cargado", 9L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servicio.asentarAnulacion(new AsientoAnulacion(5L, 61L, 0L, d("10.00"), "Cobro mal cargado", 9L)))
                .as("número de recibo no positivo").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servicio.asentarAnulacion(new AsientoAnulacion(5L, 61L, 12L, null, "Cobro mal cargado", 9L)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servicio.asentarAnulacion(anulacion("0.00", "Cobro mal cargado")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servicio.asentarAnulacion(anulacion("-5.00", "Cobro mal cargado")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servicio.asentarAnulacion(anulacion("10.001", "Cobro mal cargado")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servicio.asentarAnulacion(anulacion("10.00", null))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servicio.asentarAnulacion(anulacion("10.00", "    "))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> servicio.asentarAnulacion(anulacion("10.00", "abc"))).isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(clientes, libro, imputaciones);
    }

    @Test
    @DisplayName("una anulación de un cliente de otra ferretería o inexistente es 404 y no lee ni escribe nada más")
    void anulacionClienteAjeno() {
        when(clientes.bloquearCuentaScoped(5L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.asentarAnulacion(anulacion("600.00", "Cobro mal cargado")))
                .isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(libro, imputaciones);
        verify(clientes, never()).sumarSaldoScoped(anyLong(), any());
    }

    // ---- pendientes (CCT3-207: CC-039, CC-050, CC-031, CC-032) ----

    @Test
    @DisplayName("pendientes lista solo los comprobantes con pendiente > 0 en orden FIFO; los saldados no aparecen; aCuenta es derivado")
    void pendientesFifoYACuenta() {
        Cliente cliente = clienteConSaldo("800.00");
        when(clientes.findScoped(5L)).thenReturn(Optional.of(cliente));
        when(libro.debitosDeClienteScoped(5L)).thenReturn(
                List.of(debito(10, 50, "300.00"), debito(11, 51, "500.00"), debito(12, 52, "400.00")));
        when(imputaciones.imputadoPorComprobanteScoped(5L)).thenReturn(List.of(imputado(10, "300.00"), imputado(11, "100.00")));
        when(libro.creditoNetoDeRecibosScoped(5L)).thenReturn(d("600.00"));
        when(imputaciones.totalImputadoScoped(5L)).thenReturn(d("400.00"));

        PendientesCuenta pendientes = servicio.pendientes(5L);

        assertThat(pendientes.saldo()).isEqualByComparingTo("800.00");
        assertThat(pendientes.aCuenta()).isEqualByComparingTo("200.00");
        assertThat(pendientes.comprobantes()).extracting(PendientesCuenta.Pendiente::comprobanteId).containsExactly(11L, 12L);
        PendientesCuenta.Pendiente segundo = pendientes.comprobantes().get(0);
        assertThat(segundo.referencia()).isEqualTo("Venta N.º 11");
        assertThat(segundo.fecha()).isEqualTo(AHORA.minusDays(1));
        assertThat(segundo.importe()).isEqualByComparingTo("500.00");
        assertThat(segundo.imputado()).isEqualByComparingTo("100.00");
        assertThat(segundo.pendiente()).isEqualByComparingTo("400.00");
        verifyLecturaPura();
    }

    @Test
    @DisplayName("cliente sin deuda: lista vacía, saldo 0 y aCuenta 0")
    void pendientesSinDeuda() {
        Cliente cliente = clienteConSaldo("0.00");
        when(clientes.findScoped(5L)).thenReturn(Optional.of(cliente));
        when(libro.debitosDeClienteScoped(5L)).thenReturn(List.of());
        when(imputaciones.imputadoPorComprobanteScoped(5L)).thenReturn(List.of());
        when(libro.creditoNetoDeRecibosScoped(5L)).thenReturn(d("0"));
        when(imputaciones.totalImputadoScoped(5L)).thenReturn(d("0"));

        PendientesCuenta pendientes = servicio.pendientes(5L);

        assertThat(pendientes.comprobantes()).isEmpty();
        assertThat(pendientes.saldo()).isEqualByComparingTo("0");
        assertThat(pendientes.aCuenta()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("con un adelanto de 500 sin deuda: lista vacía, saldo −500, aCuenta 500")
    void pendientesConAdelanto() {
        Cliente cliente = clienteConSaldo("-500.00");
        when(clientes.findScoped(5L)).thenReturn(Optional.of(cliente));
        when(libro.debitosDeClienteScoped(5L)).thenReturn(List.of());
        when(imputaciones.imputadoPorComprobanteScoped(5L)).thenReturn(List.of());
        when(libro.creditoNetoDeRecibosScoped(5L)).thenReturn(d("500.00"));
        when(imputaciones.totalImputadoScoped(5L)).thenReturn(d("0"));

        PendientesCuenta pendientes = servicio.pendientes(5L);

        assertThat(pendientes.comprobantes()).isEmpty();
        assertThat(pendientes.saldo()).isEqualByComparingTo("-500.00");
        assertThat(pendientes.aCuenta()).isEqualByComparingTo("500.00");
    }

    @Test
    @DisplayName("CCT3B1-204: con una anulación, aCuenta = creditoNeto (COBRO − ANULACION) − imputado VIGENTE; el comprobante liberado vuelve a la lista")
    void pendientesConAnulacion() {
        // venta A 1.000; cobro R1 700 imputando 400 a A (anulado: ANULACION 700, imputación no vigente); cobro R2 100 a cuenta
        Cliente cliente = clienteConSaldo("900.00");
        when(clientes.findScoped(5L)).thenReturn(Optional.of(cliente));
        when(libro.debitosDeClienteScoped(5L)).thenReturn(List.of(debito(10, 50, "1000.00")));
        when(imputaciones.imputadoPorComprobanteScoped(5L)).thenReturn(List.of()); // la imputación de R1 ya no es vigente
        when(libro.creditoNetoDeRecibosScoped(5L)).thenReturn(d("100.00")); // 700 + 100 − 700
        when(imputaciones.totalImputadoScoped(5L)).thenReturn(d("0.00"));

        PendientesCuenta pendientes = servicio.pendientes(5L);

        assertThat(pendientes.aCuenta()).isEqualByComparingTo("100.00");
        assertThat(pendientes.comprobantes()).singleElement().satisfies(p -> {
            assertThat(p.comprobanteId()).isEqualTo(10L);
            assertThat(p.imputado()).isEqualByComparingTo("0.00");
            assertThat(p.pendiente()).isEqualByComparingTo("1000.00");
        });
        assertThat(pendientes.comprobantes().get(0).pendiente().subtract(pendientes.aCuenta()))
                .as("Σ pendiente − aCuenta = saldo").isEqualByComparingTo(pendientes.saldo());
        verifyLecturaPura();
    }

    @Test
    @DisplayName("CCT3B1-204: un adelanto anulado deja lista vacía, saldo 0 y aCuenta 0")
    void pendientesConAdelantoAnulado() {
        Cliente cliente = clienteConSaldo("0.00");
        when(clientes.findScoped(5L)).thenReturn(Optional.of(cliente));
        when(libro.debitosDeClienteScoped(5L)).thenReturn(List.of());
        when(imputaciones.imputadoPorComprobanteScoped(5L)).thenReturn(List.of());
        when(libro.creditoNetoDeRecibosScoped(5L)).thenReturn(d("0.00")); // COBRO 500 − ANULACION 500
        when(imputaciones.totalImputadoScoped(5L)).thenReturn(d("0.00"));

        PendientesCuenta pendientes = servicio.pendientes(5L);

        assertThat(pendientes.comprobantes()).isEmpty();
        assertThat(pendientes.saldo()).isEqualByComparingTo("0");
        assertThat(pendientes.aCuenta()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("CCT3B1-204: solo restan las imputaciones vigentes (lo que devuelve el repositorio), y distinto crédito neto da distinto aCuenta")
    void pendientesSoloRestanLasVigentes() {
        // A 1.000 con 250 vigentes (el repositorio ya no devuelve la no vigente); B 500 sin nada; cobro neto 600
        Cliente cliente = clienteConSaldo("900.00");
        when(clientes.findScoped(5L)).thenReturn(Optional.of(cliente));
        when(libro.debitosDeClienteScoped(5L)).thenReturn(List.of(debito(10, 50, "1000.00"), debito(11, 51, "500.00")));
        when(imputaciones.imputadoPorComprobanteScoped(5L)).thenReturn(List.of(imputado(10, "250.00")));
        when(libro.creditoNetoDeRecibosScoped(5L)).thenReturn(d("600.00"));
        when(imputaciones.totalImputadoScoped(5L)).thenReturn(d("250.00"));

        PendientesCuenta pendientes = servicio.pendientes(5L);

        assertThat(pendientes.comprobantes()).extracting(PendientesCuenta.Pendiente::pendiente)
                .usingElementComparator(BigDecimal::compareTo).containsExactly(d("750.00"), d("500.00"));
        assertThat(pendientes.aCuenta()).isEqualByComparingTo("350.00");
        verifyLecturaPura();
    }

    @Test
    @DisplayName("pendientes de un cliente ajeno o inexistente es 404 y no lee nada más")
    void pendientesClienteAjeno() {
        when(clientes.findScoped(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> servicio.pendientes(99L)).isInstanceOf(ResourceNotFoundException.class);

        verifyNoInteractions(libro, imputaciones);
    }

    private static Cliente clienteConSaldo(String saldo) {
        Cliente c = mock(Cliente.class);
        when(c.getSaldo()).thenReturn(new BigDecimal(saldo));
        return c;
    }

    private static ImputacionRepository.ImputadoDeComprobante imputado(long comprobante, String importe) {
        return new ImputacionRepository.ImputadoDeComprobante() {
            @Override public Long getComprobanteId() { return comprobante; }
            @Override public BigDecimal getImputado() { return d(importe); }
        };
    }

    /** La consulta de pendientes es lectura pura: sin ningún lock (bloquear*) ni escritura (save*). */
    private void verifyLecturaPura() {
        verify(clientes, never()).bloquearCuentaScoped(anyLong());
        verify(clientes, never()).sumarSaldoScoped(anyLong(), any());
        verify(libro, never()).bloquearPartidasScoped(anyLong(), any());
        verify(libro, never()).save(any(MovimientoCuentaCliente.class));
        verify(libro, never()).saveAll(any());
        verify(imputaciones, never()).imputadoBloqueadoScoped(anyLong(), any());
        verify(imputaciones, never()).saveAll(any());
        verify(imputaciones, never()).save(any(Imputacion.class));
    }
}
