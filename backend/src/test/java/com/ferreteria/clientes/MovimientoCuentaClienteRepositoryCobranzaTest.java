package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.clientes.repository.ClienteRepository;
import com.ferreteria.clientes.repository.MovimientoCuentaClienteRepository;
import com.ferreteria.clientes.repository.MovimientoCuentaClienteRepository.DebitoDeComprobante;
import com.ferreteria.support.BarreraDeLocks;
import com.ferreteria.support.BarreraDeLocks.HolderJdbc;
import com.ferreteria.support.BarreraDeLocks.Hito;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CC-030, CC-031, CC-041, CC-042: las consultas del libro que usa la cobranza (débitos por comprobante, partidas por clave
 * primaria y créditos de cobro) contra MySQL real. {@code bloquearPartidas} es nativa y por PK exacta: sin gap locks, que
 * bloquearían altas de ventas a cuenta de OTROS clientes.
 */
class MovimientoCuentaClienteRepositoryCobranzaTest extends CuentaCorrienteTestBase {

    @Autowired
    MovimientoCuentaClienteRepository libro;

    @Autowired
    ClienteRepository clientes;

    @Autowired
    PlatformTransactionManager transactionManager;

    private <T> T enTx(java.util.function.Supplier<T> trabajo) {
        return new TransactionTemplate(transactionManager).execute(status -> trabajo.get());
    }

    private List<Long> idsDelLibro(long comprobanteId) {
        return jdbc.queryForList("select id from movimiento_cuenta_cliente where comprobante_id = ? order by id",
                Long.class, comprobanteId);
    }

    @Test
    @DisplayName("debitosDeClienteScoped agrupa por comprobante: suma los 2 pagos CC de la misma venta, trae el primer id, la referencia y la fecha")
    void debitosAgrupanPorComprobante() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta mixta = ventaEnLibro(f, cliente, "700.00", "300.50");
        TenantContext.setCurrentFerreteriaId(f.id());

        List<DebitoDeComprobante> debitos = enTx(() -> libro.debitosDeClienteScoped(cliente));

