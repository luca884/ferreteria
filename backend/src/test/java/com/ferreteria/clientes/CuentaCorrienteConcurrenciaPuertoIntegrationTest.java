package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.clientes.api.ClientesParaVenta;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta.AsientoVenta;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta.CargoPago;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta.CupoCuenta;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
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
 * CC-010 a nivel del puerto, con hilos de verdad contra MySQL real: cada hilo hace lo que hará RegistroVenta (tomar al
 * cliente, verificar el cupo y asentar, todo en una transacción). El lock de la fila serializa por cliente y no entre
 * clientes distintos.
 */
class CuentaCorrienteConcurrenciaPuertoIntegrationTest extends CuentaCorrienteTestBase {

    @Autowired
    CuentaCorrienteParaVenta cuentas;

    @Autowired
    ClientesParaVenta clientesParaVenta;

    @Autowired
    PlatformTransactionManager transactionManager;

    /** Un intento de venta a cuenta: true si asentó, false si el cupo no alcanzó. */
    private Callable<Boolean> vender(Ferreteria f, long cliente, VentaSuelta venta, String importe) {
        return () -> {
            TenantContext.setCurrentFerreteriaId(f.id());
            try {
                return new TransactionTemplate(transactionManager).execute(status -> {
                    clientesParaVenta.tomarParaVenta(cliente).orElseThrow();
                    CupoCuenta cupo = cuentas.verificarCupo(cliente, new BigDecimal(importe));
                    if (cupo.excede()) {
                        return false;
                    }
                    cuentas.asentarVenta(new AsientoVenta(cliente, venta.comprobanteId(), venta.numero(),
                            List.of(new CargoPago(venta.pagoId(), new BigDecimal(importe))), f.usuarioId(), null));
                    return true;
                });
            } finally {
                TenantContext.clear();
            }
        };
    }

    private <T> List<T> enParalelo(List<Callable<T>> trabajos) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(trabajos.size());
        CountDownLatch listos = new CountDownLatch(trabajos.size());
        CountDownLatch largada = new CountDownLatch(1);
        try {
            List<Future<T>> futuros = new ArrayList<>();
            for (Callable<T> trabajo : trabajos) {
                futuros.add(pool.submit(() -> {
                    listos.countDown();
                    largada.await();
                    return trabajo.call();
                }));
            }
            assertThat(listos.await(30, TimeUnit.SECONDS)).isTrue();
            largada.countDown();
            List<T> resultados = new ArrayList<>();
            for (Future<T> futuro : futuros) {
                resultados.add(futuro.get(60, TimeUnit.SECONDS));
            }
            return resultados;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("8 hilos venden $300 a la vez con límite $1000: exactamente 3 asientan, 5 ven que excede, saldo 900 = Σ libro")
    void ochoHilosSobreElMismoCliente() throws Exception {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "1000.00");
        List<Callable<Boolean>> trabajos = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            trabajos.add(vender(f, cliente, ventaSuelta(f, cliente, "300.00"), "300.00"));
        }

        List<Boolean> asentaron = enParalelo(trabajos);

        assertThat(asentaron.stream().filter(b -> b).count()).isEqualTo(3);
        assertThat(asentaron.stream().filter(b -> !b).count()).isEqualTo(5);
        assertThat(saldo(cliente)).isEqualByComparingTo("900.00");
        assertThat(movimientos(cliente)).isEqualTo(3);
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("clientes distintos no se bloquean entre sí: con uno tomado, otro completa su venta sin esperar")
    void clientesDistintosNoSeBloquean() throws Exception {
        Ferreteria f = ferreteria();
        long tomado = cliente(f, "1000.00");
        long libre = cliente(f, "1000.00");
        VentaSuelta ventaDelLibre = ventaSuelta(f, libre, "300.00");
        CountDownLatch tomadoYa = new CountDownLatch(1);
        CountDownLatch soltar = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> quienTiene = pool.submit(() -> {
                TenantContext.setCurrentFerreteriaId(f.id());
                try {
                    new TransactionTemplate(transactionManager).execute(status -> {
                        clientesParaVenta.tomarParaVenta(tomado).orElseThrow();
                        tomadoYa.countDown();
                        try {
                            soltar.await(60, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        return null;
                    });
                } finally {
                    TenantContext.clear();
                }
            });
            assertThat(tomadoYa.await(30, TimeUnit.SECONDS)).isTrue();

            // Mientras el primero sostiene su cliente, el segundo termina completo en otro hilo y en tiempo.
            Boolean asento = pool.submit(vender(f, libre, ventaDelLibre, "300.00")).get(20, TimeUnit.SECONDS);

            assertThat(asento).isTrue();
            assertThat(quienTiene.isDone()).as("el primero sigue sosteniendo su cliente").isFalse();
            soltar.countDown();
            quienTiene.get(30, TimeUnit.SECONDS);
        } finally {
            soltar.countDown();
            pool.shutdownNow();
        }

        assertThat(saldo(libre)).isEqualByComparingTo("300.00");
        assertThat(saldo(tomado)).isEqualByComparingTo("0");
        assertInvariante(libre);
        assertInvariante(tomado);
    }

    @Test
    @DisplayName("el hilo que espera el lock del mismo cliente ve el saldo que dejó el primero (no una foto vieja)")
    void elSegundoVeElSaldoDelPrimero() throws Exception {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "500.00");
        CountDownLatch primeroTiene = new CountDownLatch(1);
        CountDownLatch segundoEsperando = new CountDownLatch(1);
        VentaSuelta v1 = ventaSuelta(f, cliente, "400.00");
        VentaSuelta v2 = ventaSuelta(f, cliente, "400.00");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> primero = pool.submit(() -> {
                TenantContext.setCurrentFerreteriaId(f.id());
                try {
                    return new TransactionTemplate(transactionManager).execute(status -> {
                        clientesParaVenta.tomarParaVenta(cliente);
                        primeroTiene.countDown();
                        try {
                            segundoEsperando.await(60, TimeUnit.SECONDS);
                            Thread.sleep(500); // deja que el segundo llegue a bloquearse en su tomarParaVenta
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        cuentas.asentarVenta(new AsientoVenta(cliente, v1.comprobanteId(), v1.numero(),
                                List.of(new CargoPago(v1.pagoId(), new BigDecimal("400.00"))), f.usuarioId(), null));
                        return true;
                    });
                } finally {
                    TenantContext.clear();
                }
            });
            assertThat(primeroTiene.await(30, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> segundo = pool.submit(() -> {
                segundoEsperando.countDown();
                return vender(f, cliente, v2, "400.00").call();
            });

            assertThat(primero.get(30, TimeUnit.SECONDS)).isTrue();
            assertThat(segundo.get(30, TimeUnit.SECONDS)).as("el segundo ve 400 + 400 > 500").isFalse();
        } finally {
            pool.shutdownNow();
        }

        assertThat(saldo(cliente)).isEqualByComparingTo("400.00");
        assertThat(movimientos(cliente)).isEqualTo(1);
        assertInvariante(cliente);
    }
}
