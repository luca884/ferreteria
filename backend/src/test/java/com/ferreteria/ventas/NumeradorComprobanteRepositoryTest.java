package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.support.IntegrationTestBase;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.repository.NumeradorComprobanteRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Numeracion interna (R-012): correlativa por ferreteria, desde 1, sin repetirse bajo concurrencia. */
class NumeradorComprobanteRepositoryTest extends IntegrationTestBase {

    private static final AtomicInteger SECUENCIA = new AtomicInteger();

    @Autowired
    NumeradorComprobanteRepository numerador;

    @Autowired
    PlatformTransactionManager transactionManager;

    @AfterEach
    void limpiar() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("la primera venta de una ferreteria nueva es la 1")
    void arrancaEnUno() {
        Long f = ferreteria();
        TenantContext.setCurrentFerreteriaId(f);

        assertThat(siguiente()).isEqualTo(1L);
    }

    @Test
    @DisplayName("los numeros son correlativos: 1, 2, 3")
    void esCorrelativo() {
        Long f = ferreteria();
        TenantContext.setCurrentFerreteriaId(f);

        assertThat(List.of(siguiente(), siguiente(), siguiente())).containsExactly(1L, 2L, 3L);
        assertThat(ultimoEnBase(f)).isEqualTo(3L);
    }

    @Test
    @DisplayName("cada ferreteria tiene su serie y las ventas de una no hacen avanzar la otra")
    void esIndependientePorFerreteria() {
        Long a = ferreteria();
        Long b = ferreteria();

        TenantContext.setCurrentFerreteriaId(a);
        assertThat(List.of(siguiente(), siguiente(), siguiente(), siguiente(), siguiente()))
                .containsExactly(1L, 2L, 3L, 4L, 5L);
        TenantContext.setCurrentFerreteriaId(b);
        assertThat(siguiente()).isEqualTo(1L);

        assertThat(ultimoEnBase(a)).isEqualTo(5L);
        assertThat(ultimoEnBase(b)).isEqualTo(1L);
    }

    @Test
    @DisplayName("hay una sola fila de numerador por ferreteria, por muchos numeros que se pidan")
    void unaFilaPorFerreteria() {
        Long f = ferreteria();
        TenantContext.setCurrentFerreteriaId(f);
        siguiente();
        siguiente();

        assertThat(jdbc.queryForObject(
                "select count(*) from numerador_comprobante where ferreteria_id = ?", Integer.class, f)).isEqualTo(1);
    }

    @Test
    @DisplayName("8 hilos concurrentes obtienen numeros distintos y seguidos (1..8)")
    void concurrenteSinRepetidosNiHuecos() throws Exception {
        Long f = ferreteria();
        int hilos = 8;
        CountDownLatch salida = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        List<Future<Long>> resultados = new ArrayList<>();
        try {
            for (int i = 0; i < hilos; i++) {
                resultados.add(pool.submit(() -> {
                    TenantContext.setCurrentFerreteriaId(f);
                    try {
                        salida.await();
                        // La transaccion se queda abierta un rato: el lock de la fila serializa a los demas.
                        return new TransactionTemplate(transactionManager).execute(status -> {
                            long numero = numerador.siguienteScoped();
                            pausa();
                            return numero;
                        });
                    } finally {
                        TenantContext.clear();
                    }
                }));
            }
            salida.countDown();
            List<Long> numeros = new ArrayList<>();
            for (Future<Long> resultado : resultados) {
                numeros.add(resultado.get(60, TimeUnit.SECONDS));
            }

            assertThat(numeros).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        } finally {
            pool.shutdownNow();
        }
        assertThat(ultimoEnBase(f)).isEqualTo(8L);
    }

    @Test
    @DisplayName("si la transaccion se revierte, el numero no se consume (sin huecos)")
    void unaTransaccionRevertidaNoConsumeNumero() {
        Long f = ferreteria();
        TenantContext.setCurrentFerreteriaId(f);
        assertThat(siguiente()).isEqualTo(1L);

        new TransactionTemplate(transactionManager).execute(status -> {
            assertThat(numerador.siguienteScoped()).isEqualTo(2L);
            status.setRollbackOnly();
            return null;
        });

        assertThat(siguiente()).isEqualTo(2L);
    }

    @Test
    @DisplayName("una transaccion que ya leyo (snapshot viejo) igual recibe el numero que sigue al ultimo confirmado")
    void conSnapshotPrevioDevuelveElNumeroVigente() {
        Long f = ferreteria();
        TenantContext.setCurrentFerreteriaId(f);
        assertThat(siguiente()).isEqualTo(1L);

        Long numero = new TransactionTemplate(transactionManager).execute(status -> {
            // Lectura consistente primero: fija el snapshot de REPEATABLE READ antes de que otra confirme.
            jdbc.queryForObject("select count(*) from numerador_comprobante", Integer.class);
            TransactionTemplate otra = new TransactionTemplate(transactionManager);
            otra.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            Long deLaOtra = otra.execute(s -> numerador.siguienteScoped());
            assertThat(deLaOtra).isEqualTo(2L);
            return numerador.siguienteScoped();
        });

        assertThat(numero).isEqualTo(3L);
        assertThat(ultimoEnBase(f)).isEqualTo(3L);
    }

    private static void pausa() {
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long siguiente() {
        return new TransactionTemplate(transactionManager).execute(status -> numerador.siguienteScoped());
    }

    private Long ultimoEnBase(Long ferreteriaId) {
        return jdbc.queryForObject(
                "select ultimo_numero from numerador_comprobante where ferreteria_id = ?", Long.class, ferreteriaId);
    }

    private Long ferreteria() {
        String cuit = String.format("30-%08d-%d", 79600000 + SECUENCIA.incrementAndGet(), 1);
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) "
                + "values (?, ?, 'RESPONSABLE_INSCRIPTO')", "Numerador " + cuit, cuit);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
    }
}
