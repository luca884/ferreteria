package com.ferreteria.facturacion.reintento;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La programación del job de reintento (cu-07-contingencias S2 y C5) sin Spring, con un job de mentira que avisa cuando
 * empieza cada pasada y espera a que el test lo suelte: el hilo propio, la espera desde el fin de la pasada anterior, sin
 * superponerse, y el apagado.
 */
class ProgramacionDelReintentoTest {

    private static final Duration CADA = Duration.ofMillis(50);
    /** Varias veces {@link #CADA}: lo que se espera para ver que algo NO pasa. */
    private static final Duration UN_RATO = Duration.ofMillis(300);
    private static final long TOPE_SEGUNDOS = 10;

    /** Una pasada que empezó: en qué hilo, cuándo, con qué «sigue», y el latch que la termina. */
    private record Pasada(Thread hilo, long empezo, BooleanSupplier sigue, CountDownLatch soltar) {
    }

    private final ReintentoFiscalJob job = mock(ReintentoFiscalJob.class);
    private final BlockingQueue<Pasada> pasadas = new LinkedBlockingQueue<>();
    private final BlockingQueue<Long> terminadas = new LinkedBlockingQueue<>();
    private ProgramacionDelReintento programacion;

    /** Cada pasada del job avisa que empezó y espera a que el test la suelte; si {@code falla}, lanza al terminar. */
    private void jobQueEspera(boolean falla) {
        doAnswer(invocacion -> {
            CountDownLatch soltar = new CountDownLatch(1);
            pasadas.add(new Pasada(Thread.currentThread(), System.nanoTime(), invocacion.getArgument(0), soltar));
            soltar.await(TOPE_SEGUNDOS, TimeUnit.SECONDS);
            terminadas.add(System.nanoTime());
            if (falla) {
                throw new IllegalStateException("la base no responde");
            }
            return null;
        }).when(job).pasada(any(BooleanSupplier.class));
    }

    private Pasada siguientePasada() throws InterruptedException {
        Pasada pasada = pasadas.poll(TOPE_SEGUNDOS, TimeUnit.SECONDS);
        assertThat(pasada).as("arrancó otra pasada").isNotNull();
        return pasada;
    }

    @AfterEach
    void apagar() {
        if (programacion != null) {
            programacion.stop(() -> { });
        }
        pasadas.forEach(p -> p.soltar().countDown());
    }

    @Test
    @DisplayName("la pasada corre en su propio hilo, no se superpone con la anterior y la siguiente arranca «cada» después "
            + "de que terminó la anterior")
    void correEnSuHiloSinSuperponerseYDesdeElFinDeLaAnterior() throws Exception {
        jobQueEspera(false);
        programacion = new ProgramacionDelReintento(job, CADA);

        programacion.start();

        assertThat(programacion.isRunning()).isTrue();
        Pasada primera = siguientePasada();
        assertThat(primera.hilo().getName()).isEqualTo(ProgramacionDelReintento.HILO);
        assertThat(primera.hilo().isDaemon()).as("no frena la salida de la JVM").isTrue();
        assertThat(primera.sigue().getAsBoolean()).isTrue();

        Thread.sleep(UN_RATO.toMillis());
        assertThat(pasadas).as("mientras corre la primera no arranca otra").isEmpty();

        primera.soltar().countDown();
        long finDeLaPrimera = terminadas.poll(TOPE_SEGUNDOS, TimeUnit.SECONDS);
        Pasada segunda = siguientePasada();
        assertThat(Duration.ofNanos(segunda.empezo() - finDeLaPrimera)).isGreaterThanOrEqualTo(CADA);
        assertThat(segunda.hilo().getName()).isEqualTo(ProgramacionDelReintento.HILO);
    }

    @Test
    @DisplayName("al apagar, la pasada en curso deja de tomar series y el apagado no la espera: avisa cuando termina y no "
            + "arranca otra; sin pasada en curso, avisa enseguida")
    void alApagarDejaDeTomarSeriesYAvisaCuandoTerminaLaPasada() throws Exception {
        jobQueEspera(false);
        programacion = new ProgramacionDelReintento(job, CADA);
        programacion.start();
        Pasada enCurso = siguientePasada();

        CountDownLatch avisado = new CountDownLatch(1);
        long antes = System.nanoTime();
        programacion.stop(avisado::countDown);

        assertThat(Duration.ofNanos(System.nanoTime() - antes)).as("stop no espera la pasada").isLessThan(UN_RATO);
        assertThat(programacion.isRunning()).isFalse();
        assertThat(enCurso.sigue().getAsBoolean()).as("la pasada en curso no toma otra serie").isFalse();
        assertThat(avisado.getCount()).as("el aviso espera a que termine la pasada en curso").isEqualTo(1);

        enCurso.soltar().countDown();
        assertThat(avisado.await(TOPE_SEGUNDOS, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(UN_RATO.toMillis());
        assertThat(pasadas).as("después del apagado no arranca otra pasada").isEmpty();

        ProgramacionDelReintento quieta = new ProgramacionDelReintento(job, Duration.ofHours(1));
        quieta.start();
        CountDownLatch avisadaQuieta = new CountDownLatch(1);
        quieta.stop(avisadaQuieta::countDown);
        assertThat(avisadaQuieta.await(TOPE_SEGUNDOS, TimeUnit.SECONDS)).isTrue();
        assertThat(pasadas).isEmpty();
    }

    @Test
    @DisplayName("una pasada que lanza no corta la programación: la siguiente arranca igual")
    void unaPasadaQueFallaNoCortaLasSiguientes() throws Exception {
        jobQueEspera(true);
        programacion = new ProgramacionDelReintento(job, CADA);
        programacion.start();

        siguientePasada().soltar().countDown();
        Pasada segunda = siguientePasada();
        segunda.soltar().countDown();

        assertThat(segunda.hilo().getName()).isEqualTo(ProgramacionDelReintento.HILO);
    }
}
