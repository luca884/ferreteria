package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionInvalidaException;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta.AsientoNota;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta.AsientoVenta;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta.CargoPago;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta.ClienteDesactivadoException;
import com.ferreteria.clientes.service.CuentaCorrienteService;
import com.ferreteria.clientes.service.PendientesCuenta;
import com.ferreteria.support.BarreraDeLocks;
import com.ferreteria.support.BarreraDeLocks.Hito;
import com.ferreteria.support.InvarianteCuenta;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * La nota de crédito (o la anulación interna) de una venta a cuenta corriente, a nivel puerto (cu-08-nc-cuenta-corriente T3:
 * S2, S3, S7 y S8 en lo que toca a clientes). Se invoca como lo hará la anulación de ventas: en UNA transacción que toma al
 * cliente y asienta la nota con los pagos a cuenta corriente de la venta. Cada escenario termina con I1 + I2. CUIT 30-8801NNNN.
 */
class CuentaCorrienteNotaDeCreditoIntegrationTest extends CuentaCorrienteTestBase {

    private static final AtomicInteger NOTAS = new AtomicInteger();

    @Autowired
    CuentaCorrienteParaVenta cuentas;

    @Autowired
    CuentaCorrienteService servicio;

    @Override
    protected String prefijoDeCuit() {
        return "30-8801";
    }

    /** El comprobante NOTA_CREDITO de una venta (sembrado, ya confirmado): su id y su número. */
    private record Nota(long id, long numero) {}

    private Nota notaDe(Ferreteria f, VentaSuelta venta) {
        long numero = NUMERO_BASE_SEMBRADO + 50_000_000L + NOTAS.incrementAndGet();
        jdbc.update("insert into comprobante (ferreteria_id, tipo, estado, numero, clave_idempotencia, usuario_id, neto, iva, "
                + "total, subtotal, comprobante_asociado_id) values (?, 'NOTA_CREDITO', 'SIN_FACTURAR', ?, ?, ?, 0, 0, 0, 0, ?)",
                f.id(), numero, UUID.randomUUID().toString(), f.usuarioId(), venta.comprobanteId());
        long id = jdbc.queryForObject("select id from comprobante where ferreteria_id = ? and numero = ?", Long.class, f.id(),
                numero);
        return new Nota(id, numero);
    }

    /** Los pagos a cuenta corriente de la venta, como los pasará ventas. */
    private List<CargoPago> pagosACuenta(VentaSuelta venta) {
        return jdbc.query("select id, monto from pago where comprobante_id = ? and medio = 'CUENTA_CORRIENTE' order by id",
                (rs, i) -> new CargoPago(rs.getLong(1), rs.getBigDecimal(2)), venta.comprobanteId());
    }

    private AsientoNota asiento(Ferreteria f, long cliente, VentaSuelta venta, Nota nota) {
        return new AsientoNota(cliente, nota.id(), nota.numero(), "Nota de crédito N.º " + nota.numero(),
                venta.comprobanteId(), pagosACuenta(venta), f.usuarioId());
    }

    /** Lo que hará la anulación de ventas: en una transacción, toma al cliente y asienta la nota. */
    private void anular(Ferreteria f, long cliente, VentaSuelta venta, Nota nota) {
        TenantContext.setCurrentFerreteriaId(f.id());
        AsientoNota asiento = asiento(f, cliente, venta, nota);
        new TransactionTemplate(transactionManager).execute(status -> {
            clientesParaVenta.tomarParaVenta(cliente).orElseThrow();
            cuentas.asentarNotaDeCredito(asiento);
            return null;
        });
    }

    /** Una venta a cuenta corriente asentada por el puerto, como RegistroVenta: toma al cliente y asienta (consume el crédito). */
    private VentaSuelta vender(Ferreteria f, long cliente, String monto) {
        VentaSuelta venta = ventaSuelta(f, cliente, monto);
        TenantContext.setCurrentFerreteriaId(f.id());
        new TransactionTemplate(transactionManager).execute(status -> {
            clientesParaVenta.tomarParaVenta(cliente).orElseThrow();
            cuentas.asentarVenta(new AsientoVenta(cliente, venta.comprobanteId(), venta.numero(),
                    List.of(new CargoPago(venta.pagoId(), new BigDecimal(monto))), f.usuarioId(), null));
            return null;
        });
        return venta;
    }

