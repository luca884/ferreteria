package com.ferreteria.facturacion.reintento;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.catalogo.service.RecalculoDiarioJob;
import com.ferreteria.cotizacion.service.ActualizacionCotizacionesJob;
import com.ferreteria.facturacion.motor.MotorFiscal;
import com.ferreteria.facturacion.motor.PasosFiscales;
import com.ferreteria.facturacion.motor.PoliticaDeReintento;
import com.ferreteria.ferreteria.repository.FerreteriaRepository;
import com.ferreteria.support.IntegrationTestBase;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;

/**
 * La programación del job de reintento con las tareas programadas prendidas, como en producción (cu-07-contingencias S2):
 * la pasada corre sola en su hilo y el hilo del scheduler autoconfigurado (uno solo, el del recálculo diario y las
 * cotizaciones) queda libre mientras tanto. La pasada es de mentira (avisa en qué hilo empezó y espera a que el test la
 * suelte), y los dos jobs programados son mocks, para que este contexto no recalcule precios ni consulte cotizaciones. El
 * contexto se cierra al terminar ({@code @DirtiesContext}): ninguna tarea suya sigue corriendo debajo de otros tests.
 */
@TestPropertySource(properties = {"app.programacion.habilitada=true", "app.facturacion.reintento.cada=100ms"})
@DirtiesContext
class ProgramacionDelReintentoIntegrationTest extends IntegrationTestBase {

    private static final long TOPE_SEGUNDOS = 10;

    /** Una pasada que avisa en qué hilo empezó y espera hasta que el test la suelte. */
    static final class JobDePrueba extends ReintentoFiscalJob {

        final BlockingQueue<String> hilos = new LinkedBlockingQueue<>();
        final CountDownLatch soltar = new CountDownLatch(1);

        JobDePrueba(FerreteriaRepository ferreterias, PasosFiscales pasos, MotorFiscal motor,
                PoliticaDeReintento politica, Clock reloj) {
            super(ferreterias, pasos, motor, politica, reloj);
        }

        @Override
        public void pasada(BooleanSupplier sigue) {
            hilos.add(Thread.currentThread().getName());
            try {
                soltar.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JobDePruebaConfig {

        @Bean
        @Primary
        JobDePrueba jobDePrueba(FerreteriaRepository ferreterias, PasosFiscales pasos, MotorFiscal motor,
                PoliticaDeReintento politica, Clock reloj) {
            return new JobDePrueba(ferreterias, pasos, motor, politica, reloj);
        }
    }

    @MockBean
    RecalculoDiarioJob recalculoDiario;
    @MockBean
    ActualizacionCotizacionesJob cotizaciones;

    @Autowired
    JobDePrueba job;
    @Autowired
    TaskScheduler scheduler;
    @Autowired
    ApplicationContext contexto;

    @AfterEach
    void soltar() {
        job.soltar.countDown();
    }

    @Test
    @DisplayName("la pasada corre sola en su propio hilo y, mientras corre, el hilo del scheduler autoconfigurado (el del "
            + "recálculo diario y las cotizaciones) sigue libre")
    void laPasadaCorreEnSuHiloYElSchedulerQuedaLibre() throws Exception {
        String hiloDeLaPasada = job.hilos.poll(TOPE_SEGUNDOS, TimeUnit.SECONDS);
        assertThat(hiloDeLaPasada).as("la pasada arrancó sola").isEqualTo(ProgramacionDelReintento.HILO);

        BlockingQueue<String> hiloDelScheduler = new LinkedBlockingQueue<>();
        scheduler.schedule(() -> hiloDelScheduler.add(Thread.currentThread().getName()), Instant.now());

        assertThat(hiloDelScheduler.poll(TOPE_SEGUNDOS, TimeUnit.SECONDS))
                .as("una tarea programada corre mientras la pasada sigue en curso")
                .startsWith("scheduling-");
        assertThat(job.hilos).as("la pasada en curso no se superpone con otra").isEmpty();
        assertThat(contexto.getBeansOfType(ScheduledExecutorService.class))
                .as("el ejecutor de la pasada no es un bean: si lo fuera, el scheduler autoconfigurado no existiría y los "
                        + "@Scheduled correrían en él")
                .isEmpty();
        assertThat(contexto.getBeansOfType(TaskScheduler.class)).containsOnlyKeys("taskScheduler");
    }
}
