package com.ferreteria.ventas;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
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

import com.ferreteria.support.PlantillaDeMigracion;

/**
 * Migracion V37 (CU-08, cu-08-nota-de-credito T2: S4, S9, S10 y S11; contratos C2 y C3): la nota de credito y la anulacion
 * interna son comprobantes NOTA_CREDITO asociados a la venta que anulan, una sola por venta, y la anulacion interna lleva
 * su motivo. Molde de MigracionV36Test: un MySQL de Testcontainers propio de la clase y una base nueva por test, migrada
 * hasta la V36; siembra por JDBC y recien despues migra a la V37.
 */
class MigracionV37Test {

    private static final MySQLContainer<?> MYSQL = PlantillaDeMigracion.MYSQL;
    private static final AtomicInteger COMPROBANTES = new AtomicInteger();
    private static final List<String> COLUMNAS_NUEVAS = List.of("comprobante_asociado_id", "motivo_anulacion");

    private String url;
    private JdbcTemplate jdbc;

    @BeforeEach
    void baseNuevaEnLaVersion36() {
        url = PlantillaDeMigracion.url(PlantillaDeMigracion.baseEnLaVersion("v37", "36"));
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration flyway(String... ubicaciones) {
        return Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations(ubicaciones);
    }

    private void migrarALa37() {
        flyway("classpath:db/migration").target("37").load().migrate();
    }

    @Test
    @DisplayName("suma al comprobante la venta asociada (bigint, nula) y el motivo de la anulacion interna (hasta 200, nulo)")
    void columnasNuevas() {
        migrarALa37();

        assertThat(columnas()).containsAllEntriesOf(Map.of(
            "comprobante_asociado_id", "bigint YES -",
            "motivo_anulacion", "varchar(200) YES -"));
    }

    @Test
    @DisplayName("una sola nota por venta: la segunda choca con uk_comprobante_asociado; otra venta, y la de otra ferreteria, "
        + "tienen la suya")
    void unaSolaNotaPorVenta() {
        migrarALa37();
        Escenario a = escenario("a");
        nota(a, a.venta(), null);

        assertThatThrownBy(() -> nota(a, a.venta(), "Se cobro dos veces")).hasMessageContaining("uk_comprobante_asociado");
        assertThatCode(() -> nota(a, venta(a.ferreteria(), a.usuario()), null)).doesNotThrowAnyException();
        Escenario b = escenario("b");
        assertThatCode(() -> nota(b, b.venta(), null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("la FK compuesta: la venta asociada tiene que existir y ser de la misma ferreteria")
    void laVentaAsociadaEsDeLaFerreteria() {
        migrarALa37();
        Escenario a = escenario("a");
        Escenario b = escenario("b");

        assertThat(indice("uk_comprobante_asociado")).containsExactly("ferreteria_id", "comprobante_asociado_id");
        assertThatThrownBy(() -> nota(a, b.venta(), null)).hasMessageContaining("fk_comprobante_asociado");
        assertThatThrownBy(() -> nota(a, a.venta() + 1000, null)).hasMessageContaining("fk_comprobante_asociado");
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|', quoteCharacter = '"', textBlock = """
        venta con asociado         | VENTA        | ASOCIADO | null
        nota sin asociado          | NOTA_CREDITO | null     | null
        venta con motivo           | VENTA        | null     | 'Se cobro dos veces'
        motivo en blanco           | NOTA_CREDITO | ASOCIADO | '   '
        motivo vacio               | NOTA_CREDITO | ASOCIADO | ''
        """)
    @DisplayName("ck_comprobante_nota_de_credito: el asociado va si y solo si es NOTA_CREDITO, y el motivo solo en una "
        + "NOTA_CREDITO y con texto")
    void elCheckRechazaLoIncoherente(String caso, String tipo, String asociado, String motivo) {
        migrarALa37();
        Escenario a = escenario("a");
        String valorAsociado = asociado.equals("ASOCIADO") ? String.valueOf(a.venta()) : "null";

        assertThatThrownBy(() -> insertar(a, tipo, valorAsociado, motivo)).as(caso)
            .hasMessageContaining("ck_comprobante_nota_de_credito");
    }

    @Test
    @DisplayName("lo coherente entra: la nota sin motivo, la anulacion interna con un motivo de 200, y no de 201; sin caja")
    void loCoherenteEntra() {
        migrarALa37();
        Escenario a = escenario("a");
        long otra = venta(a.ferreteria(), a.usuario());

        long nota = nota(a, a.venta(), null);
        long anulacion = nota(a, otra, "x".repeat(200));

        assertThat(jdbc.queryForMap("select tipo, comprobante_asociado_id, motivo_anulacion, caja_id from comprobante "
            + "where id = ?", nota)).containsEntry("tipo", "NOTA_CREDITO").containsEntry("comprobante_asociado_id",
            a.venta()).containsEntry("motivo_anulacion", null).containsEntry("caja_id", null);
        assertThat(jdbc.queryForObject("select motivo_anulacion from comprobante where id = ?", String.class, anulacion))
            .hasSize(200);
        assertThatThrownBy(() -> nota(a, venta(a.ferreteria(), a.usuario()), "x".repeat(201)))
            .hasMessageContaining("motivo_anulacion");
    }

    @Test
    @DisplayName("aditiva para los datos: las ventas quedan como estaban, sin asociado ni motivo; solo cambia comprobante y "
        + "no se pierde ninguna migracion")
    void losDatosQuedanComoEstaban() {
        Escenario a = escenario("a");
        venta(a.ferreteria(), a.usuario());
        List<Map<String, Object>> antes = jdbc.queryForList("select * from comprobante order by id");
        List<String> esquemaAntes = esquema();

        migrarALa37();

        List<Map<String, Object>> despues = jdbc.queryForList("select * from comprobante order by id");
        assertThat(despues).hasSize(2).allSatisfy(fila -> assertThat(fila)
            .containsEntry("comprobante_asociado_id", null).containsEntry("motivo_anulacion", null));
        assertThat(despues.stream().map(MigracionV37Test::sinLasNuevas).toList()).isEqualTo(antes);
        List<String> esquemaDespues = esquema();
        assertThat(esquemaAntes.stream().filter(fila -> !esquemaDespues.contains(fila)).toList()).isEmpty();
        assertThat(esquemaDespues.stream().filter(fila -> !esquemaAntes.contains(fila)).toList()).isNotEmpty()
            .allSatisfy(fila -> assertThat(fila).startsWith("comprobante ").containsAnyOf("comprobante_asociado_id",
                "motivo_anulacion", "uk_comprobante_asociado", "fk_comprobante_asociado",
                "ck_comprobante_nota_de_credito"));
        assertThat(jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success = 1 and version is not null", Integer.class))
            .isEqualTo(37);
    }

    @Test
    @DisplayName("datos demo: en una base dev existente V37 entra por out-of-order y deja los comprobantes como estaban")
    void losDatosDemoSiguenIgual() {
        flyway("classpath:db/dev-data", "classpath:db/dev-only").ignoreMigrationPatterns("*:missing").load().migrate();
        List<Map<String, Object>> antes = jdbc.queryForList("select * from comprobante order by id");

        flyway("classpath:db/migration", "classpath:db/dev-data", "classpath:db/dev-only").outOfOrder(true).load().migrate();

        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '37' and success = 1",
            Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("select * from comprobante order by id").stream()
            .map(MigracionV37Test::sinLasNuevas).toList()).isEqualTo(antes);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"de corrido", "ignorando errores"})
    @DisplayName("rollback-v37-nota-de-credito.sql, sin notas de credito, deja el esquema como en la V36, las ventas como "
        + "estaban, saca la V37 del historial y la V37 vuelve a entrar")
    void rollbackSinNotas(String modo) throws Exception {
        Escenario a = escenario("a");
        venta(a.ferreteria(), a.usuario());
        List<String> esquemaAntes = esquema();
        migrarALa37();
        venta(a.ferreteria(), a.usuario());
        List<Map<String, Object>> ventasAntes = jdbc.queryForList("select * from comprobante order by id").stream()
            .map(MigracionV37Test::sinLasNuevas).toList();

        if (modo.equals("de corrido")) {
            correrRollback();
        } else {
            assertThat(correrRollbackIgnorandoErrores()).isEmpty();
        }

        assertThat(esquema()).containsExactlyInAnyOrderElementsOf(esquemaAntes);
        assertThat(jdbc.queryForList("select * from comprobante order by id")).isEqualTo(ventasAntes);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '37'", Integer.class))
            .isZero();
        assertThat(jdbc.queryForObject("select max(cast(version as unsigned)) from flyway_schema_history", Integer.class))
            .isEqualTo(36);
        // Y V37 se puede volver a aplicar.
        migrarALa37();
        assertThat(columnas()).containsKeys(COLUMNAS_NUEVAS.toArray(String[]::new));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"una nota de credito", "una anulacion interna"})
    @DisplayName("rollback-v37-nota-de-credito.sql se niega con una sola NOTA_CREDITO, y no toca nada")
    void rollbackConNotasSeNiega(String caso) {
        migrarALa37();
        sembrarNota(caso);
        List<String> esquemaAntes = esquema();
        List<Map<String, Object>> antes = jdbc.queryForList("select * from comprobante order by id");

        assertThatThrownBy(this::correrRollback).hasMessageContaining("ck_rollback_v37_sin_notas");

        assertThat(esquema()).containsExactlyInAnyOrderElementsOf(esquemaAntes);
        assertThat(jdbc.queryForList("select * from comprobante order by id")).isEqualTo(antes);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '37'", Integer.class))
            .isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"una nota de credito", "una anulacion interna"})
    @DisplayName("rollback-v37-nota-de-credito.sql corrido sentencia por sentencia ignorando errores (mysql --force, consola) "
        + "con una sola NOTA_CREDITO no borra ninguna columna, restriccion, indice ni la 37 del historial")
    void rollbackIgnorandoErroresConNotasNoBorraNada(String caso) throws Exception {
        migrarALa37();
        sembrarNota(caso);
        List<String> esquemaAntes = esquema();
        List<Map<String, Object>> antes = jdbc.queryForList("select * from comprobante order by id");

        List<String> errores = correrRollbackIgnorandoErrores();

        assertThat(errores).anySatisfy(error -> assertThat(error).contains("ck_rollback_v37_sin_notas"));
        assertThat(esquema()).containsExactlyInAnyOrderElementsOf(esquemaAntes);
        assertThat(jdbc.queryForList("select * from comprobante order by id")).isEqualTo(antes);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '37'", Integer.class))
            .isEqualTo(1);
    }

    /** Dos ferreterias con ventas; solo la A con la nota del caso (la de ARCA, sin motivo, o la anulacion interna). */
    private void sembrarNota(String caso) {
        Escenario a = escenario("a");
        escenario("b");
        nota(a, a.venta(), caso.equals("una anulacion interna") ? "El cliente se arrepintio" : null);
    }

    /** La fila sin las columnas de la V37: la que habia antes. */
    private static Map<String, Object> sinLasNuevas(Map<String, Object> fila) {
        Map<String, Object> sin = new LinkedHashMap<>(fila);
        COLUMNAS_NUEVAS.forEach(sin::remove);
        return sin;
    }

    // ---- siembra por JDBC ----

    private record Escenario(long ferreteria, long usuario, long venta) {}

    /** Una ferreteria con su Dueño y una venta. */
    private Escenario escenario(String sufijo) {
        // Cada test tiene su base: alcanza con que la A y la B no choquen (uk_ferreteria_cuit, hasta 13 caracteres).
        String cuit = "30-78601370-" + sufijo;
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
            "Ferreteria V37 " + sufijo, cuit);
        long f = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) values (?, ?, 'x', 'Dueno V37', "
            + "'DUENO')", f, "v37-" + f + "@test.local");
        long usuario = jdbc.queryForObject("select min(id) from usuario where ferreteria_id = ?", Long.class, f);
        return new Escenario(f, usuario, venta(f, usuario));
    }

    /** Una venta insertada como antes de la V37, sin las columnas nuevas: sirve antes y despues de migrar. */
    private long venta(long ferreteria, long usuario) {
        int numero = COMPROBANTES.incrementAndGet();
        jdbc.update("insert into comprobante (ferreteria_id, tipo, estado, numero, clave_idempotencia, usuario_id, neto, iva, "
            + "total, subtotal) values (?, 'VENTA', 'SIN_FACTURAR', ?, ?, ?, 100, 21, 121, 121)", ferreteria, numero,
            "v37-" + numero, usuario);
        return jdbc.queryForObject("select id from comprobante where ferreteria_id = ? and numero = ?", Long.class,
            ferreteria, numero);
    }

    /** La NOTA_CREDITO de la venta, sin caja: con motivo es una anulacion interna. */
    private long nota(Escenario e, long venta, String motivo) {
        return comprobante(e.ferreteria(), e.usuario(), "NOTA_CREDITO", String.valueOf(venta), motivo);
    }

    private long comprobante(long ferreteria, long usuario, String tipo, String asociado, String motivo) {
        int numero = COMPROBANTES.incrementAndGet();
        jdbc.update("insert into comprobante (ferreteria_id, tipo, estado, numero, clave_idempotencia, usuario_id, neto, iva, "
            + "total, subtotal, comprobante_asociado_id, motivo_anulacion) values (?, ?, 'SIN_FACTURAR', ?, ?, ?, 100, 21, "
            + "121, 121, " + asociado + ", ?)", ferreteria, tipo, numero, "v37-" + numero, usuario, motivo);
        return jdbc.queryForObject("select id from comprobante where ferreteria_id = ? and numero = ?", Long.class,
            ferreteria, numero);
    }

    /** Para el CHECK: el motivo viene como literal SQL del CSV ({@code null}, {@code ''} o {@code 'texto'}). */
    private void insertar(Escenario e, String tipo, String asociado, String motivo) {
        int numero = COMPROBANTES.incrementAndGet();
        jdbc.update("insert into comprobante (ferreteria_id, tipo, estado, numero, clave_idempotencia, usuario_id, neto, iva, "
            + "total, subtotal, comprobante_asociado_id, motivo_anulacion) values (?, ?, 'SIN_FACTURAR', ?, ?, ?, 100, 21, "
            + "121, 121, " + asociado + ", " + motivo + ")", e.ferreteria(), tipo, numero, "v37-" + numero, e.usuario());
    }

    // ---- lectura del esquema ----

    /** Columna -> "tipo nulo default" de comprobante ("-" sin default). */
    private Map<String, String> columnas() {
        Map<String, String> columnas = new HashMap<>();
        jdbc.queryForList("select column_name as nombre, column_type as tipo, is_nullable as nulo, "
            + "coalesce(column_default, '-') as defecto from information_schema.columns where table_schema = database() "
            + "and table_name = 'comprobante'")
            .forEach(c -> columnas.put((String) c.get("nombre"), c.get("tipo") + " " + c.get("nulo") + " "
                + c.get("defecto")));
        return columnas;
    }

    /** Las columnas del indice de comprobante, en orden; vacio si no existe. */
    private List<String> indice(String nombre) {
        return jdbc.queryForList("select column_name from information_schema.statistics where table_schema = database() "
            + "and table_name = 'comprobante' and index_name = ? order by seq_in_index", String.class, nombre);
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
        filas.addAll(jdbc.queryForList("select concat_ws(' ', table_name, 'fk', constraint_name, referenced_table_name) "
            + "from information_schema.referential_constraints where constraint_schema = database()", String.class));
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
            new ClassPathResource("ventas/rollback-v37-nota-de-credito.sql").getInputStream(), StandardCharsets.UTF_8);
    }
}
