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
 * Migracion V35 (rama 6 de facturacion, S2, S3 y S6): el ticket de acceso de WSAA por (ferreteria, servicio, ambiente)
 * (ticket_acceso: el TA cifrado, su lease, la espera y la ultima falla), el estado del simulador de WSAA (ticket_simulado) y
 * el bloqueo de C4 en config_arca. Aditiva: dos tablas nuevas y tres columnas nullable. Molde de MigracionV34Test: un MySQL
 * de Testcontainers propio de la clase y una base nueva por test, migrada hasta la V34; siembra por JDBC y recien despues
 * migra a la V35. CUIT: bloque 30-7860135x, con digito verificador valido.
 */
class MigracionV35Test {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    private static final AtomicInteger CONTADOR = new AtomicInteger();
    private static final List<String> TABLAS_NUEVAS = List.of("ticket_acceso", "ticket_simulado");
    private static final List<String> COLUMNAS_DEL_BLOQUEO = List.of("bloqueo_codigo", "bloqueo_mensaje", "bloqueado_en");
    private static final String CUIT_A = "30-78601350-6";
    private static final String CUIT_B = "30-78601351-4";
    private static final String HUELLA = "CD".repeat(32);

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
    void baseNuevaEnLaVersion34() throws SQLException {
        String base = "v35_" + CONTADOR.incrementAndGet();
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement s = c.createStatement()) {
            s.execute("create database " + base + " character set utf8mb4");
        }
        url = MYSQL.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + base + "$1");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
        flyway("classpath:db/migration").target("34").load().migrate();
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration flyway(String... ubicaciones) {
        return Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations(ubicaciones);
    }

    private void migrarALa35() {
        flyway("classpath:db/migration").target("35").load().migrate();
    }

    @Test
    @DisplayName("crea las dos tablas y las tres columnas del bloqueo: ferreteria_id NOT NULL, el token y el sign cifrados en "
        + "VARBINARY, horas en DATETIME(6) y todo lo del TA nullable")
    void tablasYColumnas() {
        migrarALa35();

        assertThat(columnas("ticket_acceso")).isEqualTo(esperadas(
            "id bigint NO", "ferreteria_id bigint NO", "servicio varchar(32) NO", "ambiente varchar(20) NO",
            "huella_credencial char(64) YES", "token_cifrado varbinary(8192) YES", "sign_cifrado varbinary(1024) YES",
            "generado datetime(6) YES", "vence datetime(6) YES", "lease_dueno varchar(36) YES",
            "lease_vence datetime(6) YES", "espera_hasta datetime(6) YES", "ultima_falla varchar(64) YES",
            "ultima_falla_en datetime(6) YES"));
        assertThat(columnas("ticket_simulado")).isEqualTo(esperadas(
            "id bigint NO", "ferreteria_id bigint NO", "servicio varchar(32) NO", "huella char(64) NO",
            "generado datetime(6) NO", "vence datetime(6) NO"));
        assertThat(columnas("config_arca")).containsAllEntriesOf(esperadas(
            "bloqueo_codigo varchar(64) YES", "bloqueo_mensaje varchar(500) YES", "bloqueado_en datetime(6) YES"));
    }

    @Test
    @DisplayName("un ticket por (ferreteria, servicio, ambiente) y un ticket simulado por (ferreteria, servicio)")
    void unicos() {
        migrarALa35();
        long a = ferreteria(CUIT_A);
        long b = ferreteria(CUIT_B);
        ticketVacio(a, "wsfe", "HOMOLOGACION");
        simulado(a, "wsfe");

        assertThatThrownBy(() -> ticketVacio(a, "wsfe", "HOMOLOGACION")).hasMessageContaining("uk_ticket_acceso_clave");
        assertThatThrownBy(() -> simulado(a, "wsfe")).hasMessageContaining("uk_ticket_simulado_clave");
        // Otro ambiente, otro servicio u otra ferreteria tienen el suyo.
        ticketVacio(a, "wsfe", "PRODUCCION");
        ticketVacio(a, "wsfex", "HOMOLOGACION");
        ticketVacio(b, "wsfe", "HOMOLOGACION");
        simulado(a, "wsfex");
        simulado(b, "wsfe");
        assertThat(jdbc.queryForObject("select count(*) from ticket_acceso", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select count(*) from ticket_simulado", Integer.class)).isEqualTo(3);
    }

    @Test
    @DisplayName("las dos tablas exigen una ferreteria que exista (FK)")
    void lasFkExigenLaFerreteria() {
        migrarALa35();
        long a = ferreteria(CUIT_A);
        long inexistente = a + 1000;

        assertThatThrownBy(() -> ticketVacio(inexistente, "wsfe", "HOMOLOGACION"))
            .hasMessageContaining("fk_ticket_acceso_ferreteria");
        assertThatThrownBy(() -> simulado(inexistente, "wsfe")).hasMessageContaining("fk_ticket_simulado_ferreteria");
        assertThatCode(() -> ticketVacio(a, "wsfe", "HOMOLOGACION")).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
        ticket: ambiente fuera de la lista | update ticket_acceso set ambiente = 'PRUEBA' | ck_ticket_acceso_ambiente
        ticket: huella corta | update ticket_acceso set huella_credencial = 'CD' | ck_ticket_acceso_huella
        ticket: huella en minusculas | update ticket_acceso set huella_credencial = lower(huella_credencial) | ck_ticket_acceso_huella
        ticket: huella con separadores | update ticket_acceso set huella_credencial = concat('C:', substring(huella_credencial, 3)) | ck_ticket_acceso_huella
        ticket: TA sin la huella | update ticket_acceso set huella_credencial = null | ck_ticket_acceso_ta
        ticket: TA sin el token | update ticket_acceso set token_cifrado = null | ck_ticket_acceso_ta
        ticket: TA sin el sign | update ticket_acceso set sign_cifrado = null | ck_ticket_acceso_ta
        ticket: TA sin generado | update ticket_acceso set generado = null | ck_ticket_acceso_ta
        ticket: TA sin vencimiento | update ticket_acceso set vence = null | ck_ticket_acceso_ta
        ticket: solo el token | update ticket_acceso set huella_credencial = null, sign_cifrado = null, generado = null, vence = null | ck_ticket_acceso_ta
        ticket: lease sin vencimiento | update ticket_acceso set lease_vence = null | ck_ticket_acceso_lease
        ticket: lease sin duenio | update ticket_acceso set lease_dueno = null | ck_ticket_acceso_lease
        simulado: huella corta | update ticket_simulado set huella = 'CD' | ck_ticket_simulado_huella
        simulado: huella en minusculas | update ticket_simulado set huella = lower(huella) | ck_ticket_simulado_huella
        config: bloqueo sin hora | update config_arca set bloqueo_codigo = 'cms.cert.expired' | ck_config_arca_bloqueo
        config: hora sin codigo | update config_arca set bloqueado_en = '2026-10-29 12:00:00' | ck_config_arca_bloqueo
        config: mensaje sin codigo | update config_arca set bloqueo_mensaje = 'Certificado expirado' | ck_config_arca_bloqueo
        """)
    @DisplayName("cada CHECK rechaza lo incoherente (ambiente, huella SHA-256 en hexa, el TA entero o nada, el lease de a dos "
        + "y el bloqueo con su hora)")
    void cadaCheckRechazaLoIncoherente(String caso, String sentencia, String restriccion) {
        migrarALa35();
        long a = ferreteria(CUIT_A);
        configArca(a);
        ticketConTaYLease(a);
        simulado(a, "wsfe");

        assertThatThrownBy(() -> jdbc.update(sentencia)).as(caso).hasMessageContaining(restriccion);
    }

    @Test
    @DisplayName("lo coherente entra: el ticket sin TA, con lease y sin TA, el TA con lease, espera y falla, el bloqueo con o "
        + "sin mensaje, y su levantada")
    void loCoherenteEntra() {
        migrarALa35();
        long a = ferreteria(CUIT_A);
        configArca(a);
        ticketVacio(a, "wsfe", "SIMULADOR");
        ticketConTaYLease(a);
        simulado(a, "wsfe");

        assertThat(jdbc.update("update ticket_acceso set lease_dueno = 'f3b0c2a4-1d2e-4f5a-8b9c-0d1e2f3a4b5c', "
            + "lease_vence = '2026-10-29 12:01:30', espera_hasta = '2026-10-29 12:10:00', ultima_falla = "
            + "'coe.alreadyAuthenticated', ultima_falla_en = '2026-10-29 12:00:00' where ambiente = 'SIMULADOR'")).isOne();
        assertThat(jdbc.update("update ticket_acceso set huella_credencial = '0123456789ABCDEF0123456789ABCDEF0123456789"
            + "ABCDEF0123456789ABCDEF', lease_dueno = null, lease_vence = null where ambiente = 'HOMOLOGACION'")).isOne();
        // Descartar el TA (el 600) deja la fila con la espera y la falla.
        assertThat(jdbc.update("update ticket_acceso set huella_credencial = null, token_cifrado = null, sign_cifrado = "
            + "null, generado = null, vence = null where ambiente = 'HOMOLOGACION'")).isOne();
        assertThat(jdbc.update("update config_arca set bloqueo_codigo = 'cms.cert.untrusted', bloqueo_mensaje = "
            + "'Certificado no emitido por AC de confianza', bloqueado_en = '2026-10-29 12:00:00'")).isOne();
        assertThat(jdbc.update("update config_arca set bloqueo_mensaje = null")).isOne();
        assertThat(jdbc.update("update config_arca set bloqueo_codigo = null, bloqueado_en = null")).isOne();
        assertThat(jdbc.update("update ticket_simulado set huella = '0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF"
            + "0123456789ABCDEF'")).isOne();
    }

    @Test
    @DisplayName("aditiva: las filas de config_arca quedan como estaban y sin bloqueo; ninguna otra tabla cambia y config_arca "
        + "solo suma las tres columnas y su CHECK; no se pierde ninguna migracion")
    void noQuitaNadaYLasConfiguracionesQuedanSinBloqueo() {
        long a = ferreteria(CUIT_A);
        long b = ferreteria(CUIT_B);
        configArca(a);
        jdbc.update("insert into config_arca (ferreteria_id, ambiente, punto_venta) values (?, 'HOMOLOGACION', 3)", b);
        List<Map<String, Object>> configsAntes = jdbc.queryForList("select * from config_arca order by ferreteria_id");
        Map<String, Object> ferreteriaAntes = jdbc.queryForMap("select * from ferreteria where id = ?", a);
        List<String> esquemaAntes = esquema();

        migrarALa35();

        List<Map<String, Object>> configsDespues = jdbc.queryForList("select * from config_arca order by ferreteria_id");
        assertThat(configsDespues).hasSize(2).allSatisfy(fila -> assertThat(fila)
            .containsEntry("bloqueo_codigo", null).containsEntry("bloqueo_mensaje", null).containsEntry("bloqueado_en", null));
        assertThat(configsDespues.stream().map(fila -> sinElBloqueo(fila)).toList()).isEqualTo(configsAntes);
        assertThat(jdbc.queryForMap("select * from ferreteria where id = ?", a)).isEqualTo(ferreteriaAntes);

        List<String> esquemaDespues = esquema();
        assertThat(esquemaDespues).containsAll(esquemaAntes);
        List<String> agregadas = esquemaDespues.stream().filter(fila -> !esquemaAntes.contains(fila))
            .filter(fila -> !TABLAS_NUEVAS.contains(fila.split(" ")[0])).toList();
        // Las tres columnas, la restriccion CHECK y su clausula: nada mas fuera de las tablas nuevas.
        assertThat(agregadas).hasSize(5).allSatisfy(fila -> assertThat(fila).startsWith("config_arca ")
            .containsAnyOf("bloqueo_codigo", "bloqueo_mensaje", "bloqueado_en", "ck_config_arca_bloqueo"));
        assertThat(esquemaDespues.stream().map(fila -> fila.split(" ")[0]).distinct()
            .filter(tabla -> esquemaAntes.stream().noneMatch(fila -> fila.startsWith(tabla + " "))).toList())
            .containsExactlyInAnyOrderElementsOf(TABLAS_NUEVAS);
        assertThat(jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success = 1 and version is not null", Integer.class))
            .isEqualTo(35);
    }

    @Test
    @DisplayName("datos demo: en una base dev existente V35 entra por out-of-order, las demo con el simulador quedan sin "
        + "bloqueo y las tablas nuevas vacias")
    void losDatosDemoSiguenIgual() {
        flyway("classpath:db/dev-data", "classpath:db/dev-only").ignoreMigrationPatterns("*:missing").load().migrate();
        String ferreterias = "select id, razon_social, cuit, condicion_iva from ferreteria order by id";
        List<Map<String, Object>> ferreteriasAntes = jdbc.queryForList(ferreterias);
        List<Map<String, Object>> configsAntes = jdbc.queryForList("select * from config_arca order by ferreteria_id");
        assertThat(ferreteriasAntes).isNotEmpty();
        assertThat(configsAntes).isNotEmpty();

        flyway("classpath:db/migration", "classpath:db/dev-data", "classpath:db/dev-only").outOfOrder(true).load().migrate();

        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '35' and success = 1",
            Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList(ferreterias)).isEqualTo(ferreteriasAntes);
        List<Map<String, Object>> configsDespues = jdbc.queryForList("select * from config_arca order by ferreteria_id");
        assertThat(configsDespues).allSatisfy(fila -> assertThat(fila).containsEntry("bloqueo_codigo", null)
            .containsEntry("bloqueado_en", null));
        assertThat(configsDespues.stream().map(fila -> sinElBloqueo(fila)).toList()).isEqualTo(configsAntes);
        assertThat(TABLAS_NUEVAS).allSatisfy(tabla ->
            assertThat(jdbc.queryForObject("select count(*) from " + tabla, Integer.class)).as(tabla).isZero());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"de corrido", "ignorando errores"})
    @DisplayName("rollback-v35-ticket-de-acceso-arca.sql, sin bloqueos (aunque haya tickets), saca las dos tablas, las tres "
        + "columnas y la V35, deja el esquema y config_arca como en la V34, y la V35 vuelve a entrar")
    void rollbackSinBloqueos(String modo) throws Exception {
        long a = ferreteria(CUIT_A);
        configArca(a);
        Map<String, Object> configAntes = jdbc.queryForMap("select * from config_arca where ferreteria_id = ?", a);
        List<String> esquemaAntes = esquema();
        migrarALa35();
        ticketConTaYLease(a);
        simulado(a, "wsfe");

        if (modo.equals("de corrido")) {
            correrRollback();
        } else {
            assertThat(correrRollbackIgnorandoErrores()).isEmpty();
        }

        assertThat(esquema()).containsExactlyInAnyOrderElementsOf(esquemaAntes);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '35'", Integer.class))
            .isZero();
        assertThat(jdbc.queryForObject("select max(cast(version as unsigned)) from flyway_schema_history", Integer.class))
            .isEqualTo(34);
        assertThat(jdbc.queryForMap("select * from config_arca where ferreteria_id = ?", a)).isEqualTo(configAntes);
        // Y V35 se puede volver a aplicar.
        migrarALa35();
        assertThat(columnas("ticket_acceso")).isNotEmpty();
        assertThat(columnas("config_arca")).containsKeys("bloqueo_codigo", "bloqueo_mensaje", "bloqueado_en");
    }

    @Test
    @DisplayName("rollback-v35-ticket-de-acceso-arca.sql se niega si una sola ferreteria tiene el bloqueo, y no toca nada")
    void rollbackConUnBloqueoSeNiega() {
        migrarALa35();
        sembrarUnBloqueo();
        List<String> esquemaAntes = esquema();
        Map<String, Integer> filasAntes = filas();
        List<Map<String, Object>> configsAntes = jdbc.queryForList("select * from config_arca order by ferreteria_id");

        assertThatThrownBy(this::correrRollback).hasMessageContaining("ck_rollback_sin_bloqueos");

        assertThat(esquema()).containsExactlyInAnyOrderElementsOf(esquemaAntes);
        assertThat(filas()).isEqualTo(filasAntes);
        assertThat(jdbc.queryForList("select * from config_arca order by ferreteria_id")).isEqualTo(configsAntes);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '35'", Integer.class))
            .isEqualTo(1);
    }

    @Test
    @DisplayName("rollback-v35-ticket-de-acceso-arca.sql corrido sentencia por sentencia ignorando errores (mysql --force, "
        + "consola) con un solo bloqueo no borra ninguna tabla, columna ni la 35 del historial")
    void rollbackIgnorandoErroresConUnBloqueoNoBorraNada() throws Exception {
        migrarALa35();
        sembrarUnBloqueo();
        List<String> esquemaAntes = esquema();
        Map<String, Integer> filasAntes = filas();
        List<Map<String, Object>> configsAntes = jdbc.queryForList("select * from config_arca order by ferreteria_id");

        List<String> errores = correrRollbackIgnorandoErrores();

        assertThat(errores).anySatisfy(error -> assertThat(error).contains("ck_rollback_sin_bloqueos"));
        assertThat(errores).anySatisfy(error -> assertThat(error).contains("ck_rollback_bloqueo_ya_sacado"));
        assertThat(esquema()).containsExactlyInAnyOrderElementsOf(esquemaAntes);
        assertThat(filas()).isEqualTo(filasAntes);
        assertThat(jdbc.queryForList("select * from config_arca order by ferreteria_id")).isEqualTo(configsAntes);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '35'", Integer.class))
            .isEqualTo(1);
    }

    /** Dos ferreterias con su configuracion y sus tickets; solo la A con el bloqueo de C4. */
    private void sembrarUnBloqueo() {
        long a = ferreteria(CUIT_A);
        long b = ferreteria(CUIT_B);
        configArca(a);
        configArca(b);
        ticketConTaYLease(a);
        simulado(b, "wsfe");
        jdbc.update("update config_arca set bloqueo_codigo = 'coe.notAuthorized', bloqueo_mensaje = 'Computador no "
            + "autorizado a acceder al servicio', bloqueado_en = '2026-10-29 12:00:00' where ferreteria_id = ?", a);
    }

    private Map<String, Integer> filas() {
        Map<String, Integer> filas = new HashMap<>();
        TABLAS_NUEVAS.forEach(t -> filas.put(t, jdbc.queryForObject("select count(*) from " + t, Integer.class)));
        return filas;
    }

    /** La fila de config_arca sin las tres columnas de la V35: la que habia antes. */
    private static Map<String, Object> sinElBloqueo(Map<String, Object> fila) {
        Map<String, Object> sin = new java.util.LinkedHashMap<>(fila);
        COLUMNAS_DEL_BLOQUEO.forEach(sin::remove);
        return sin;
    }

    // ---- siembra por JDBC ----

    private long ferreteria(String cuit) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
            "Ferreteria " + cuit, cuit);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
    }

    /** La configuracion de una ferreteria de prueba con el simulador y la facturacion prendida. */
    private void configArca(long ferreteria) {
        jdbc.update("insert into config_arca (ferreteria_id, de_prueba, ambiente, punto_venta, facturacion_habilitada, "
            + "habilitada_en) values (?, true, 'SIMULADOR', 1, true, '2026-10-28 13:00:00')", ferreteria);
    }

    /** La fila del ticket antes del primer loginCms: solo la clave. */
    private void ticketVacio(long ferreteria, String servicio, String ambiente) {
        jdbc.update("insert into ticket_acceso (ferreteria_id, servicio, ambiente) values (?, ?, ?)", ferreteria, servicio,
            ambiente);
    }

    /** Un TA de homologacion para wsfe, vigente 12 horas, con el lease de quien lo pidio. */
    private void ticketConTaYLease(long ferreteria) {
        jdbc.update("insert into ticket_acceso (ferreteria_id, servicio, ambiente, huella_credencial, token_cifrado, "
            + "sign_cifrado, generado, vence, lease_dueno, lease_vence) values (?, 'wsfe', 'HOMOLOGACION', ?, x'0102', "
            + "x'0304', '2026-10-29 11:55:00', '2026-10-29 23:55:00', '9a8b7c6d-5e4f-4a3b-9c2d-1e0f9a8b7c6d', "
            + "'2026-10-29 12:01:30')", ferreteria, HUELLA);
    }

    /** El TA que emitio el simulador, vigente 10 minutos. */
    private void simulado(long ferreteria, String servicio) {
        jdbc.update("insert into ticket_simulado (ferreteria_id, servicio, huella, generado, vence) values (?, ?, ?, "
            + "'2026-10-29 12:00:00', '2026-10-29 12:10:00')", ferreteria, servicio, HUELLA);
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
            new ClassPathResource("facturacion/rollback-v35-ticket-de-acceso-arca.sql").getInputStream(),
            StandardCharsets.UTF_8);
    }
}