        assertThat(debitos).hasSize(1);
        DebitoDeComprobante debito = debitos.get(0);
        assertThat(debito.getComprobanteId()).isEqualTo(mixta.comprobanteId());
        assertThat(debito.getDebe()).isEqualByComparingTo("1000.50");
        assertThat(debito.getPrimerMovimientoId()).isEqualTo(idsDelLibro(mixta.comprobanteId()).get(0));
        assertThat(debito.getReferencia()).isEqualTo("Venta N.º " + mixta.numero());
        assertThat(debito.getFecha()).isNotNull();
    }

    @Test
    @DisplayName("debitosDeClienteScoped ordena FIFO por el id del primer movimiento (no por importe ni por comprobante) y excluye los COBRO")
    void debitosEnOrdenFifoYSinCobros() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta primera = ventaEnLibro(f, cliente, "100.00");
        VentaSuelta segunda = ventaEnLibro(f, cliente, "900.00");
        VentaSuelta tercera = ventaEnLibro(f, cliente, "50.00");
        cobroEnLibro(f, cliente, "200.00"); // un COBRO en el libro: no es un débito
        TenantContext.setCurrentFerreteriaId(f.id());

        List<DebitoDeComprobante> debitos = enTx(() -> libro.debitosDeClienteScoped(cliente));

        assertThat(debitos).extracting(DebitoDeComprobante::getComprobanteId)
                .containsExactly(primera.comprobanteId(), segunda.comprobanteId(), tercera.comprobanteId());
        assertThat(debitos).extracting(DebitoDeComprobante::getPrimerMovimientoId).isSorted();
    }

    @Test
    @DisplayName("debitosDeClienteScoped no muestra los débitos de otro cliente ni los de otra ferretería")
    void debitosScoping() {
        Ferreteria a = ferreteria();
        Ferreteria b = ferreteria();
        long propio = cliente(a, "5000.00");
        long otroDeLaMisma = cliente(a, "5000.00");
        long deB = cliente(b, "5000.00");
        VentaSuelta mia = ventaEnLibro(a, propio, "100.00");
        ventaEnLibro(a, otroDeLaMisma, "200.00");
        ventaEnLibro(b, deB, "300.00");

        TenantContext.setCurrentFerreteriaId(a.id());
        assertThat(enTx(() -> libro.debitosDeClienteScoped(propio))).extracting(DebitoDeComprobante::getComprobanteId)
                .containsExactly(mia.comprobanteId());
        assertThat(enTx(() -> libro.debitosDeClienteScoped(deB))).as("cliente de otra ferretería").isEmpty();
    }

    @Test
    @DisplayName("debitosDeComprobantesScoped es el subconjunto pedido, y un comprobante de otro cliente o de otra ferretería no aparece")
    void debitosDeComprobantes() {
        Ferreteria a = ferreteria();
        Ferreteria b = ferreteria();
        long cliente = cliente(a, "5000.00");
        long otro = cliente(a, "5000.00");
        long deB = cliente(b, "5000.00");
        VentaSuelta uno = ventaEnLibro(a, cliente, "100.00");
        VentaSuelta dos = ventaEnLibro(a, cliente, "200.00");
        ventaEnLibro(a, cliente, "300.00");
        VentaSuelta delOtro = ventaEnLibro(a, otro, "400.00");
        VentaSuelta ajena = ventaEnLibro(b, deB, "500.00");
        TenantContext.setCurrentFerreteriaId(a.id());

        List<DebitoDeComprobante> pedidos = enTx(() -> libro.debitosDeComprobantesScoped(cliente,
                List.of(dos.comprobanteId(), uno.comprobanteId(), delOtro.comprobanteId(), ajena.comprobanteId(), -1L)));

        assertThat(pedidos).extracting(DebitoDeComprobante::getComprobanteId)
                .as("solo los de la cuenta del cliente, en orden FIFO")
                .containsExactly(uno.comprobanteId(), dos.comprobanteId());
    }

    @Test
    @DisplayName("idsDePartidasScoped devuelve los ids de las filas VENTA de esos comprobantes, ascendentes, sin COBRO ni ajenos")
    void idsDePartidas() {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        long otro = cliente(f, "5000.00");
        VentaSuelta mixta = ventaEnLibro(f, cliente, "100.00", "50.00");
        VentaSuelta simple = ventaEnLibro(f, cliente, "70.00");
        VentaSuelta delOtro = ventaEnLibro(f, otro, "10.00");
        cobroEnLibro(f, cliente, "20.00");
        TenantContext.setCurrentFerreteriaId(f.id());

        List<Long> ids = enTx(() -> libro.idsDePartidasScoped(cliente,
                List.of(simple.comprobanteId(), mixta.comprobanteId(), delOtro.comprobanteId())));

        List<Long> esperados = new java.util.ArrayList<>(idsDelLibro(mixta.comprobanteId()));
        esperados.addAll(idsDelLibro(simple.comprobanteId()));
        assertThat(ids).containsExactlyElementsOf(esperados.stream().sorted().toList());
        assertThat(ids).hasSize(3);
    }

    @Test
    @DisplayName("bloquearPartidasScoped devuelve los ids ASCENDENTES aunque se pidan descendentes, y no cruza clientes ni ferreterías")
    void bloquearPartidasAscendentesYScoped() {
        Ferreteria a = ferreteria();
        Ferreteria b = ferreteria();
        long cliente = cliente(a, "5000.00");
        long otro = cliente(a, "5000.00");
        long deB = cliente(b, "5000.00");
        VentaSuelta v1 = ventaEnLibro(a, cliente, "10.00");
        VentaSuelta v2 = ventaEnLibro(a, cliente, "20.00");
        VentaSuelta v3 = ventaEnLibro(a, cliente, "30.00");
        VentaSuelta delOtro = ventaEnLibro(a, otro, "40.00");
        VentaSuelta ajena = ventaEnLibro(b, deB, "50.00");
        long id1 = idsDelLibro(v1.comprobanteId()).get(0);
        long id2 = idsDelLibro(v2.comprobanteId()).get(0);
        long id3 = idsDelLibro(v3.comprobanteId()).get(0);
        TenantContext.setCurrentFerreteriaId(a.id());

        List<Long> bloqueadas = enTx(() -> libro.bloquearPartidasScoped(cliente, List.of(id3, id1, id2,
                idsDelLibro(delOtro.comprobanteId()).get(0), idsDelLibro(ajena.comprobanteId()).get(0))));

        assertThat(bloqueadas).containsExactly(id1, id2, id3);
    }

    @Test
    @DisplayName("bloquearPartidas es por PK exacta: mientras A las tiene bloqueadas, otra conexión inserta una venta de OTRO cliente sin esperar (sin gap locks)")
    void bloquearPartidasNoTomaGapLocks() throws Exception {
        Ferreteria f = ferreteria();
        long clienteA = cliente(f, "5000.00");
        long clienteB = cliente(f, "5000.00"); // posterior a A: sus filas del libro caen detrás de las de A en ix_mcc_cliente
        VentaSuelta ventaA = ventaEnLibro(f, clienteA, "100.00");
        VentaSuelta ventaDeB = ventaSuelta(f, clienteB, "60.00"); // comprobante y pago listos; falta la fila del libro
        List<Long> partidasDeA = idsDelLibro(ventaA.comprobanteId());
        Hito bloqueadas = new Hito();
        Hito soltar = new Hito();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        try {
            Future<?> quienBloquea = pool.submit(() -> {
                TenantContext.setCurrentFerreteriaId(f.id());
                try {
                    enTx(() -> {
                        libro.bloquearPartidasScoped(clienteA, partidasDeA);
                        bloqueadas.avisar();
                        soltar.esperar(Duration.ofSeconds(60));
                        return null;
                    });
                } finally {
                    TenantContext.clear();
                }
            });
            bloqueadas.esperar();

            HolderJdbc insertor = BarreraDeLocks.holderJdbc("set session innodb_lock_wait_timeout = 1");
            try {
                insertor.ejecutar("insert into movimiento_cuenta_cliente (ferreteria_id, cliente_id, tipo, debe, haber, "
                        + "comprobante_id, pago_id, fecha, usuario_id, referencia) values (" + f.id() + ", " + clienteB
                        + ", 'VENTA', 60.00, 0, " + ventaDeB.comprobanteId() + ", " + ventaDeB.pagoId() + ", now(6), "
                        + f.usuarioId() + ", 'Venta N.º " + ventaDeB.numero() + "')");
                insertor.commit();
            } catch (RuntimeException e) {
                insertor.rollback();
                throw new AssertionError("Un alta del libro de OTRO cliente quedó esperando: ¿bloquearPartidas toma gap locks?", e);
            } finally {
                soltar.avisar();
            }
            quienBloquea.get(30, TimeUnit.SECONDS);
        } finally {
            soltar.avisar();
            pool.shutdownNow();
        }

        assertThat(movimientos(clienteB)).isEqualTo(1);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    @Test
    @DisplayName("creditosDeCobroScoped suma solo los COBRO del cliente (0 si no hay): ni ventas, ni otro cliente, ni otra ferretería")
    void creditosDeCobro() {
        Ferreteria a = ferreteria();
        Ferreteria b = ferreteria();
        long cliente = cliente(a, "5000.00");
        long otro = cliente(a, "5000.00");
        long deB = cliente(b, "5000.00");
        ventaEnLibro(a, cliente, "1000.00");
        cobroEnLibro(a, cliente, "300.25");
        cobroEnLibro(a, cliente, "100.00");
        cobroEnLibro(a, otro, "999.00");
        cobroEnLibro(b, deB, "888.00");
        TenantContext.setCurrentFerreteriaId(a.id());

        assertThat(enTx(() -> libro.creditosDeCobroScoped(cliente))).isEqualByComparingTo("400.25");
        assertThat(enTx(() -> libro.creditosDeCobroScoped(cliente(a, null)))).isEqualByComparingTo("0");
        assertThat(enTx(() -> libro.creditosDeCobroScoped(deB))).as("cliente de otra ferretería").isEqualByComparingTo("0");
    }
}
