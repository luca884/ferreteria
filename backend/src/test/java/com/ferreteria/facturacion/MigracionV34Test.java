package com.ferreteria.facturacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StreamUtils;
import org.testcontainers.containers.MySQLContainer;

/**
 * Migracion V34 (rama 5 de facturacion, S15): la credencial de ARCA de cada ferreteria (credencial_arca: a lo sumo una
 * PENDIENTE y una ACTIVA) y la CA del simulador (ca_simulador: una por ferreteria), con la clave privada cifrada. Aditiva:
 * ninguna tabla existente cambia. Molde de MigracionV33Test: un MySQL de Testcontainers propio de la clase y una base nueva
 * por test, migrada hasta la V33; siembra por JDBC y recien despues migra a la V34. CUIT: bloque 30-7860133x, con digito
 * verificador valido.
 */
class MigracionV34Test {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    private static final AtomicInteger CONTADOR = new AtomicInteger();
    private static final List<String> TABLAS_NUEVAS = List.of("credencial_arca", "ca_simulador");
    private static final String CUIT_A = "30-78601330-1";
    private static final String CUIT_B = "30-78601332-6";
    private static final String HUELLA = "AB".repeat(32);

    private String url;
    private JdbcTemplate jdbc;

    @BeforeAll
    static void arrancar() {
        MYSQL.start();
    }

    @AfterAll
    static void parar() {
        MYSQL.stop();
    }

