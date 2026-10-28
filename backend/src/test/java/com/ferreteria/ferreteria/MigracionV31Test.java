package com.ferreteria.ferreteria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StreamUtils;
import org.testcontainers.containers.MySQLContainer;

/**
 * Migracion V31 (datos fiscales de la ferreteria, CU-01 paso 1): ferreteria suma domicilio_comercial, inicio_actividades e
 * ingresos_brutos, los tres nullable, con CHECK de texto no vacio. Aditiva: las ferreterias existentes quedan en NULL. Usa
 * un MySQL de Testcontainers propio de la clase y una base nueva por test: migra hasta la V30, siembra por JDBC y recien
 * despues migra a la V31. CUIT: bloque 30-7860131x.
 */
class MigracionV31Test {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    private static final AtomicInteger CONTADOR = new AtomicInteger();
    private static final List<String> COLUMNAS_NUEVAS = List.of("domicilio_comercial", "inicio_actividades", "ingresos_brutos");

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
    void baseNuevaEnLaVersion30() throws SQLException {
        String base = "v31_" + CONTADOR.incrementAndGet();
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement s = c.createStatement()) {
            s.execute("create database " + base + " character set utf8mb4");
        }
        url = MYSQL.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + base + "$1");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
        flyway("classpath:db/migration").target("30").load().migrate();
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration flyway(String... ubicaciones) {
        return Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations(ubicaciones);
    }

    private void migrarALa31() {
        flyway("classpath:db/migration").target("31").load().migrate();
    }

    @Test
    @DisplayName("ferreteria suma domicilio_comercial VARCHAR(200), inicio_actividades DATE e ingresos_brutos VARCHAR(50), nullable")
    void columnasNuevas() {
        migrarALa31();

        Map<String, String> tipos = new HashMap<>();
        Map<String, String> nulos = new HashMap<>();
        jdbc.queryForList("select column_name as nombre, column_type as tipo, is_nullable as nulo "
            + "from information_schema.columns where table_schema = database() and table_name = 'ferreteria'")
            .forEach(c -> {
                tipos.put((String) c.get("nombre"), (String) c.get("tipo"));
                nulos.put((String) c.get("nombre"), (String) c.get("nulo"));
            });
        assertThat(tipos).containsEntry("domicilio_comercial", "varchar(200)")
            .containsEntry("inicio_actividades", "date")
            .containsEntry("ingresos_brutos", "varchar(50)");
        assertThat(COLUMNAS_NUEVAS).allSatisfy(columna -> assertThat(nulos.get(columna)).isEqualTo("YES"));
    }

    @Test
    @DisplayName("una ferreteria de antes de V31 queda con los datos nuevos vacios y con todo lo demas igual")
    void lasFerreteriasExistentesQuedanIgual() {
        long f = ferreteria("30-78601311-1");
        Map<String, Object> antes = jdbc.queryForMap("select * from ferreteria where id = ?", f);

        migrarALa31();

        Map<String, Object> despues = new HashMap<>(jdbc.queryForMap("select * from ferreteria where id = ?", f));
        COLUMNAS_NUEVAS.forEach(columna -> assertThat(despues.remove(columna)).isNull());
        assertThat(despues).isEqualTo(antes);
    }

    @Test
    @DisplayName("guarda los tres datos, y un domicilio o unos Ingresos Brutos en blanco no entran (se guardan como NULL)")
    void guardaLosDatosYNoAceptaTextosEnBlanco() {
        long f = ferreteria("30-78601311-2");
        migrarALa31();

        jdbc.update("update ferreteria set domicilio_comercial = 'Av. Independencia 1234, Mar del Plata', "
            + "inicio_actividades = '2015-03-01', ingresos_brutos = '901-123456-7' where id = ?", f);
        Map<String, Object> fila = jdbc.queryForMap(
            "select domicilio_comercial, inicio_actividades, ingresos_brutos from ferreteria where id = ?", f);
        assertThat(fila.get("domicilio_comercial")).isEqualTo("Av. Independencia 1234, Mar del Plata");
        assertThat(fila.get("inicio_actividades")).isEqualTo(Date.valueOf("2015-03-01"));
        assertThat(fila.get("ingresos_brutos")).isEqualTo("901-123456-7");

        assertThatThrownBy(() -> jdbc.update("update ferreteria set domicilio_comercial = '   ' where id = ?", f))
            .hasMessageContaining("ck_ferreteria_domicilio_comercial");
        assertThatThrownBy(() -> jdbc.update("update ferreteria set ingresos_brutos = '' where id = ?", f))
            .hasMessageContaining("ck_ferreteria_ingresos_brutos");
    }

    @Test
    @DisplayName("aditiva: no quita ninguna columna ni ninguna migracion anterior")
    void noQuitaNada() {
        List<String> columnasAntes = columnasDeFerreteria();

        migrarALa31();

        List<String> columnasDespues = columnasDeFerreteria();
        assertThat(columnasDespues).containsAll(columnasAntes);
        assertThat(columnasDespues).hasSize(columnasAntes.size() + 3).containsAll(COLUMNAS_NUEVAS);
        assertThat(jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success = 1 and version is not null", Integer.class))
            .isEqualTo(31);
    }

    @Test
    @DisplayName("datos demo: en una base dev existente V31 entra por out-of-order y las ferreterias quedan sin datos fiscales")
    void losDatosDemoSiguenIgual() {
        flyway("classpath:db/dev-data").ignoreMigrationPatterns("*:missing").load().migrate();
        String consulta = "select id, razon_social, cuit, condicion_iva from ferreteria order by id";
        List<Map<String, Object>> antes = jdbc.queryForList(consulta);
        assertThat(antes).isNotEmpty();

        flyway("classpath:db/migration", "classpath:db/dev-data").outOfOrder(true).load().migrate();

        assertThat(jdbc.queryForList(consulta)).isEqualTo(antes);
        assertThat(jdbc.queryForObject("select count(*) from ferreteria where domicilio_comercial is not null "
            + "or inicio_actividades is not null or ingresos_brutos is not null", Integer.class)).isZero();
    }

    @Test
    @DisplayName("rollback-v31-datos-fiscales.sql, sin datos cargados, saca las columnas, los CHECK y la V31, y deja lo demas")
    void rollbackSinDatos() throws Exception {
        long f = ferreteria("30-78601311-3");
        List<String> columnasAntes = columnasDeFerreteria();
        migrarALa31();

        correrRollback();

        assertThat(columnasDeFerreteria()).containsExactlyInAnyOrderElementsOf(columnasAntes);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.check_constraints "
            + "where constraint_schema = database() and constraint_name in "
            + "('ck_ferreteria_domicilio_comercial', 'ck_ferreteria_ingresos_brutos', 'ck_rollback_sin_datos_fiscales')",
            Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '31'", Integer.class))
            .isZero();
        assertThat(jdbc.queryForObject("select razon_social from ferreteria where id = ?", String.class, f))
            .isEqualTo("Ferreteria 30-78601311-3");
        // Y V31 se puede volver a aplicar.
        migrarALa31();
        assertThat(columnasDeFerreteria()).containsAll(COLUMNAS_NUEVAS);
    }

    @ParameterizedTest(name = "con solo {0} cargado")
    @CsvSource({"inicio_actividades, 2015-03-01", "ingresos_brutos, Exento", "domicilio_comercial, Av. Luro 3200"})
    @DisplayName("rollback-v31-datos-fiscales.sql se niega si alguna ferreteria ya cargo un dato fiscal, y no toca nada")
    void rollbackConDatosSeNiega(String columna, String valor) {
        long f = ferreteria("30-78601311-4");
        migrarALa31();
        jdbc.update("update ferreteria set " + columna + " = ? where id = ?", valor, f);
        List<String> columnasAntes = columnasDeFerreteria();

        assertThatThrownBy(this::correrRollback).hasMessageContaining("ck_rollback_sin_datos_fiscales");

        assertThat(columnasDeFerreteria()).containsExactlyInAnyOrderElementsOf(columnasAntes);
        assertThat(jdbc.queryForObject("select " + columna + " from ferreteria where id = ?", String.class, f))
            .isEqualTo(valor);
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '31'", Integer.class))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.check_constraints "
            + "where constraint_schema = database() and constraint_name = 'ck_rollback_sin_datos_fiscales'",
            Integer.class)).isZero();
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

    private static String scriptDeRollback() throws IOException {
        return StreamUtils.copyToString(
            new ClassPathResource("ferreteria/rollback-v31-datos-fiscales.sql").getInputStream(), StandardCharsets.UTF_8);
    }

    private List<String> columnasDeFerreteria() {
        return jdbc.queryForList("select column_name from information_schema.columns "
            + "where table_schema = database() and table_name = 'ferreteria'", String.class);
    }

    private long ferreteria(String cuit) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
            "Ferreteria " + cuit, cuit);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
    }
}
