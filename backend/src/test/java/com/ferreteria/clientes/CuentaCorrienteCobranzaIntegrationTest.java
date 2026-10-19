package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.AsientoCobro;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionAsentada;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionInvalidaException;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionInvalidaException.DeudaActual;
import com.ferreteria.clientes.service.CuentaCorrienteService;
import com.ferreteria.clientes.service.PendientesCuenta;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * El puerto de cobranza contra MySQL real (CCT3-209: CC-026, CC-027, CC-030, CC-031, CC-033, CC-037, CC-040, CC-041, CC-051,
 * CC-002). Se invoca como lo hará RegistroCobranza: en UNA transacción que toma al cliente, emite el recibo y asienta. Cada
 * escenario termina con I1 + I2. CUIT 30-8401NNNN.
 */
class CuentaCorrienteCobranzaIntegrationTest extends CuentaCorrienteTestBase {

    @Autowired
    CuentaCorrienteService servicio;

    @Override
    protected String prefijoDeCuit() {
        return "30-8401";
    }

    private static BigDecimal d(String valor) {
        return new BigDecimal(valor);
    }

    private PendientesCuenta pendientes(long cliente) {
        return servicio.pendientes(cliente);
    }

    // ---- feliz ----

    @Test
    @DisplayName("un comprobante: cobra y imputa todo → un COBRO de haber 1.000 con su recibo, una imputación, pendiente 0 y saldo 0")
    void unComprobante() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta venta = ventaEnLibro(f, cliente, "1000.00");
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());

        List<ImputacionAsentada> asentadas = cobrar(f, cliente, caja, "1000.00", imputa(venta.comprobanteId(), "1000.00"));

        assertThat(asentadas).singleElement().satisfies(a -> {
            assertThat(a.comprobanteId()).isEqualTo(venta.comprobanteId());
            assertThat(a.referencia()).isEqualTo("Venta N.º " + venta.numero());
            assertThat(a.importe()).isEqualByComparingTo("1000.00");
        });
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertThat(cobros(cliente)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select haber from movimiento_cuenta_cliente where cliente_id = ? and tipo = 'COBRO'",
                BigDecimal.class, cliente)).isEqualByComparingTo("1000.00");
        assertThat(jdbc.queryForObject("select referencia from movimiento_cuenta_cliente where cliente_id = ? and tipo = 'COBRO'",
                String.class, cliente)).startsWith("Recibo N.º ");
        assertThat(imputadoA(venta.comprobanteId())).isEqualByComparingTo("1000.00");
        PendientesCuenta p = pendientes(cliente);
        assertThat(p.comprobantes()).isEmpty();
        assertThat(p.saldo()).isEqualByComparingTo("0");
        assertThat(p.aCuenta()).isEqualByComparingTo("0");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("varios comprobantes en un recibo: 2 imputaciones y UN solo COBRO por el total")
    void variosComprobantes() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, cliente, "1000.00");
        VentaSuelta b = ventaEnLibro(f, cliente, "500.00");
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());

        cobrar(f, cliente, caja, "1500.00", imputa(b.comprobanteId(), "500.00"), imputa(a.comprobanteId(), "1000.00"));

        assertThat(imputaciones(cliente)).isEqualTo(2);
        assertThat(cobros(cliente)).isEqualTo(1);
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertThat(pendientes(cliente).comprobantes()).isEmpty();
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("parcial: cobra 400 de un comprobante de 1.000 → pendiente 600, saldo 600")
    void parcial() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta venta = ventaEnLibro(f, cliente, "1000.00");
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());

        cobrar(f, cliente, caja, "400.00", imputa(venta.comprobanteId(), "400.00"));

        PendientesCuenta p = pendientes(cliente);
        assertThat(p.comprobantes()).singleElement().satisfies(c -> {
            assertThat(c.importe()).isEqualByComparingTo("1000.00");
            assertThat(c.imputado()).isEqualByComparingTo("400.00");
            assertThat(c.pendiente()).isEqualByComparingTo("600.00");
        });
        assertThat(p.saldo()).isEqualByComparingTo("600.00");
        assertThat(p.aCuenta()).isEqualByComparingTo("0");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("exceso a cuenta: saldo 1.000, cobra 1.500 imputando 1.000 → saldo −500 y 500 a cuenta")
    void excedenteACuenta() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta venta = ventaEnLibro(f, cliente, "1000.00");
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());

        cobrar(f, cliente, caja, "1500.00", imputa(venta.comprobanteId(), "1000.00"));

        assertThat(saldo(cliente)).isEqualByComparingTo("-500.00");
        PendientesCuenta p = pendientes(cliente);
        assertThat(p.comprobantes()).isEmpty();
        assertThat(p.saldo()).isEqualByComparingTo("-500.00");
        assertThat(p.aCuenta()).isEqualByComparingTo("500.00");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("sin imputaciones: cobra 400 sobre saldo 1.000 → saldo 600, el comprobante sigue con pendiente 1.000 y 400 a cuenta")
    void sinImputaciones() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta venta = ventaEnLibro(f, cliente, "1000.00");
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());

        assertThat(cobrar(f, cliente, caja, "400.00")).isEmpty();

        assertThat(saldo(cliente)).isEqualByComparingTo("600.00");
        assertThat(imputaciones(cliente)).isZero();
        PendientesCuenta p = pendientes(cliente);
        assertThat(p.comprobantes()).singleElement().satisfies(c -> {
            assertThat(c.comprobanteId()).isEqualTo(venta.comprobanteId());
            assertThat(c.pendiente()).isEqualByComparingTo("1000.00");
        });
        assertThat(p.aCuenta()).isEqualByComparingTo("400.00");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("adelanto: cliente activo con saldo 0, sin pendientes y SIN cuenta habilitada cobra 500 → un COBRO, saldo −500, aCuenta 500; un segundo adelanto de 200 → −700")
    void adelantoACLienteSinDeuda() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, null); // sin cuenta corriente habilitada
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());

        assertThat(cobrar(f, cliente, caja, "500.00")).isEmpty();

        assertThat(saldo(cliente)).isEqualByComparingTo("-500.00");
        assertThat(cobros(cliente)).isEqualTo(1);
        PendientesCuenta p = pendientes(cliente);
        assertThat(p.comprobantes()).isEmpty();
        assertThat(p.saldo()).isEqualByComparingTo("-500.00");
        assertThat(p.aCuenta()).isEqualByComparingTo("500.00");
        assertInvariante(cliente);

        cobrar(f, cliente, caja, "200.00");

        assertThat(saldo(cliente)).isEqualByComparingTo("-700.00");
        assertThat(cobros(cliente)).isEqualTo(2);
        assertThat(pendientes(cliente).aCuenta()).isEqualByComparingTo("700.00");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("el cobro no depende del límite (CC-027): con límite NULL o 0 y deuda cobra igual")
    void cobroConLimiteNuloOCero() {
        Ferreteria f = ferreteria();
        long sinLimite = cliente(f, null);
        long limiteCero = cliente(f, "0.00");
        VentaSuelta a = ventaEnLibro(f, sinLimite, "800.00");
        VentaSuelta b = ventaEnLibro(f, limiteCero, "800.00");
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());

        cobrar(f, sinLimite, caja, "800.00", imputa(a.comprobanteId(), "800.00"));
        cobrar(f, limiteCero, caja, "300.00", imputa(b.comprobanteId(), "300.00"));

        assertThat(saldo(sinLimite)).isEqualByComparingTo("0");
        assertThat(saldo(limiteCero)).isEqualByComparingTo("500.00");
        assertInvariante(sinLimite);
        assertInvariante(limiteCero);
    }

    // ---- rechazos ----

    @Test
    @DisplayName("imputar más que el pendiente (1.000,01 sobre 1.000) → EXCEDE_DEUDA con la deuda actual; nada queda, ni el recibo")
    void excede() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta venta = ventaEnLibro(f, cliente, "1000.00");
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());

        assertThatThrownBy(() -> cobrar(f, cliente, caja, "1000.01", imputa(venta.comprobanteId(), "1000.01")))
                .isInstanceOfSatisfying(ImputacionInvalidaException.class, e -> {
                    assertThat(e.deudas()).singleElement().satisfies((DeudaActual deuda) -> {
                        assertThat(deuda.comprobanteId()).isEqualTo(venta.comprobanteId());
                        assertThat(deuda.pendiente()).isEqualByComparingTo("1000.00");
                    });
                });

        assertThat(recibos(cliente)).as("el recibo se revirtió con el rechazo").isZero();
        assertThat(cobros(cliente)).isZero();
        assertThat(imputaciones(cliente)).isZero();
        assertThat(saldo(cliente)).isEqualByComparingTo("1000.00");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("un comprobante ya saldado (pendiente 0) → EXCEDE_DEUDA con pendiente 0")
    void saldado() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta venta = ventaEnLibro(f, cliente, "1000.00");
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());
        cobrar(f, cliente, caja, "1000.00", imputa(venta.comprobanteId(), "1000.00"));

        assertThatThrownBy(() -> cobrar(f, cliente, caja, "1.00", imputa(venta.comprobanteId(), "1.00")))
                .isInstanceOfSatisfying(ImputacionInvalidaException.class,
                        e -> assertThat(e.deudas().get(0).pendiente()).isEqualByComparingTo("0"));

        assertThat(cobros(cliente)).isEqualTo(1);
        assertThat(recibos(cliente)).isEqualTo(1);
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("tenant: un comprobante de OTRO cliente de la ferretería y uno de OTRA ferretería → 404, sin una fila escrita")
    void comprobanteAjeno() {
        Ferreteria a = ferreteria();
        Ferreteria b = ferreteria();
        long cliente = cliente(a, "5000.00");
        long otro = cliente(a, "5000.00");
        long deB = cliente(b, "5000.00");
        VentaSuelta propia = ventaEnLibro(a, cliente, "100.00");
        VentaSuelta delOtro = ventaEnLibro(a, otro, "500.00");
        VentaSuelta ajena = ventaEnLibro(b, deB, "500.00");
        long caja = cajaCerrada(a);
        TenantContext.setCurrentFerreteriaId(a.id());

        assertThatThrownBy(() -> cobrar(a, cliente, caja, "100.00", imputa(delOtro.comprobanteId(), "50.00")))
                .isInstanceOf(ResourceNotFoundException.class).hasMessage("Comprobante no encontrado en la cuenta del cliente");
        assertThatThrownBy(() -> cobrar(a, cliente, caja, "100.00", imputa(ajena.comprobanteId(), "50.00")))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> cobrar(a, cliente, caja, "100.00", imputa(propia.comprobanteId(), "50.00"),
                imputa(delOtro.comprobanteId(), "10.00"))).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> cobrar(a, cliente, caja, "100.00", imputa(99_999_999L, "50.00")))
                .isInstanceOf(ResourceNotFoundException.class);

        for (long id : List.of(cliente, otro, deB)) {
            assertThat(recibos(id)).isZero();
            assertThat(cobros(id)).isZero();
            assertThat(imputaciones(id)).isZero();
        }
        assertThat(saldo(cliente)).isEqualByComparingTo("100.00");
        assertThat(saldo(otro)).isEqualByComparingTo("500.00");
        assertThat(saldo(deB)).isEqualByComparingTo("500.00");
        assertInvariante(cliente);
        assertInvariante(otro);
        assertInvariante(deB);
    }

    @Test
    @DisplayName("tenant: un cliente de otra ferretería → 404 y nada escrito")
    void clienteAjeno() {
        Ferreteria a = ferreteria();
        Ferreteria b = ferreteria();
        long deB = cliente(b, "5000.00");
        TenantContext.setCurrentFerreteriaId(a.id());

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> cobranza.asentarCobro(
                new AsientoCobro(deB, 1L, 1L, d("10.00"), List.of(), a.usuarioId()))))
                .isInstanceOf(ResourceNotFoundException.class);

        assertThat(cobros(deB)).isZero();
        assertThat(saldo(deB)).isEqualByComparingTo("0");
        assertInvariante(deB);
    }

    // ---- transacción ----

    @Test
    @DisplayName("(MANDATORY) asentarCobro sin transacción lanza IllegalTransactionStateException y no escribe nada")
    void mandatorySinTransaccion() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        TenantContext.setCurrentFerreteriaId(f.id());

        assertThatThrownBy(() -> cobranza.asentarCobro(new AsientoCobro(cliente, 1L, 1L, d("10.00"), List.of(), f.usuarioId())))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(cobros(cliente)).isZero();
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("si la transacción externa se revierte se van el recibo, las imputaciones, el COBRO y el saldo")
    void rollbackRevierteTodo() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta venta = ventaEnLibro(f, cliente, "1000.00");
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());

        new TransactionTemplate(transactionManager).execute(status -> {
            asentarEnLaTx(f, cliente, caja, "600.00", imputa(venta.comprobanteId(), "600.00"));
            status.setRollbackOnly();
            return null;
        });

        assertThat(recibos(cliente)).isZero();
        assertThat(imputaciones(cliente)).isZero();
        assertThat(cobros(cliente)).isZero();
        assertThat(saldo(cliente)).isEqualByComparingTo("1000.00");
        assertInvariante(cliente);
    }

    // ---- redondeo (CC-051) ----

    @Test
    @DisplayName("redondeo: pendiente 100,00 con 33,33 + 33,33 + 33,34 en tres cobranzas sucesivas → todas ok y pendiente 0")
    void tresCobranzasQueSumanCien() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta venta = ventaEnLibro(f, cliente, "100.00");
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());

        cobrar(f, cliente, caja, "33.33", imputa(venta.comprobanteId(), "33.33"));
        cobrar(f, cliente, caja, "33.33", imputa(venta.comprobanteId(), "33.33"));
        cobrar(f, cliente, caja, "33.34", imputa(venta.comprobanteId(), "33.34"));

        assertThat(pendientes(cliente).comprobantes()).isEmpty();
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertThat(imputadoA(venta.comprobanteId())).isEqualByComparingTo("100.00");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("redondeo: 33,34 + 33,34 + 33,33 → la tercera da EXCEDE_DEUDA (queda 33,32), y no se pierde un centavo")
    void laTerceraExcede() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta venta = ventaEnLibro(f, cliente, "100.00");
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());
        cobrar(f, cliente, caja, "33.34", imputa(venta.comprobanteId(), "33.34"));
        cobrar(f, cliente, caja, "33.34", imputa(venta.comprobanteId(), "33.34"));

        assertThatThrownBy(() -> cobrar(f, cliente, caja, "33.33", imputa(venta.comprobanteId(), "33.33")))
                .isInstanceOfSatisfying(ImputacionInvalidaException.class,
                        e -> assertThat(e.deudas().get(0).pendiente()).isEqualByComparingTo("33.32"));

        assertThat(imputadoA(venta.comprobanteId())).isEqualByComparingTo("66.68");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("compara por valor: 100,1 sobre pendiente 100,10 entra; 100,11 no")
    void mismaCifraOtraEscala() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta venta = ventaEnLibro(f, cliente, "100.10");
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());

        assertThatThrownBy(() -> cobrar(f, cliente, caja, "100.11", imputa(venta.comprobanteId(), "100.11")))
                .isInstanceOf(ImputacionInvalidaException.class);
        cobrar(f, cliente, caja, "100.1", imputa(venta.comprobanteId(), "100.1"));

        assertThat(pendientes(cliente).comprobantes()).isEmpty();
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("un comprobante con dos pagos a cuenta corriente suma sus débitos y se cobra de una vez")
    void ventaMixtaConDosPagosACuenta() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta venta = ventaEnLibro(f, cliente, "400.00", "100.00"); // un comprobante, dos pagos CC → debe 500
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());

        assertThat(pendientes(cliente).comprobantes()).singleElement()
                .satisfies(c -> assertThat(c.pendiente()).isEqualByComparingTo("500.00"));
        cobrar(f, cliente, caja, "500.00", imputa(venta.comprobanteId(), "500.00"));

        assertThat(pendientes(cliente).comprobantes()).isEmpty();
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("pendientes: FIFO por la primera partida, y un cliente desactivado se consulta igual")
    void pendientesFifoYDesactivado() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta primera = ventaEnLibro(f, cliente, "300.00");
        VentaSuelta segunda = ventaEnLibro(f, cliente, "500.00");
        VentaSuelta tercera = ventaEnLibro(f, cliente, "400.00");
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());
        cobrar(f, cliente, caja, "300.00", imputa(primera.comprobanteId(), "300.00"));
        jdbc.update("update cliente set activo = 0 where id = ?", cliente);

        PendientesCuenta p = pendientes(cliente);

        assertThat(p.comprobantes()).extracting(PendientesCuenta.Pendiente::comprobanteId)
                .containsExactly(segunda.comprobanteId(), tercera.comprobanteId());
        assertThat(p.saldo()).isEqualByComparingTo("900.00");
        assertThat(p.comprobantes().get(0).referencia()).isEqualTo("Venta N.º " + segunda.numero());
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("pendientes de un cliente de otra ferretería es 404")
    void pendientesAjeno() {
        Ferreteria a = ferreteria();
        Ferreteria b = ferreteria();
        long deB = cliente(b, "5000.00");
        TenantContext.setCurrentFerreteriaId(a.id());

        assertThatThrownBy(() -> pendientes(deB)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("imputacionesDeRecibo devuelve las imputaciones con la referencia del comprobante; vacío si fue todo a cuenta")
    void imputacionesDeRecibo() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, cliente, "1000.00");
        VentaSuelta b = ventaEnLibro(f, cliente, "500.00");
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());
        cobrar(f, cliente, caja, "700.00", imputa(b.comprobanteId(), "200.00"), imputa(a.comprobanteId(), "300.00"));
        cobrar(f, cliente, caja, "50.00");
        List<Long> recibosDelCliente = jdbc.queryForList("select id from recibo where cliente_id = ? order by id", Long.class,
                cliente);

        List<ImputacionAsentada> conImputaciones = cobranza.imputacionesDeRecibo(recibosDelCliente.get(0));
        List<ImputacionAsentada> todoACuenta = cobranza.imputacionesDeRecibo(recibosDelCliente.get(1));

        assertThat(conImputaciones).extracting(ImputacionAsentada::comprobanteId)
                .containsExactly(a.comprobanteId(), b.comprobanteId());
        assertThat(conImputaciones).extracting(ImputacionAsentada::referencia)
                .containsExactly("Venta N.º " + a.numero(), "Venta N.º " + b.numero());
        assertThat(todoACuenta).isEmpty();
        assertInvariante(cliente);
    }
}