    @BeforeEach
    void baseNuevaEnLaVersion33() throws SQLException {
        String base = "v34_" + CONTADOR.incrementAndGet();
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement s = c.createStatement()) {
            s.execute("create database " + base + " character set utf8mb4");
        }
        url = MYSQL.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + base + "$1");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
        flyway("classpath:db/migration").target("33").load().migrate();
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration flyway(String... ubicaciones) {
        return Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations(ubicaciones);
    }

    private void migrarALa34() {
        flyway("classpath:db/migration").target("34").load().migrate();
    }

    @Test
    @DisplayName("crea las dos tablas con sus columnas: ferreteria_id NOT NULL, la clave cifrada en VARBINARY, horas en DATETIME(6)")
    void tablasYColumnas() {
        migrarALa34();

        assertThat(columnas("credencial_arca")).isEqualTo(esperadas(
            "id bigint NO", "ferreteria_id bigint NO", "estado varchar(20) NO", "cuit bigint NO", "huella char(64) NO",
            "clave_privada varbinary(4096) NO", "pedido text NO", "creada_en datetime(6) NO", "certificado text YES",
            "ambiente varchar(20) YES", "emisor varchar(100) YES", "vigente_desde datetime(6) YES",
            "vigente_hasta datetime(6) YES", "activada_en datetime(6) YES", "ca_simulador_id bigint YES"));
        assertThat(columnas("ca_simulador")).isEqualTo(esperadas(
            "id bigint NO", "ferreteria_id bigint NO", "certificado text NO", "huella char(64) NO",
            "clave_privada varbinary(4096) NO", "creada_en datetime(6) NO"));
    }

    @Test
    @DisplayName("la FK compuesta no deja cruzar ferreterias: una credencial de A no apunta a la CA del simulador de B")
    void laFkCompuestaNoCruzaFerreterias() {
        migrarALa34();
        long a = ferreteria(CUIT_A);
        long b = ferreteria(CUIT_B);
        long caDeB = ca(b);

        assertThatThrownBy(() -> activaDelSimulador(a, caDeB)).hasMessageContaining("fk_credencial_arca_ca_simulador");
        // Dentro de la misma ferreteria, si.
        assertThatCode(() -> activaDelSimulador(b, caDeB)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("a lo sumo una credencial PENDIENTE y una ACTIVA por ferreteria, y una CA del simulador por ferreteria")
    void unicos() {
        migrarALa34();
        long a = ferreteria(CUIT_A);
        long b = ferreteria(CUIT_B);
        pendiente(a);
        activa(a, "HOMOLOGACION");
        ca(a);

        assertThatThrownBy(() -> pendiente(a)).hasMessageContaining("uk_credencial_arca_estado");
        assertThatThrownBy(() -> activa(a, "PRODUCCION")).hasMessageContaining("uk_credencial_arca_estado");
        assertThatThrownBy(() -> ca(a)).hasMessageContaining("uk_ca_simulador_ferreteria");
        // Otra ferreteria tiene las suyas.
        pendiente(b);
        activa(b, "PRODUCCION");
        ca(b);
        assertThat(jdbc.queryForObject("select count(*) from credencial_arca", Integer.class)).isEqualTo(4);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
        credencial: estado fuera de la lista | update credencial_arca set estado = 'VENCIDA' where estado = 'PENDIENTE' | ck_credencial_arca_estado
        credencial: CUIT 0 | update credencial_arca set cuit = 0 | ck_credencial_arca_cuit
        credencial: CUIT de 12 cifras | update credencial_arca set cuit = 100000000000 | ck_credencial_arca_cuit
        credencial: huella corta | update credencial_arca set huella = 'AB' | ck_credencial_arca_huella
        credencial: huella en minusculas | update credencial_arca set huella = lower(huella) | ck_credencial_arca_huella
        credencial: huella con separadores | update credencial_arca set huella = concat('A:', substring(huella, 3)) | ck_credencial_arca_huella
        credencial: pendiente con certificado | update credencial_arca set certificado = 'x' where estado = 'PENDIENTE' | ck_credencial_arca_activa
        credencial: pendiente con ambiente | update credencial_arca set ambiente = 'HOMOLOGACION' where estado = 'PENDIENTE' | ck_credencial_arca_activa
        credencial: activa sin certificado | update credencial_arca set certificado = null where estado = 'ACTIVA' | ck_credencial_arca_activa
        credencial: activa sin emisor | update credencial_arca set emisor = null where estado = 'ACTIVA' | ck_credencial_arca_activa
        credencial: activa sin vencimiento | update credencial_arca set vigente_hasta = null where estado = 'ACTIVA' | ck_credencial_arca_activa
        credencial: activa sin fecha de activacion | update credencial_arca set activada_en = null where estado = 'ACTIVA' | ck_credencial_arca_activa
        credencial: ambiente fuera de la lista | update credencial_arca set ambiente = 'PRUEBA' where estado = 'ACTIVA' | ck_credencial_arca_ambiente
        credencial: vence antes de empezar | update credencial_arca set vigente_hasta = vigente_desde where estado = 'ACTIVA' | ck_credencial_arca_vigencia
        credencial: del simulador sin su CA | update credencial_arca set ambiente = 'SIMULADOR' where estado = 'ACTIVA' | ck_credencial_arca_simulador
        ca del simulador: huella en minusculas | update ca_simulador set huella = lower(huella) | ck_ca_simulador_huella
        """)
    @DisplayName("cada CHECK rechaza lo incoherente (estados, CUIT de 11 cifras, huella SHA-256 en hexa, la activa completa)")
    void cadaCheckRechazaLoIncoherente(String caso, String sentencia, String restriccion) {
        migrarALa34();
        long a = ferreteria(CUIT_A);
        pendiente(a);
        activa(a, "HOMOLOGACION");
        ca(a);

        assertThatThrownBy(() -> jdbc.update(sentencia)).as(caso).hasMessageContaining(restriccion);
    }

    @Test
    @DisplayName("una credencial de homologacion o produccion no apunta a la CA del simulador")
    void unaCredencialRealNoLlevaLaCaDelSimulador() {
        migrarALa34();
        long a = ferreteria(CUIT_A);
        long ca = ca(a);
        activa(a, "PRODUCCION");

        assertThatThrownBy(() -> jdbc.update("update credencial_arca set ca_simulador_id = ? where estado = 'ACTIVA'", ca))
            .hasMessageContaining("ck_credencial_arca_simulador");
    }

    @Test
    @DisplayName("lo coherente entra: el pedido pendiente, la activa de homologacion, de produccion y la del simulador con su CA")
    void loCoherenteEntra() {
        migrarALa34();
        long a = ferreteria(CUIT_A);
        long b = ferreteria(CUIT_B);
        pendiente(a);
        activa(a, "PRODUCCION");
        activaDelSimulador(b, ca(b));

        assertThat(jdbc.update("update credencial_arca set huella = '0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"
            + "0123456789ABCDEF', cuit = 99999999999 where ferreteria_id = ?", a)).isEqualTo(2);
        assertThat(jdbc.update("update credencial_arca set ambiente = 'HOMOLOGACION' where ferreteria_id = ? "
            + "and estado = 'ACTIVA'", a)).isEqualTo(1);
    }

    @Test
    @DisplayName("aditiva: ninguna tabla existente cambia (columnas, restricciones ni indices), sus filas quedan igual y no se pierde ninguna migracion")
    void noQuitaNada() {
        long f = ferreteria(CUIT_A);
        jdbc.update("insert into config_arca (ferreteria_id, de_prueba, ambiente, punto_venta) values (?, true, 'SIMULADOR', 1)",
            f);
        Map<String, Object> ferreteriaAntes = jdbc.queryForMap("select * from ferreteria where id = ?", f);
        Map<String, Object> configAntes = jdbc.queryForMap("select * from config_arca where ferreteria_id = ?", f);
        List<String> esquemaAntes = esquema();

        migrarALa34();

        List<String> esquemaDespues = esquema();
        assertThat(esquemaDespues.stream().filter(fila -> !TABLAS_NUEVAS.contains(fila.split(" ")[0])).toList())
            .containsExactlyInAnyOrderElementsOf(esquemaAntes);
        assertThat(esquemaDespues.stream().map(fila -> fila.split(" ")[0]).distinct()
            .filter(tabla -> esquemaAntes.stream().noneMatch(fila -> fila.startsWith(tabla + " "))).toList())
            .containsExactlyInAnyOrderElementsOf(TABLAS_NUEVAS);
        assertThat(jdbc.queryForMap("select * from ferreteria where id = ?", f)).isEqualTo(ferreteriaAntes);
        assertThat(jdbc.queryForMap("select * from config_arca where ferreteria_id = ?", f)).isEqualTo(configAntes);
        assertThat(jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success = 1 and version is not null", Integer.class))
            .isEqualTo(34);
    }

    @Test
    @DisplayName("datos demo: en una base dev existente V34 entra por out-of-order, las ferreterias quedan igual y las tablas nuevas vacias")
    void losDatosDemoSiguenIgual() {
        flyway("classpath:db/dev-data").ignoreMigrationPatterns("*:missing").load().migrate();
        String ferreterias = "select id, razon_social, cuit, condicion_iva from ferreteria order by id";
        List<Map<String, Object>> ferreteriasAntes = jdbc.queryForList(ferreterias);
        assertThat(ferreteriasAntes).isNotEmpty();

        flyway("classpath:db/migration", "classpath:db/dev-data").outOfOrder(true).load().migrate();

        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '34' and success = 1",
            Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList(ferreterias)).isEqualTo(ferreteriasAntes);
        assertThat(TABLAS_NUEVAS).allSatisfy(tabla ->
            assertThat(jdbc.queryForObject("select count(*) from " + tabla, Integer.class)).as(tabla).isZero());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"de corrido", "ignorando errores"})
    @DisplayName("rollback-v34-credencial.sql, sin credenciales, saca las dos tablas y la V34, deja el esquema como en la V33, y la V34 vuelve a entrar")
    void rollbackSinDatos(String modo) throws Exception {
        long f = ferreteria(CUIT_A);
        List<String> esquemaAntes = esquema();
        migrarALa34();

        if (modo.equals("de corrido")) {
            correrRollback();
        } else {
            assertThat(correrRollbackIgnorandoErrores()).isEmpty();
        }

        assertThat(esquema()).containsExactlyInAnyOrderElementsOf(esquemaAntes);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '34'", Integer.class))
            .isZero();
        assertThat(jdbc.queryForObject("select razon_social from ferreteria where id = ?", String.class, f))
            .isEqualTo("Ferreteria " + CUIT_A);
        // Y V34 se puede volver a aplicar.
        migrarALa34();
        assertThat(columnas("credencial_arca")).isNotEmpty();
    }

    @ParameterizedTest(name = "con una fila en {0}")
    @ValueSource(strings = {"credencial_arca", "ca_simulador"})
    @DisplayName("rollback-v34-credencial.sql se niega si hay una sola credencial o CA del simulador, y no toca nada")
    void rollbackConDatosSeNiega(String tabla) {
        migrarALa34();
        sembrarUnaFilaEn(tabla);
        List<String> esquemaAntes = esquema();
        Map<String, Integer> filasAntes = filasDeCredenciales();

        assertThatThrownBy(this::correrRollback).hasMessageContaining("ck_rollback_sin_credenciales");

        assertThat(esquema()).containsExactlyInAnyOrderElementsOf(esquemaAntes);
        assertThat(filasDeCredenciales()).isEqualTo(filasAntes).containsEntry(tabla, 1);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '34'", Integer.class))
            .isEqualTo(1);
    }

    @ParameterizedTest(name = "con una fila en {0}")
    @ValueSource(strings = {"credencial_arca", "ca_simulador"})
    @DisplayName("rollback-v34-credencial.sql corrido sentencia por sentencia ignorando errores (mysql --force, consola) con "
        + "una sola fila no borra ninguna tabla ni la 34 del historial")
    void rollbackIgnorandoErroresConDatosNoBorraNada(String tabla) throws Exception {
        migrarALa34();
        sembrarUnaFilaEn(tabla);
        List<String> esquemaAntes = esquema();
        Map<String, Integer> filasAntes = filasDeCredenciales();

        List<String> errores = correrRollbackIgnorandoErrores();

        assertThat(errores).anySatisfy(error -> assertThat(error).contains("ck_rollback_sin_credenciales"));
        assertThat(esquema()).containsExactlyInAnyOrderElementsOf(esquemaAntes);
        assertThat(filasDeCredenciales()).isEqualTo(filasAntes).containsEntry(tabla, 1);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '34'", Integer.class))
            .isEqualTo(1);
    }

    /** Una sola fila en la tabla pedida: un pedido pendiente, o la CA del simulador sola. */
    private void sembrarUnaFilaEn(String tabla) {
        long f = ferreteria(CUIT_A);
        if (tabla.equals("credencial_arca")) {
            pendiente(f);
        } else {
            ca(f);
        }
    }

    private Map<String, Integer> filasDeCredenciales() {
        Map<String, Integer> filas = new HashMap<>();
        TABLAS_NUEVAS.forEach(t -> filas.put(t, jdbc.queryForObject("select count(*) from " + t, Integer.class)));
        return filas;
    }

    // ---- siembra por JDBC ----

    private long ferreteria(String cuit) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
            "Ferreteria " + cuit, cuit);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
    }

    private long ca(long ferreteria) {
        jdbc.update("insert into ca_simulador (ferreteria_id, certificado, huella, clave_privada, creada_en) "
            + "values (?, '-----BEGIN CERTIFICATE-----', ?, x'0102', '2026-10-28 13:00:00')", ferreteria, HUELLA);
        return jdbc.queryForObject("select id from ca_simulador where ferreteria_id = ?", Long.class, ferreteria);
    }

    /** El pedido de certificado que espera el .crt: sin certificado, ambiente, emisor ni vigencia. */
    private void pendiente(long ferreteria) {
        jdbc.update("insert into credencial_arca (ferreteria_id, estado, cuit, huella, clave_privada, pedido, creada_en) "
            + "values (?, 'PENDIENTE', 30786013301, ?, x'0102', '-----BEGIN CERTIFICATE REQUEST-----', "
            + "'2026-10-28 13:00:00')", ferreteria, HUELLA);
    }

    /** Una credencial activa de homologacion o produccion, vigente del 2026-10-28 al 2028-10-27. */
    private void activa(long ferreteria, String ambiente) {
        jdbc.update("insert into credencial_arca (ferreteria_id, estado, cuit, huella, clave_privada, pedido, creada_en, "
            + "certificado, ambiente, emisor, vigente_desde, vigente_hasta, activada_en) values (?, 'ACTIVA', 30786013301, "
            + "?, x'0102', '-----BEGIN CERTIFICATE REQUEST-----', '2026-10-28 13:00:00', '-----BEGIN CERTIFICATE-----', ?, "
            + "'Computadores Test', '2026-10-28 13:00:00', '2028-10-27 13:00:00', '2026-10-28 13:05:00')",
            ferreteria, HUELLA, ambiente);
    }

    private void activaDelSimulador(long ferreteria, long ca) {
        jdbc.update("insert into credencial_arca (ferreteria_id, estado, cuit, huella, clave_privada, pedido, creada_en, "
            + "certificado, ambiente, emisor, vigente_desde, vigente_hasta, activada_en, ca_simulador_id) values (?, "
            + "'ACTIVA', 30786013301, ?, x'0102', '-----BEGIN CERTIFICATE REQUEST-----', '2026-10-28 13:00:00', "
            + "'-----BEGIN CERTIFICATE-----', 'SIMULADOR', 'Simulador de ARCA', '2026-10-28 13:00:00', "
            + "'2028-10-27 13:00:00', '2026-10-28 13:05:00', ?)", ferreteria, HUELLA, ca);
    }

    // ---- lectura del esquema ----

    /** Columna -> "tipo nulo" de la tabla. */
    private Map<String, String> columnas(String tabla) {
        Map<String, String> columnas = new HashMap<>();
        jdbc.queryForList("select column_name as nombre, column_type as tipo, is_nullable as nulo "
            + "from information_schema.columns where table_schema = database() and table_name = ?", tabla)
            .forEach(c -> columnas.put((String) c.get("nombre"), c.get("tipo") + " " + c.get("nulo")));
        return columnas;
    }

    /** "nombre tipo nulo" -> mapa columna -> "tipo nulo". */
    private static Map<String, String> esperadas(String... columnas) {
        Map<String, String> esperadas = new HashMap<>();
        for (String columna : columnas) {
            int espacio = columna.indexOf(' ');
            esperadas.put(columna.substring(0, espacio), columna.substring(espacio + 1));
        }
        return esperadas;
    }

    /** Todo el esquema, una fila por columna, restriccion, CHECK e indice; cada fila empieza por el nombre de su tabla. */
    private List<String> esquema() {
        List<String> filas = new ArrayList<>();
        filas.addAll(jdbc.queryForList("select concat_ws(' ', table_name, 'columna', column_name, column_type, is_nullable, "
            + "coalesce(column_default, '-'), extra) from information_schema.columns where table_schema = database()",
            String.class));
        filas.addAll(jdbc.queryForList("select concat_ws(' ', table_name, constraint_type, constraint_name) "
            + "from information_schema.table_constraints where constraint_schema = database()", String.class));
        filas.addAll(jdbc.queryForList("select concat_ws(' ', t.table_name, 'check', c.constraint_name, c.check_clause) "
            + "from information_schema.check_constraints c join information_schema.table_constraints t "
            + "on t.constraint_schema = c.constraint_schema and t.constraint_name = c.constraint_name "
            + "and t.constraint_type = 'CHECK' where c.constraint_schema = database()", String.class));
        filas.addAll(jdbc.queryForList("select concat_ws(' ', table_name, 'indice', index_name, non_unique, seq_in_index, "
            + "coalesce(column_name, expression)) from information_schema.statistics where table_schema = database()",
            String.class));
        return filas;
    }

    private void correrRollback() throws Exception {
        Properties propiedades = new Properties();
        propiedades.setProperty("user", "root");
        propiedades.setProperty("password", MYSQL.getPassword());
        propiedades.setProperty("allowMultiQueries", "true");
        try (Connection c = DriverManager.getConnection(url, propiedades); Statement s = c.createStatement()) {
            s.execute(scriptDeRollback());
        }
    }

    /**
     * Como un mysql --force o el script pegado en una consola: sentencia por sentencia (sin los comentarios, cortando en cada
     * punto y coma), sigue aunque una falle. Devuelve los mensajes de las que fallaron.
     */
    private List<String> correrRollbackIgnorandoErrores() throws Exception {
        String sinComentarios = scriptDeRollback().lines().filter(linea -> !linea.strip().startsWith("--"))
            .collect(Collectors.joining("\n"));
        List<String> errores = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url, "root", MYSQL.getPassword()); Statement s = c.createStatement()) {
            for (String sentencia : sinComentarios.split(";")) {
                if (sentencia.isBlank()) {
                    continue;
                }
                try {
                    s.execute(sentencia);
                } catch (SQLException e) {
                    errores.add(e.getMessage());
                }
            }
        }
        return errores;
    }

    private static String scriptDeRollback() throws IOException {
        return StreamUtils.copyToString(
            new ClassPathResource("facturacion/rollback-v34-credencial.sql").getInputStream(), StandardCharsets.UTF_8);
    }
}
