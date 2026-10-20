package com.ferreteria.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.support.BarreraDeLocks.HolderJdbc;
import com.ferreteria.support.BarreraDeLocks.Hito;
import com.ferreteria.support.BarreraDeLocks.Largada;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * CCT3-201: la infraestructura de los tests de concurrencia deterministas se prueba a sí misma contra MySQL real. Cada test
 * trabaja sobre una tabla propia de dos filas (creada por root, fuera de las entidades de la aplicación) para no mezclarse
 * con los datos de otros tests.
 */
class BarreraDeLocksTest extends IntegrationTestBase {

    private static final String TABLA = "barrera_prueba";

    private final BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
    private final List<HolderJdbc> abiertos = new ArrayList<>();
    private ExecutorService pool;

    @AfterAll
    static void borrarTabla() {
        jdbcRoot().execute("drop table if exists " + TABLA);
    }

    @BeforeEach
    void filas() {
        // La tabla se crea acá y no en un @BeforeAll: antes de que arranque el contexto, Flyway vería el esquema no vacío.
        jdbcRoot().execute("create table if not exists " + TABLA + " (id int primary key, v int not null) engine=InnoDB");
        jdbcRoot().update("delete from " + TABLA);
        jdbcRoot().update("insert into " + TABLA + " (id, v) values (1, 0), (2, 0)");
        pool = Executors.newCachedThreadPool();
    }

    @AfterEach
    void cerrar() {
        for (HolderJdbc holder : abiertos) {
            holder.rollback();
        }
        pool.shutdownNow();
    }

    private HolderJdbc holder(String... sql) {
        HolderJdbc holder = BarreraDeLocks.holderJdbc(sql);
        abiertos.add(holder);
        return holder;
    }

    private static String bloquearFila(int id) {
        return "select id from " + TABLA + " where id = " + id + " for update";
    }

    @Test
    @DisplayName("esperarBloqueados(1) retorna cuando otro hilo está en LOCK WAIT sobre la fila que sostiene el holder, y no antes")
    void esperaAQueElOtroEsteBloqueado() throws Exception {
        HolderJdbc quienTiene = holder(bloquearFila(1));
        HolderJdbc quienEspera = holder();
        Future<?> espera = pool.submit(() -> quienEspera.ejecutar(bloquearFila(1)));

        barrera.esperarBloqueados(1);

        assertThat(barrera.bloqueadosAhora()).as("el hilo B está de verdad en LOCK WAIT").isGreaterThanOrEqualTo(1);
        assertThat(espera.isDone()).as("B sigue esperando el lock de A").isFalse();
        quienTiene.commit();
        espera.get(30, TimeUnit.SECONDS); // al soltar A, B termina
    }

