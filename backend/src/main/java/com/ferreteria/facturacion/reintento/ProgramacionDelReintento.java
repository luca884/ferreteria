package com.ferreteria.facturacion.reintento;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle;
import org.springframework.context.SmartLifecycle;

/**
 * Corre el job de reintento ({@link ReintentoFiscalJob}) en su propio hilo, {@value #HILO}, y no en el del scheduler
 * autoconfigurado: ese tiene un solo hilo, compartido con el recálculo diario y las cotizaciones, y una pasada puede tardar
 * hasta su tope (rama 7, cu-07-contingencias S2). Cada pasada arranca {@code cada} ({@code app.facturacion.reintento.cada})
 * después de que terminó la anterior, así nunca se superponen; la primera, {@code cada} después de arrancar. Una pasada que
 * lanza no corta las siguientes. El hilo es daemon: no frena la salida de la JVM.
 *
 * <p>El ejecutor es propio y no un bean, a propósito: con un {@code TaskScheduler} o un {@code ScheduledExecutorService} en
 * el contexto, Spring Boot no autoconfigura el scheduler de los {@code @Scheduled}, y esos pasarían a correr en otro lado.
 *
 * <p>Al apagar ({@link #stop(Runnable)}), la pasada en curso deja de tomar ferreterías y series (la serie que está
 * atendiendo el motor sigue), no arranca otra, y Spring recibe el aviso cuando la pasada en curso termina. Va en la fase del
 * apagado ordenado del servidor web ({@link WebServerGracefulShutdownLifecycle#SMART_LIFECYCLE_PHASE}): Spring detiene a la
 * par todo lo de una fase y la espera hasta {@code spring.lifecycle.timeout-per-shutdown-phase}, así que la pasada se espera
 * en la misma ventana que los pedidos web, sin sumar tiempo al drenaje de Railway ({@code ApagadoOrdenadoTest}). Si no
 * termina a tiempo, o la JVM muere con una llamada en vuelo, la serie queda tomada hasta que vence su lease y la pasada
 * siguiente, en la instancia nueva, la recupera verificando con ARCA: el camino normal del motor, sin reenviar a ciegas.
 *
 * <p>Existe solo con {@code app.programacion.habilitada} ({@code ReintentoFiscalConfig}), como las tareas programadas; en
 * los tests está apagada y el job se llama directo.
 */
public class ProgramacionDelReintento implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(ProgramacionDelReintento.class);

    /** El nombre del hilo de las pasadas. */
    public static final String HILO = "facturacion-reintento";

    private final ReintentoFiscalJob job;
    private final Duration cada;
    private final AtomicBoolean apagando = new AtomicBoolean();
    /** El de la programación en marcha: null antes de arrancar y después de apagar. */
    private ScheduledExecutorService ejecutor;

    /**
     * @param cada entre el fin de una pasada y el principio de la siguiente
     */
    public ProgramacionDelReintento(ReintentoFiscalJob job, Duration cada) {
        this.job = job;
        this.cada = Objects.requireNonNull(cada, "Falta cada cuánto corre el job de reintento.");
    }

    @Override
    public synchronized void start() {
        if (ejecutor != null) {
            return;
        }
        apagando.set(false);
        ejecutor = Executors.newSingleThreadScheduledExecutor(tarea -> {
            Thread hilo = new Thread(tarea, HILO);
            hilo.setDaemon(true);
            return hilo;
        });
        ejecutor.scheduleWithFixedDelay(this::pasada, cada.toNanos(), cada.toNanos(), TimeUnit.NANOSECONDS);
        log.info("Job de reintento fiscal en marcha: una pasada cada {} desde el fin de la anterior.", cada);
    }

    /** Una pasada, sin dejar salir nada: una excepción que sale de la tarea cancelaría las pasadas siguientes. */
    private void pasada() {
        try {
            job.pasada(() -> !apagando.get());
        } catch (RuntimeException e) {
            log.error("Falló una pasada del reintento fiscal; la próxima corre igual.", e);
        }
    }

    @Override
    public void stop() {
        stop(() -> { });
    }

    /**
     * La pasada en curso deja de tomar series, no arranca otra y {@code alTerminar} corre cuando la pasada en curso termina
     * (o ya, si no hay ninguna). No espera: vuelve enseguida.
     */
    @Override
    public synchronized void stop(Runnable alTerminar) {
        apagando.set(true);
        if (ejecutor == null) {
            alTerminar.run();
            return;
        }
        // El hilo es uno solo: el aviso corre después de la pasada en curso. Con el shutdown no arranca otra pasada, pero
        // el aviso, ya encolado, corre igual.
        ejecutor.execute(alTerminar);
        ejecutor.shutdown();
        ejecutor = null;
        log.info("Apagando el job de reintento fiscal: no arranca otra pasada.");
    }

    @Override
    public synchronized boolean isRunning() {
        return ejecutor != null;
    }

    /** La fase del apagado ordenado del servidor web: se espera a la par con los pedidos en curso. */
    @Override
    public int getPhase() {
        return WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE;
    }
}
