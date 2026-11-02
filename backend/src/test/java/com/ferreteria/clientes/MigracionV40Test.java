package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StreamUtils;
import org.testcontainers.containers.MySQLContainer;

/**
 * Migracion V40 (CU-12, cu-12-numeracion-ajustes T1: S2 y S3): los Recargos y los Descuentos se numeran cada uno por su lado,
 * como GDS (G95). El numerador de ajustes pasa a una fila por ferreteria y tipo, que arranca en el numero mas alto de ese tipo;
 * el unico de ajuste_cuenta pasa a (ferreteria, tipo, numero). Los ajustes existentes no cambian. Molde de MigracionV39Test:
 * un MySQL de Testcontainers propio y una base nueva por test, migrada hasta la V39.
 */
class MigracionV40Test {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    private static final AtomicInteger CONTADOR = new AtomicInteger();
    private static final AtomicInteger CLAVES = new AtomicInteger();
    private static final String ROLLBACK = "clientes/rollback-v40-numeracion-ajustes.sql";

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
    void baseNuevaEnLaVersion39() throws SQLException {
        String base = "v40_" + CONTADOR.incrementAndGet();
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement s = c.createStatement()) {
            s.execute("create database " + base + " character set utf8mb4");
        }
        url = MYSQL.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + base + "$1");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
        migrarHasta("39");
    }

    private void migrarHasta(String version) {
        Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations("classpath:db/migration")
            .target(version).load().migrate();
    }

    // ---------------------------------------------------------------- esquema

    @Test
    @DisplayName("esquema: el numerador gana tipo (varchar(10), no nulo) con su CHECK y es unico por (ferreteria, tipo); el "
        + "ajuste es unico por (ferreteria, tipo, numero)")
    void columnasIndicesYRestricciones() {
        migrarHasta("40");

        assertThat(columna("numerador_ajuste_cuenta", "tipo")).isEqualTo("varchar(10) NO");
        assertThat(indice("numerador_ajuste_cuenta", "uk_numerador_ajuste_cuenta_ferreteria_tipo"))
            .containsExactly("ferreteria_id", "tipo");
        assertThat(indice("numerador_ajuste_cuenta", "uk_numerador_ajuste_cuenta_ferreteria")).isEmpty();
        assertThat(restriccionesDe("numerador_ajuste_cuenta", "CHECK")).contains("ck_numerador_ajuste_cuenta_tipo");
        assertThat(indice("ajuste_cuenta", "uk_ajuste_cuenta_tipo_numero")).containsExactly("ferreteria_id", "tipo", "numero");
        assertThat(indice("ajuste_cuenta", "uk_ajuste_cuenta_numero")).isEmpty();
        assertThat(restriccionesDe("numerador_ajuste_cuenta", "FOREIGN KEY"))
            .contains("fk_numerador_ajuste_cuenta_ferreteria");
    }

    // ---------------------------------------------------------------- datos

    @Test
    @DisplayName("S2: cada tipo arranca en su numero mas alto de la ferreteria; los ajustes no cambian y una ferreteria sin "
        + "ajustes sigue sin numerador")
    void cadaTipoArrancaEnSuMaximo() {
        Escenario a = escenario("a");
        Escenario b = escenario("b");
        Escenario sinAjustes = escenario("c");
        ajusteConNumerador(a, "RECARGO", 1);
        ajusteConNumerador(a, "DESCUENTO", 2);
        ajusteConNumerador(a, "RECARGO", 3);
        ajusteConNumerador(b, "DESCUENTO", 1);
        List<Map<String, Object>> ajustesAntes = jdbc.queryForList("select * from ajuste_cuenta order by id");

        migrarHasta("40");

        assertThat(numeradores(a.ferreteria())).containsExactly("DESCUENTO 2", "RECARGO 3");
        assertThat(numeradores(b.ferreteria())).containsExactly("DESCUENTO 1");
        assertThat(numeradores(sinAjustes.ferreteria())).isEmpty();
        assertThat(jdbc.queryForList("select * from ajuste_cuenta order by id")).isEqualTo(ajustesAntes);
    }

    @Test
    @DisplayName("S1 en la base: un Descuento N.º 1 convive con el Recargo N.º 1; el mismo tipo y numero, o dos filas del "
        + "numerador para el mismo tipo, chocan; un tipo que no existe no entra")
    void unicosPorTipo() {
        migrarHasta("40");
        Escenario e = escenario("a");
        ajuste(e, "RECARGO", 1);

        assertThatCode(() -> ajuste(e, "DESCUENTO", 1)).doesNotThrowAnyException();
        assertThatThrownBy(() -> ajuste(e, "RECARGO", 1)).hasMessageContaining("uk_ajuste_cuenta_tipo_numero");
        numerador(e.ferreteria(), "RECARGO", 1);
        assertThatCode(() -> numerador(e.ferreteria(), "DESCUENTO", 1)).doesNotThrowAnyException();
        assertThatThrownBy(() -> numerador(e.ferreteria(), "RECARGO", 2))
            .hasMessageContaining("uk_numerador_ajuste_cuenta_ferreteria_tipo");
        assertThatThrownBy(() -> numerador(e.ferreteria(), "OTRO", 1)).hasMessageContaining("ck_numerador_ajuste_cuenta_tipo");
    }

    // ---------------------------------------------------------------- rollback

    @Test
    @DisplayName("S3: rollback-v40 sin numeros repetidos deja el esquema como en la V39, un numerador por ferreteria con el "
        + "numero mas alto, saca la 40 del historial y la V40 vuelve a entrar")
    void rollbackSinRepetidos() throws Exception {
        Escenario a = escenario("a");
        Escenario b = escenario("b");
        ajusteConNumerador(a, "RECARGO", 1);
        ajusteConNumerador(a, "DESCUENTO", 2);
        List<String> esquemaAntes = esquema();
        migrarHasta("40");
        ajuste(a, "RECARGO", 3);
        numeradorAl(a.ferreteria(), "RECARGO", 3);
        ajuste(b, "DESCUENTO", 1);
        numerador(b.ferreteria(), "DESCUENTO", 1);
        List<Map<String, Object>> ajustesAntes = jdbc.queryForList("select * from ajuste_cuenta order by id");

        correrRollback();

        assertThat(esquema()).containsExactlyInAnyOrderElementsOf(esquemaAntes);
        assertThat(jdbc.queryForList("select * from ajuste_cuenta order by id")).isEqualTo(ajustesAntes);
        assertThat(ultimoNumero(a.ferreteria())).containsExactly(3L);
        assertThat(ultimoNumero(b.ferreteria())).containsExactly(1L);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '40'", Integer.class))
            .isZero();
        migrarHasta("40");
        assertThat(numeradores(a.ferreteria())).containsExactly("DESCUENTO 2", "RECARGO 3");
    }

    @Test
    @DisplayName("S3: rollback-v40 con un Recargo y un Descuento del mismo numero se niega y no toca nada")
    void rollbackConRepetidosSeNiega() {
        migrarHasta("40");
        Escenario e = escenario("a");
        ajuste(e, "RECARGO", 1);
        numerador(e.ferreteria(), "RECARGO", 1);
        ajuste(e, "DESCUENTO", 1);
        numerador(e.ferreteria(), "DESCUENTO", 1);
        List<String> esquemaAntes = esquema();
        List<String> numeradoresAntes = numeradores(e.ferreteria());

        assertThatThrownBy(this::correrRollback).hasMessageContaining("uk_ajuste_cuenta_numero");

        assertThat(esquema()).containsExactlyInAnyOrderElementsOf(esquemaAntes);
        assertThat(numeradores(e.ferreteria())).isEqualTo(numeradoresAntes);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '40'", Integer.class))
            .isEqualTo(1);
    }

    @Test
    @DisplayName("la cadena: despues del rollback de la V40, los de la V39 y la V28 siguen andando (el de la V28 exige que no "
        + "haya ajustes)")
    void cadenaConLosRollbacksAnteriores() throws Exception {
        escenario("a");
        migrarHasta("40");

        correrRollback();
        correrScript("clientes/rollback-v39-nc-cuenta-corriente.sql");
        correrScript("clientes/rollback-v28-ajustes-cuenta-corriente.sql");

        assertThat(existeLaTabla("numerador_ajuste_cuenta")).isFalse();
        assertThat(existeLaTabla("ajuste_cuenta")).isFalse();
    }

    // ---------------------------------------------------------------- siembra

    /** Una ferreteria con su Dueño y un cliente. */
    private record Escenario(long ferreteria, long usuario, long cliente) {}

    private Escenario escenario(String sufijo) {
        String cuit = "30-78601400-" + sufijo;
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
            "Ferreteria V40 " + sufijo, cuit);
        long f = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) values (?, ?, 'x', 'Dueno V40', "
            + "'DUENO')", f, "v40-" + f + "@test.local");
        long usuario = jdbc.queryForObject("select min(id) from usuario where ferreteria_id = ?", Long.class, f);
        jdbc.update("insert into cliente (ferreteria_id, razon_social) values (?, ?)", f, "Cliente V40 " + sufijo);
        long cliente = jdbc.queryForObject("select id from cliente where ferreteria_id = ?", Long.class, f);
        return new Escenario(f, usuario, cliente);
    }

    private void ajuste(Escenario e, String tipo, long numero) {
        jdbc.update("insert into ajuste_cuenta (ferreteria_id, cliente_id, numero, tipo, importe, motivo, clave, fecha, usuario_id) "
            + "values (?, ?, ?, ?, 50, 'Deuda vieja', ?, now(6), ?)", e.ferreteria(), e.cliente(), numero, tipo,
            "aj-" + CLAVES.incrementAndGet(), e.usuario());
    }

    /** Antes de la V40: el ajuste y el numerador unico de la ferreteria, que avanza con cada ajuste (como el UPSERT). */
    private void ajusteConNumerador(Escenario e, String tipo, long numero) {
        ajuste(e, tipo, numero);
        jdbc.update("insert into numerador_ajuste_cuenta (ferreteria_id, ultimo_numero) values (?, 1) "
            + "on duplicate key update ultimo_numero = ultimo_numero + 1", e.ferreteria());
    }

    private void numerador(long ferreteria, String tipo, long ultimo) {
        jdbc.update("insert into numerador_ajuste_cuenta (ferreteria_id, tipo, ultimo_numero) values (?, ?, ?)", ferreteria,
            tipo, ultimo);
    }

    private void numeradorAl(long ferreteria, String tipo, long ultimo) {
        jdbc.update("update numerador_ajuste_cuenta set ultimo_numero = ? where ferreteria_id = ? and tipo = ?", ultimo,
            ferreteria, tipo);
    }

    /** Despues de la V40: «TIPO ultimo», ordenado por tipo. */
    private List<String> numeradores(long ferreteria) {
        return jdbc.queryForList("select concat(tipo, ' ', ultimo_numero) from numerador_ajuste_cuenta where ferreteria_id = ? "
            + "order by tipo", String.class, ferreteria);
    }

    /** Antes de la V40 (o despues del rollback): el ultimo numero de la fila unica de la ferreteria. */
    private List<Long> ultimoNumero(long ferreteria) {
        return jdbc.queryForList("select ultimo_numero from numerador_ajuste_cuenta where ferreteria_id = ?", Long.class,
            ferreteria);
    }

    // ---------------------------------------------------------------- lectura del esquema

    private List<String> restriccionesDe(String tabla, String tipo) {
        return jdbc.queryForList("select constraint_name from information_schema.table_constraints "
            + "where table_schema = database() and table_name = ? and constraint_type = ? order by 1", String.class,
            tabla, tipo);
    }

    private String columna(String tabla, String columna) {
        Map<String, Object> c = jdbc.queryForMap("select column_type, is_nullable from information_schema.columns "
            + "where table_schema = database() and table_name = ? and column_name = ?", tabla, columna);
        return c.get("COLUMN_TYPE") + " " + c.get("IS_NULLABLE");
    }

    private boolean existeLaTabla(String tabla) {
        return jdbc.queryForObject("select count(*) from information_schema.tables where table_schema = database() "
            + "and table_name = ?", Integer.class, tabla) > 0;
    }

    /** Las columnas del indice, en orden; vacio si no existe. */
    private List<String> indice(String tabla, String nombre) {
        return jdbc.queryForList("select column_name from information_schema.statistics where table_schema = database() "
            + "and table_name = ? and index_name = ? order by seq_in_index", String.class, tabla, nombre);
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
        correrScript(ROLLBACK);
    }

    private void correrScript(String script) throws Exception {
        Properties propiedades = new Properties();
        propiedades.setProperty("user", "root");
        propiedades.setProperty("password", MYSQL.getPassword());
        propiedades.setProperty("allowMultiQueries", "true");
        String sql = StreamUtils.copyToString(new ClassPathResource(script).getInputStream(), StandardCharsets.UTF_8);
        try (Connection c = DriverManager.getConnection(url, propiedades); Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }
}
