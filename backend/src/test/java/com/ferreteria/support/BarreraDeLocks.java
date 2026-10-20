package com.ferreteria.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Sincronización DETERMINISTA para los tests de concurrencia contra MySQL real (design §9 de cuenta-corriente-tramo-3a). La
 * regla del proyecto es que un test de concurrencia no se apoya en el paso del tiempo ({@code Thread.sleep}, jitter): el
 * resultado depende de una condición observable, «el hilo B está esperando un lock en MySQL», no de cuánto tardó en llegar.
 *
 * <ul>
 *   <li>{@link #esperarBloqueados(int)}: consulta {@code performance_schema.data_lock_waits} (con el JdbcTemplate de root,
 *       el usuario de la aplicación no tiene PROCESS) hasta que haya al menos n transacciones en {@code LOCK WAIT}. El
 *       intervalo de consulta es FIJO (5 ms) y hay un tope duro (30 s por defecto): si vence falla con un
 *       {@link AssertionError} que vuelca {@code performance_schema.data_lock_waits}. Es la ÚNICA espera con reloj de la
 *       clase, acotada y documentada: no decide ningún resultado, solo es la frecuencia con que se mira la condición.
 *   <li>{@link Hito}: «A llegó a su punto» / «seguí» (un latch con timeout).
 *   <li>{@link Largada}: listos + largada común, para que N hilos arranquen juntos.
 *   <li>{@link #deadlocksAntes()} / {@link #deadlocksDespues(long)}: delta de {@code lock_deadlocks} de
 *       {@code INNODB_METRICS}; todo test de concurrencia nuevo exige delta 0.
 *   <li>{@link #holderJdbc(String...)}: una conexión cruda con la transacción abierta que sostiene locks (simula a otro
 *       actor sin tocar el código de la aplicación).
 * </ul>
 *
 * Las vistas de InnoDB son globales al servidor: sirven porque los tests de una JVM no corren en paralelo entre sí.
 */
public final class BarreraDeLocks {

    /** Frecuencia fija con que se mira la condición (no condiciona ningún resultado). */
    static final long INTERVALO_MS = 5;

    /** Código de MySQL de «no se pudo tomar el lock de inmediato y se pidió NOWAIT». */
    private static final int ER_LOCK_NOWAIT = 3572;

    public static final Duration TOPE_POR_DEFECTO = Duration.ofSeconds(30);

    private final JdbcTemplate root;
    private final Duration tope;

    public BarreraDeLocks(JdbcTemplate root) {
        this(root, TOPE_POR_DEFECTO);
    }

    public BarreraDeLocks(JdbcTemplate root, Duration tope) {
        this.root = root;
        this.tope = tope;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // LOCK WAIT
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Cuántas transacciones están ahora mismo esperando un lock en el servidor (las distintas que figuran como
     * «solicitantes» en {@code performance_schema.data_lock_waits}, que es una vista EN VIVO de InnoDB).
     *
     * <p>Desvío del design, que decía {@code INNODB_TRX}: esa tabla de {@code information_schema} está CACHEADA y la caché
     * solo se refresca si pasaron más de 100 ms desde la ÚLTIMA lectura; consultarla cada 5 ms la deja congelada y devuelve
     * un estado viejo (se observó: un hilo ya liberado seguía figurando en LOCK WAIT y el que recién se bloqueaba, no).
     * {@code data_lock_waits} no tiene ese problema. Es la misma condición observable.
     */
    public int bloqueadosAhora() {
        Integer n = root.queryForObject(
                "select count(distinct requesting_engine_transaction_id) from performance_schema.data_lock_waits",
                Integer.class);
        return n == null ? 0 : n;
    }

    /** Retorna cuando hay al menos {@code n} transacciones en {@code LOCK WAIT}; {@code AssertionError} si vence el tope. */
    public void esperarBloqueados(int n) {
        long limite = System.nanoTime() + tope.toNanos();
        while (bloqueadosAhora() < n) {
            if (System.nanoTime() > limite) {
                throw new AssertionError("Venció el tope de " + tope.toMillis() + " ms esperando " + n
                        + " transacción(es) en LOCK WAIT; hay " + bloqueadosAhora() + ". " + volcado());
            }
            pausaDeConsulta();
        }
    }

    /**
     * Las tablas sobre las que están esperando AHORA las transacciones en {@code LOCK WAIT} (el objeto del lock solicitado:
     * {@code performance_schema.data_lock_waits} unida con {@code data_locks}), sin repetidos y ordenadas. Sirve para afirmar
     * QUÉ espera un hilo bloqueado y no solo que espera (un test que solo mira «hay 1 bloqueado» pasa aunque espere otra cosa).
     */
    public List<String> objetosEnEspera() {
        return root.queryForList("select distinct d.OBJECT_NAME from performance_schema.data_lock_waits w "
                + "join performance_schema.data_locks d on d.ENGINE_LOCK_ID = w.REQUESTING_ENGINE_LOCK_ID "
                + "order by d.OBJECT_NAME", String.class);
    }

    /**
     * ¿Se puede tomar YA la fila {@code tabla.id} para escribir? Desde una conexión nueva (autocommit) ejecuta
     * {@code select id from tabla where id = ? for update nowait}: {@code true} si entra (y se libera en el acto, no deja nada
     * tomado), {@code false} si MySQL la rechaza por tenerla otro (error 3572, «lock(s) could not be acquired immediately and
     * NOWAIT is set»). Es una condición observable sin tiempos: «¿retiene la cobranza bloqueada este lock?».
     */
    public static boolean sePuedeTomarYa(String tabla, long id) {
        try (Connection c = DriverManager.getConnection(IntegrationTestBase.MYSQL.getJdbcUrl(),
                IntegrationTestBase.MYSQL.getUsername(), IntegrationTestBase.MYSQL.getPassword());
                Statement st = c.createStatement()) {
            st.execute("select id from " + tabla + " where id = " + id + " for update nowait");
            return true;
        } catch (SQLException e) {
            if (e.getErrorCode() == ER_LOCK_NOWAIT) {
                return false;
            }
            throw new AssertionError("NOWAIT sobre " + tabla + " " + id + " falló por otra causa: " + e.getMessage(), e);
        }
    }

    /** El polling acotado y documentado de la barrera: intervalo fijo, nunca decide el resultado del test. */
    private static void pausaDeConsulta() {
        try {
            Thread.sleep(INTERVALO_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrumpido mientras se esperaba un LOCK WAIT", e);
        }
    }

    private String volcado() {
        StringBuilder sb = new StringBuilder("Estado de InnoDB: performance_schema.data_lock_waits = ");
        sb.append(filas("select REQUESTING_ENGINE_TRANSACTION_ID, BLOCKING_ENGINE_TRANSACTION_ID "
                + "from performance_schema.data_lock_waits"));
        sb.append("; INNODB_TRX (cacheada, solo orientativa) = ");
        sb.append(filas("select trx_id, trx_state, trx_mysql_thread_id, trx_query from information_schema.INNODB_TRX"));
        return sb.toString();
    }

    private String filas(String sql) {
        try {
            List<Map<String, Object>> filas = root.queryForList(sql);
            return filas.isEmpty() ? "[]" : filas.stream().map(Object::toString).collect(Collectors.joining(", ", "[", "]"));
        } catch (RuntimeException e) {
            return "(no se pudo leer: " + e.getMessage() + ")";
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Deadlocks
    // ---------------------------------------------------------------------------------------------------------------

    /** El contador actual de deadlocks del servidor; se llama ANTES del escenario y se pasa a {@link #deadlocksDespues}. */
    public long deadlocksAntes() {
        habilitarMetrica();
        return contadorDeDeadlocks();
    }

    /** Deadlocks ocurridos desde {@code antes}. */
    public long deadlocksDespues(long antes) {
        return contadorDeDeadlocks() - antes;
    }

    /** Falla (con la cantidad) si hubo algún deadlock desde {@code antes}: el delta exigido a todo test de concurrencia es 0. */
    public void assertSinDeadlocks(long antes) {
        long delta = deadlocksDespues(antes);
        if (delta != 0) {
            throw new AssertionError("Hubo " + delta + " deadlock(s) de InnoDB (lock_deadlocks) y se esperaban 0");
        }
    }

    private void habilitarMetrica() {
        String estado = estadoDeLaMetrica();
        if (!"enabled".equalsIgnoreCase(estado)) {
            // Ruidoso si falla: sin la métrica habilitada no hay forma de afirmar «cero deadlocks».
            root.execute("set global innodb_monitor_enable = 'lock_deadlocks'");
            estado = estadoDeLaMetrica();
            if (!"enabled".equalsIgnoreCase(estado)) {
                throw new AssertionError("No se pudo habilitar la métrica lock_deadlocks (estado: " + estado + ")");
            }
        }
    }

    private String estadoDeLaMetrica() {
        List<String> estados = root.queryForList(
                "select status from information_schema.INNODB_METRICS where name = 'lock_deadlocks'", String.class);
        if (estados.isEmpty()) {
            throw new AssertionError("MySQL no expone la métrica lock_deadlocks en INNODB_METRICS");
        }
        return estados.get(0);
    }

    private long contadorDeDeadlocks() {
        Long n = root.queryForObject("select count from information_schema.INNODB_METRICS where name = 'lock_deadlocks'",
                Long.class);
        return n == null ? 0 : n;
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Hito y largada
    // ---------------------------------------------------------------------------------------------------------------

    /** «A llegó a su punto»: un latch de un solo disparo con timeout. */
    public static final class Hito {
        private final CountDownLatch latch = new CountDownLatch(1);

        public void avisar() {
            latch.countDown();
        }

        public void esperar() {
            esperar(TOPE_POR_DEFECTO);
        }

        public void esperar(Duration tope) {
            try {
                if (!latch.await(tope.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new AssertionError("Venció el tope de " + tope.toMillis() + " ms esperando un hito");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrumpido esperando un hito", e);
            }
        }
    }

    /** Listos + largada común: cada hilo llama {@link #listoYEsperar()}; el test espera a todos y da la largada. */
    public static final class Largada {
        private final CountDownLatch listos;
        private final CountDownLatch largada = new CountDownLatch(1);

        public Largada(int participantes) {
            this.listos = new CountDownLatch(participantes);
        }

        /** Lo llama cada hilo: avisa que está listo y espera la largada. */
        public void listoYEsperar() {
            listos.countDown();
            try {
                if (!largada.await(TOPE_POR_DEFECTO.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new AssertionError("Venció el tope esperando la largada");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrumpido esperando la largada", e);
            }
        }

        /** El test espera a que todos los hilos estén listos. */
        public void esperarListos(Duration tope) {
            try {
                if (!listos.await(tope.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new AssertionError("Venció el tope de " + tope.toMillis() + " ms esperando que estén listos todos los hilos");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrumpido esperando a los hilos", e);
            }
        }

        public void largar() {
            largada.countDown();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Holder JDBC
    // ---------------------------------------------------------------------------------------------------------------

    /**
     * Abre una conexión cruda (usuario de la aplicación, autocommit apagado), ejecuta cada sentencia en la misma transacción
     * y la deja abierta: sostiene los locks hasta {@link HolderJdbc#commit()} o {@link HolderJdbc#rollback()}.
     */
    public static HolderJdbc holderJdbc(String... sql) {
        HolderJdbc holder = HolderJdbc.abrir();
        try {
            for (String sentencia : sql) {
                holder.ejecutar(sentencia);
            }
        } catch (RuntimeException e) {
            holder.rollback();
            throw e;
        }
        return holder;
    }

    /** Una conexión con su transacción abierta. */
    public static final class HolderJdbc implements AutoCloseable {
        private final Connection conexion;

        private HolderJdbc(Connection conexion) {
            this.conexion = conexion;
        }

        private static HolderJdbc abrir() {
            try {
                Connection c = DriverManager.getConnection(IntegrationTestBase.MYSQL.getJdbcUrl(),
                        IntegrationTestBase.MYSQL.getUsername(), IntegrationTestBase.MYSQL.getPassword());
                c.setAutoCommit(false);
                return new HolderJdbc(c);
            } catch (SQLException e) {
                throw new ErrorDeHolder("No se pudo abrir la conexión del holder", e);
            }
        }

        /** Ejecuta una sentencia dentro de la transacción abierta (si es un SELECT, descarta el resultado). */
        public void ejecutar(String sql) {
            try (Statement st = conexion.createStatement()) {
                st.execute(sql);
            } catch (SQLException e) {
                throw new ErrorDeHolder("Falló «" + sql + "»: " + e.getMessage(), e);
            }
        }

        public void commit() {
            try {
                conexion.commit();
            } catch (SQLException e) {
                throw new ErrorDeHolder("Falló el commit del holder", e);
            } finally {
                cerrarConexion();
            }
        }

        /** Deshace y cierra; tolera una conexión ya cerrada (se llama también desde los {@code @AfterEach}). */
        public void rollback() {
            try {
                if (!conexion.isClosed()) {
                    conexion.rollback();
                }
            } catch (SQLException ignorado) {
                // la conexión puede haber muerto por un deadlock/timeout: el cierre la limpia igual
            } finally {
                cerrarConexion();
            }
        }

        @Override
        public void close() {
            rollback();
        }

        private void cerrarConexion() {
            try {
                conexion.close();
            } catch (SQLException ignorado) {
                // nada que hacer
            }
        }
    }

    /** Falla de una sentencia del holder; la {@link SQLException} original queda como causa. */
    public static final class ErrorDeHolder extends RuntimeException {
        public ErrorDeHolder(String mensaje, SQLException causa) {
            super(mensaje, causa);
        }
    }
}