    @Test
    @DisplayName("esperarBloqueados(2) espera a que haya DOS hilos en LOCK WAIT")
    void esperaADosBloqueados() throws Exception {
        HolderJdbc quienTiene = holder(bloquearFila(1));
        HolderJdbc holderB = holder();
        HolderJdbc holderC = holder();
        Future<?> b = pool.submit(() -> holderB.ejecutar(bloquearFila(1)));
        barrera.esperarBloqueados(1);
        Future<?> c = pool.submit(() -> holderC.ejecutar(bloquearFila(1)));

        barrera.esperarBloqueados(2);

        assertThat(barrera.bloqueadosAhora()).isGreaterThanOrEqualTo(2);
        quienTiene.commit();
        b.get(30, TimeUnit.SECONDS);
        // C termina cuando B suelte: B conserva su tx hasta que la confirmemos
        holderB.commit();
        c.get(30, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("sin nadie bloqueado y con tope corto: AssertionError con el volcado de data_lock_waits")
    void sinBloqueadosVencePorTope() {
        BarreraDeLocks conTopeCorto = new BarreraDeLocks(jdbcRoot(), Duration.ofMillis(300));

        assertThatThrownBy(() -> conTopeCorto.esperarBloqueados(1))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("data_lock_waits")
                .hasMessageContaining("LOCK WAIT");
    }

    @Test
    @DisplayName("esperarBloqueados(0) retorna de inmediato (no hay nada que esperar)")
    void cero() {
        barrera.esperarBloqueados(0);
    }

    @Test
    @DisplayName("Hito: A avisa que llegó y B la espera; B avisa y A sigue; con timeout vencido falla")
    void hito() throws Exception {
        Hito llegoA = new Hito();
        AtomicBoolean llegoAntes = new AtomicBoolean();
        Future<?> hilo = pool.submit(() -> {
            llegoA.avisar();
            return null;
        });
        llegoA.esperar(Duration.ofSeconds(30));
        hilo.get(30, TimeUnit.SECONDS);
        llegoAntes.set(true);
        assertThat(llegoAntes).isTrue();

        Hito nuncaLlega = new Hito();
        assertThatThrownBy(() -> nuncaLlega.esperar(Duration.ofMillis(100)))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("hito");
    }

    @Test
    @DisplayName("Largada: nadie corre hasta que todos están listos y se da la largada; todos arrancan juntos")
    void largadaComun() throws Exception {
        int n = 4;
        Largada largada = new Largada(n);
        List<Callable<Integer>> trabajos = new ArrayList<>();
        AtomicBoolean largo = new AtomicBoolean();
        for (int i = 0; i < n; i++) {
            int numero = i;
            trabajos.add(() -> {
                largada.listoYEsperar();
                assertThat(largo).as("nadie corre antes de la largada").isTrue();
                return numero;
            });
        }
        List<Future<Integer>> futuros = new ArrayList<>();
        for (Callable<Integer> trabajo : trabajos) {
            futuros.add(pool.submit(trabajo));
        }

        largada.esperarListos(Duration.ofSeconds(30));
        assertThat(futuros).noneMatch(Future::isDone);
        largo.set(true);
        largada.largar();

        List<Integer> resultados = new ArrayList<>();
        for (Future<Integer> futuro : futuros) {
            resultados.add(futuro.get(30, TimeUnit.SECONDS));
        }
        assertThat(resultados).containsExactly(0, 1, 2, 3);
    }

    @Test
    @DisplayName("un deadlock inducido a propósito con dos conexiones cruzadas da delta 1")
    void deadlockInducidoDaDeltaUno() throws Exception {
        long antes = barrera.deadlocksAntes();
        HolderJdbc h1 = holder(bloquearFila(1));
        HolderJdbc h2 = holder(bloquearFila(2));
        Future<?> cruza1 = pool.submit(() -> h1.ejecutar(bloquearFila(2))); // h1 espera la fila de h2

        barrera.esperarBloqueados(1); // recién con h1 esperando, h2 cierra el ciclo
        List<Throwable> errores = new ArrayList<>();
        try {
            h2.ejecutar(bloquearFila(1));
        } catch (RuntimeException e) {
            errores.add(e);
        }
        try {
            cruza1.get(30, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            errores.add(e.getCause());
        }

        assertThat(errores).as("InnoDB eligió exactamente una víctima").hasSize(1);
        assertThat(errores.get(0).getCause()).isInstanceOf(SQLException.class);
        assertThat(((SQLException) errores.get(0).getCause()).getErrorCode()).as("1213 = ER_LOCK_DEADLOCK").isEqualTo(1213);
        assertThat(barrera.deadlocksDespues(antes)).isEqualTo(1);
        assertThatThrownBy(() -> barrera.assertSinDeadlocks(antes)).isInstanceOf(AssertionError.class);
    }

    @Test
    @DisplayName("sin deadlock el delta es 0, aunque haya espera de lock")
    void sinDeadlockDeltaCero() throws Exception {
        long antes = barrera.deadlocksAntes();
        HolderJdbc h1 = holder(bloquearFila(1));
        HolderJdbc h2 = holder();
        Future<?> espera = pool.submit(() -> h2.ejecutar(bloquearFila(1)));
        barrera.esperarBloqueados(1);
        h1.commit();
        espera.get(30, TimeUnit.SECONDS);

        assertThat(barrera.deadlocksDespues(antes)).isZero();
        barrera.assertSinDeadlocks(antes);
    }

    @Test
    @DisplayName("objetosEnEspera dice sobre QUÉ tabla espera el bloqueado (y queda vacío cuando nadie espera)")
    void objetosEnEspera() throws Exception {
        assertThat(barrera.objetosEnEspera()).as("nadie espera todavía").isEmpty();
        HolderJdbc quienTiene = holder(bloquearFila(1));
        HolderJdbc quienEspera = holder();
        Future<?> espera = pool.submit(() -> quienEspera.ejecutar(bloquearFila(1)));

        barrera.esperarBloqueados(1);

        assertThat(barrera.objetosEnEspera()).containsExactly(TABLA);
        quienTiene.commit();
        espera.get(30, TimeUnit.SECONDS);
        quienEspera.commit();
        assertThat(barrera.objetosEnEspera()).as("ya nadie espera").isEmpty();
    }

    @Test
    @DisplayName("sePuedeTomarYa: NOWAIT desde otra conexión entra si la fila está libre y es rechazado si la retiene otro; no deja nada tomado")
    void sePuedeTomarYa() {
        assertThat(BarreraDeLocks.sePuedeTomarYa(TABLA, 1)).as("libre").isTrue();
        HolderJdbc quienTiene = holder(bloquearFila(1));

        assertThat(BarreraDeLocks.sePuedeTomarYa(TABLA, 1)).as("retenida por el holder").isFalse();
        assertThat(BarreraDeLocks.sePuedeTomarYa(TABLA, 2)).as("otra fila, libre").isTrue();
        quienTiene.commit();
        assertThat(BarreraDeLocks.sePuedeTomarYa(TABLA, 1)).as("soltada").isTrue();
        assertThat(BarreraDeLocks.sePuedeTomarYa(TABLA, 1)).as("la consulta anterior no dejó el lock tomado").isTrue();
    }

    @Test
    @DisplayName("holderJdbc abre una tx, ejecuta y retiene el lock hasta commit() o rollback(); rollback descarta los cambios")
    void holderRetieneYSuelta() throws Exception {
        HolderJdbc h = holder("update " + TABLA + " set v = 7 where id = 1");
        HolderJdbc otro = holder();
        otro.ejecutar("set session innodb_lock_wait_timeout = 1");

        assertThatThrownBy(() -> otro.ejecutar(bloquearFila(1)))
                .as("el holder retiene el lock de la fila 1")
                .isInstanceOf(RuntimeException.class);
        assertThat(jdbcRoot().queryForObject("select v from " + TABLA + " where id = 1", Integer.class))
                .as("cambio sin commitear: invisible desde afuera").isZero();

        h.rollback();
        assertThat(jdbcRoot().queryForObject("select v from " + TABLA + " where id = 1", Integer.class)).isZero();

        HolderJdbc confirma = holder("update " + TABLA + " set v = 9 where id = 2");
        confirma.commit();
        assertThat(jdbcRoot().queryForObject("select v from " + TABLA + " where id = 2", Integer.class)).isEqualTo(9);
    }
}