    private long renglonesNc(long cliente) {
        return jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente where cliente_id = ? and tipo = 'NC'",
                Long.class, cliente);
    }

    private BigDecimal imputadoPorLaNota(long notaId) {
        return jdbc.queryForObject("select coalesce(sum(importe), 0) from imputacion where nc_origen_id = ?", BigDecimal.class,
                notaId);
    }

    private PendientesCuenta pendientes(Ferreteria f, long cliente) {
        TenantContext.setCurrentFerreteriaId(f.id());
        return servicio.pendientes(cliente);
    }

    // ---- la venta debía todo ----

    @Test
    @DisplayName("venta $ 500 a cuenta sin cobrar → NC $ 500: un renglón NC al haber, saldo 0, la venta queda paga por una imputación NC y nada a favor")
    void ventaSinCobrar() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta venta = ventaEnLibro(f, cliente, "500.00");
        Nota nota = notaDe(f, venta);

        anular(f, cliente, venta, nota);

        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForMap("select debe, haber, pago_id, comprobante_id, recibo_id, ajuste_id, motivo, referencia "
                + "from movimiento_cuenta_cliente where cliente_id = ? and tipo = 'NC'", cliente)).satisfies(m -> {
                    assertThat((BigDecimal) m.get("debe")).isEqualByComparingTo("0");
                    assertThat((BigDecimal) m.get("haber")).isEqualByComparingTo("500.00");
                    assertThat(((Number) m.get("pago_id")).longValue()).isEqualTo(venta.pagoId());
                    assertThat(((Number) m.get("comprobante_id")).longValue()).isEqualTo(nota.id());
                    assertThat(m.get("recibo_id")).isNull();
                    assertThat(m.get("ajuste_id")).isNull();
                    assertThat(m.get("motivo")).isNull();
                    assertThat(m.get("referencia")).isEqualTo("Nota de crédito N.º " + nota.numero());
                });
        assertThat(jdbc.queryForMap("select comprobante_id, importe, automatica, vigente, recibo_id, ajuste_origen_id "
                + "from imputacion where nc_origen_id = ?", nota.id())).satisfies(m -> {
                    assertThat(((Number) m.get("comprobante_id")).longValue()).isEqualTo(venta.comprobanteId());
                    assertThat((BigDecimal) m.get("importe")).isEqualByComparingTo("500.00");
                    assertThat(m.get("automatica")).isEqualTo(false);
                    assertThat(m.get("vigente")).isEqualTo(true);
                    assertThat(m.get("recibo_id")).isNull();
                    assertThat(m.get("ajuste_origen_id")).isNull();
                });
        PendientesCuenta p = pendientes(f, cliente);
        assertThat(p.comprobantes()).isEmpty();
        assertThat(p.aCuenta()).isEqualByComparingTo("0");
        assertThat(p.saldo()).isEqualByComparingTo("0");
        assertInvariante(cliente);
    }

    // ---- la venta ya estaba cobrada: todo a favor, y lo consume la próxima venta ----

    @Test
    @DisplayName("venta $ 500 ya cobrada con un recibo → NC: saldo −500 y 500 a favor sin imputar; una venta nueva de $ 300 lo consume (automática NC → venta nueva) y deja −200")
    void ventaCobradaDejaAFavorYLoConsumeLaProximaVenta() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta venta = ventaEnLibro(f, cliente, "500.00");
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());
        cobrar(f, cliente, caja, "500.00", imputa(venta.comprobanteId(), "500.00"));
        Nota nota = notaDe(f, venta);

        anular(f, cliente, venta, nota);

        assertThat(saldo(cliente)).isEqualByComparingTo("-500.00");
        assertThat(imputadoPorLaNota(nota.id())).isEqualByComparingTo("0");
        PendientesCuenta p = pendientes(f, cliente);
        assertThat(p.comprobantes()).isEmpty();
        assertThat(p.aCuenta()).isEqualByComparingTo("500.00");
        assertInvariante(cliente);

        VentaSuelta nueva = vender(f, cliente, "300.00");

        assertThat(saldo(cliente)).isEqualByComparingTo("-200.00");
        assertThat(jdbc.queryForMap("select importe, automatica from imputacion where nc_origen_id = ? and comprobante_id = ?",
                nota.id(), nueva.comprobanteId())).satisfies(m -> {
                    assertThat((BigDecimal) m.get("importe")).isEqualByComparingTo("300.00");
                    assertThat(m.get("automatica")).isEqualTo(true);
                });
        PendientesCuenta despues = pendientes(f, cliente);
        assertThat(despues.comprobantes()).isEmpty();
        assertThat(despues.aCuenta()).isEqualByComparingTo("200.00");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("venta $ 500 con 300 cobrados (pendiente 200) → NC: imputa 200 a la venta y deja 300 a favor; saldo −300")
    void ventaParcialmenteCobrada() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta venta = ventaEnLibro(f, cliente, "500.00");
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());
        cobrar(f, cliente, caja, "300.00", imputa(venta.comprobanteId(), "300.00"));
        Nota nota = notaDe(f, venta);

        anular(f, cliente, venta, nota);

        assertThat(saldo(cliente)).isEqualByComparingTo("-300.00");
        assertThat(imputadoPorLaNota(nota.id())).isEqualByComparingTo("200.00");
        assertThat(imputadoA(venta.comprobanteId())).isEqualByComparingTo("500.00");
        PendientesCuenta p = pendientes(f, cliente);
        assertThat(p.comprobantes()).isEmpty();
        assertThat(p.aCuenta()).isEqualByComparingTo("300.00");
        assertInvariante(cliente);
    }

    // ---- venta mixta ----

    @Test
    @DisplayName("venta con dos pagos a cuenta (300 + 200) → dos renglones NC, uno por pago, y la venta paga")
    void ventaConDosPagosACuenta() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta venta = ventaEnLibro(f, cliente, "300.00", "200.00");
        Nota nota = notaDe(f, venta);

        anular(f, cliente, venta, nota);

        assertThat(jdbc.queryForList("select haber from movimiento_cuenta_cliente where cliente_id = ? and tipo = 'NC' "
                + "order by id", BigDecimal.class, cliente)).usingElementComparator(BigDecimal::compareTo)
                .containsExactly(new BigDecimal("300.00"), new BigDecimal("200.00"));
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertThat(imputadoPorLaNota(nota.id())).isEqualByComparingTo("500.00");
        assertInvariante(cliente);
    }

    // ---- rechazos: nada escrito ----

    @Test
    @DisplayName("repetir la NC sobre el mismo pago choca con uk_mcc_pago_tipo y la transacción no deja nada")
    void repetidaChocaYNoDejaNada() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta venta = ventaEnLibro(f, cliente, "500.00");
        Nota nota = notaDe(f, venta);
        anular(f, cliente, venta, nota);
        long movimientosAntes = movimientos(cliente);
        long imputacionesAntes = imputaciones(cliente);

        assertThatThrownBy(() -> anular(f, cliente, venta, nota)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(movimientos(cliente)).isEqualTo(movimientosAntes);
        assertThat(imputaciones(cliente)).isEqualTo(imputacionesAntes);
        assertThat(renglonesNc(cliente)).isEqualTo(1);
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("cliente desactivado → ClienteDesactivadoException («El cliente está desactivado.») y nada escrito")
    void clienteDesactivado() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta venta = ventaEnLibro(f, cliente, "500.00");
        long caja = cajaCerrada(f);
        TenantContext.setCurrentFerreteriaId(f.id());
        cobrar(f, cliente, caja, "500.00", imputa(venta.comprobanteId(), "500.00"));
        jdbc.update("update cliente set activo = false where id = ?", cliente);
        Nota nota = notaDe(f, venta);
        long movimientosAntes = movimientos(cliente);
        long imputacionesAntes = imputaciones(cliente);

        assertThatThrownBy(() -> anular(f, cliente, venta, nota)).isInstanceOf(ClienteDesactivadoException.class)
                .hasMessage("El cliente está desactivado.");

        assertThat(movimientos(cliente)).isEqualTo(movimientosAntes);
        assertThat(imputaciones(cliente)).isEqualTo(imputacionesAntes);
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertInvariante(cliente);
    }

    // ---- el oráculo ----

    @Test
    @DisplayName("I2 de la NC: una NC que imputa más que su haber rompe la invariante")
    void laInvarianteDetectaUnaNcQueImputaDeMas() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta venta = ventaEnLibro(f, cliente, "500.00");
        VentaSuelta otra = ventaEnLibro(f, cliente, "300.00");
        Nota nota = notaDe(f, venta);
        anular(f, cliente, venta, nota);
        jdbc.update("insert into imputacion (ferreteria_id, cliente_id, nc_origen_id, comprobante_id, importe) "
                + "values (?, ?, ?, ?, 100)", f.id(), cliente, nota.id(), otra.comprobanteId());

        assertThatThrownBy(() -> InvarianteCuenta.assertInvariante(jdbc, cliente)).isInstanceOf(AssertionError.class)
                .hasMessageContaining("I2g");
    }

    // ---- concurrencia: NC contra una cobranza que imputa a la misma venta ----

    private <T> Future<T> lanzar(ExecutorService pool, Ferreteria f, Callable<T> trabajo) {
        return pool.submit(() -> {
            TenantContext.setCurrentFerreteriaId(f.id());
            try {
                return trabajo.call();
            } finally {
                TenantContext.clear();
            }
        });
    }

    private static <T> T resultado(Future<T> futuro) throws Exception {
        try {
            return futuro.get(60, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception causa) {
                throw causa;
            }
            throw e;
        }
    }

    @Test
    @DisplayName("NC con el cliente tomado y una cobranza de la misma venta en espera: la NC paga la venta y la cobranza da EXCEDE_DEUDA; nada de más")
    void ncPrimeroYCobranzaDespues() throws Exception {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta venta = ventaEnLibro(f, cliente, "500.00");
        long caja = cajaCerrada(f);
        Nota nota = notaDe(f, venta);
        TenantContext.setCurrentFerreteriaId(f.id());
        AsientoNota asiento = asiento(f, cliente, venta, nota);
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        Hito llego = new Hito();
        Hito suelta = new Hito();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> laNota = lanzar(pool, f, () -> new TransactionTemplate(transactionManager).execute(status -> {
                clientesParaVenta.tomarParaVenta(cliente).orElseThrow();
                llego.avisar();
                suelta.esperar();
                cuentas.asentarNotaDeCredito(asiento);
                return null;
            }));
            llego.esperar();
            Future<?> elCobro = lanzar(pool, f, () -> cobrar(f, cliente, caja, "500.00", imputa(venta.comprobanteId(), "500.00")));
            barrera.esperarBloqueados(1);
            suelta.avisar();

            resultado(laNota);
            assertThatThrownBy(() -> resultado(elCobro)).isInstanceOf(ImputacionInvalidaException.class);
        } finally {
            suelta.avisar();
            pool.shutdownNow();
        }

        assertThat(imputadoA(venta.comprobanteId())).isEqualByComparingTo("500.00");
        assertThat(imputadoPorLaNota(nota.id())).isEqualByComparingTo("500.00");
        assertThat(recibos(cliente)).isZero();
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    @Test
    @DisplayName("cobranza con el cliente tomado y la NC de la misma venta en espera: la cobranza paga la venta y la NC queda entera a favor")
    void cobranzaPrimeroYNcDespues() throws Exception {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta venta = ventaEnLibro(f, cliente, "500.00");
        long caja = cajaCerrada(f);
        Nota nota = notaDe(f, venta);
        TenantContext.setCurrentFerreteriaId(f.id());
        AsientoNota asiento = asiento(f, cliente, venta, nota);
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        Hito llego = new Hito();
        Hito suelta = new Hito();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> elCobro = lanzar(pool, f, () -> new TransactionTemplate(transactionManager).execute(status -> {
                clientesParaVenta.tomarParaVenta(cliente).orElseThrow();
                llego.avisar();
                suelta.esperar();
                return asentarEnLaTx(f, cliente, caja, "500.00", imputa(venta.comprobanteId(), "500.00"));
            }));
            llego.esperar();
            Future<?> laNota = lanzar(pool, f, () -> new TransactionTemplate(transactionManager).execute(status -> {
                clientesParaVenta.tomarParaVenta(cliente).orElseThrow();
                cuentas.asentarNotaDeCredito(asiento);
                return null;
            }));
            barrera.esperarBloqueados(1);
            suelta.avisar();

            resultado(elCobro);
            resultado(laNota);
        } finally {
            suelta.avisar();
            pool.shutdownNow();
        }

        assertThat(imputadoA(venta.comprobanteId())).isEqualByComparingTo("500.00");
        assertThat(imputadoPorLaNota(nota.id())).isEqualByComparingTo("0");
        assertThat(saldo(cliente)).isEqualByComparingTo("-500.00");
        assertThat(pendientes(f, cliente).aCuenta()).isEqualByComparingTo("500.00");
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }
}
