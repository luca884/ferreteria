package com.ferreteria.catalogo.service.lectura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.common.exception.BadRequestException;
import com.ferreteria.tenant.TenantContext;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TurnoDeLecturaTest {

    private static final String OCUPADO = "Ya se está leyendo otra lista. Probá de nuevo en un momento.";

    private final CountDownLatch leyendo = new CountDownLatch(1);
    private final CountDownLatch terminar = new CountDownLatch(1);
    private final ExecutorService pedidos = Executors.newFixedThreadPool(3);

    @AfterEach
    void cerrar() {
        terminar.countDown();
        pedidos.shutdownNow();
    }

    /** Una lectura que avisa que arranco y no termina hasta que el test la suelta. */
    private Supplier<String> lenta() {
        return () -> {
            leyendo.countDown();
            try {
                terminar.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "leyó";
        };
    }

    /** Lee como la ferreteria del pedido ({@code null}: sin ferreteria) y devuelve como termino. */
    private static String leerComo(Long ferreteria, TurnoDeLectura turno, Supplier<String> lectura) {
        if (ferreteria != null) {
            TenantContext.setCurrentFerreteriaId(ferreteria);
        }
        try {
            return turno.conTurno(lectura);
        } catch (BadRequestException e) {
            return e.getMessage();
        } finally {
            TenantContext.clear();
        }
    }

    @Test
    void seLeeUnaListaPorVezYLaQueNoConsigueTurnoATiempoAvisa() throws Exception {
        TurnoDeLectura turno = new TurnoDeLectura(Duration.ofMillis(300));
        Future<String> deA = pedidos.submit(() -> leerComo(1L, turno, lenta()));
        assertThat(leyendo.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(leerComo(2L, turno, () -> "leyó")).isEqualTo(OCUPADO);

        terminar.countDown();
        assertThat(deA.get()).isEqualTo("leyó");
        assertThat(leerComo(2L, turno, () -> "leyó")).as("al terminar A, B vuelve a probar y lee").isEqualTo("leyó");
    }

    @Test
    void laQueEsperaLeeApenasSeLiberaElTurno() throws Exception {
        TurnoDeLectura turno = new TurnoDeLectura(Duration.ofSeconds(10));
        Future<String> deA = pedidos.submit(() -> leerComo(1L, turno, lenta()));
        assertThat(leyendo.await(5, TimeUnit.SECONDS)).isTrue();
        Future<String> deB = pedidos.submit(() -> leerComo(2L, turno, () -> "leyó B"));
        Thread.sleep(200);
        assertThat(deB).isNotDone();

        terminar.countDown();

        assertThat(deA.get()).isEqualTo("leyó");
        assertThat(deB.get(5, TimeUnit.SECONDS)).isEqualTo("leyó B");
    }

    @Test
    void unaFerreteriaQueYaEstaLeyendoNoEsperaOtroTurno() throws Exception {
        TurnoDeLectura turno = new TurnoDeLectura(Duration.ofSeconds(30));
        Future<String> deA = pedidos.submit(() -> leerComo(1L, turno, lenta()));
        assertThat(leyendo.await(5, TimeUnit.SECONDS)).isTrue();
        long desde = System.nanoTime();

        assertThat(leerComo(1L, turno, () -> "leyó")).isEqualTo(OCUPADO);
        assertThat(Duration.ofNanos(System.nanoTime() - desde)).isLessThan(Duration.ofSeconds(1));

        terminar.countDown();
        assertThat(deA.get()).isEqualTo("leyó");
        assertThat(leerComo(1L, turno, () -> "leyó")).isEqualTo("leyó");
    }

    @Test
    void unPedidoSinFerreteriaEsperaSuTurno() throws Exception {
        TurnoDeLectura turno = new TurnoDeLectura(Duration.ofMillis(300));
        Future<String> sinFerreteria = pedidos.submit(() -> leerComo(null, turno, lenta()));
        assertThat(leyendo.await(5, TimeUnit.SECONDS)).isTrue();

        assertThat(leerComo(null, turno, () -> "leyó")).isEqualTo(OCUPADO);

        terminar.countDown();
        assertThat(sinFerreteria.get()).isEqualTo("leyó");
    }

    @Test
    void unaLecturaQueFallaLiberaElTurnoYLaFerreteria() {
        TurnoDeLectura turno = new TurnoDeLectura(Duration.ofMillis(300));

        assertThatThrownBy(() -> {
            TenantContext.setCurrentFerreteriaId(1L);
            try {
                turno.conTurno(() -> {
                    throw new BadRequestException("No se pudo leer el CSV. Revisá las comillas.");
                });
            } finally {
                TenantContext.clear();
            }
        }).hasMessage("No se pudo leer el CSV. Revisá las comillas.");

        assertThat(leerComo(1L, turno, () -> "leyó")).isEqualTo("leyó");
    }

    @Test
    void interrumpirLaEsperaDelTurnoDejaLibreALaFerreteria() throws Exception {
        TurnoDeLectura turno = new TurnoDeLectura(Duration.ofSeconds(30));
        Future<String> deA = pedidos.submit(() -> leerComo(1L, turno, lenta()));
        assertThat(leyendo.await(5, TimeUnit.SECONDS)).isTrue();
        ExecutorService deB = Executors.newSingleThreadExecutor();
        try {
            Future<String> esperando = deB.submit(() -> leerComo(2L, turno, () -> "leyó"));
            Thread.sleep(200);

            esperando.cancel(true);
            deB.shutdown();
            assertThat(deB.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

            terminar.countDown();
            assertThat(deA.get()).isEqualTo("leyó");
            assertThat(leerComo(2L, turno, () -> "leyó")).as("B quedó libre y lee").isEqualTo("leyó");
        } finally {
            deB.shutdownNow();
        }
    }
}
