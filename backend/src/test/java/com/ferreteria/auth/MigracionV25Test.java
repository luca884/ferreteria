package com.ferreteria.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
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
 * Migracion V25 (CU-07): usuario.tope_descuento DECIMAL(5,2) NULL. Aditiva: los usuarios existentes quedan sin tope.
 * Usa un MySQL de Testcontainers propio de la clase y una base nueva por test: migra hasta la V24, siembra por JDBC y
 * recien despues migra a la V25. CUIT: bloque 30-7850125x.
 */
class MigracionV25Test {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");
    private static final AtomicInteger CONTADOR = new AtomicInteger();

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
    void baseNuevaEnLaVersion24() throws SQLException {
        String base = "v25_" + CONTADOR.incrementAndGet();
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement s = c.createStatement()) {
            s.execute("create database " + base + " character set utf8mb4");
        }
        url = MYSQL.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + base + "$1");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
        flyway("classpath:db/migration").target("24").load().migrate();
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration flyway(String... ubicaciones) {
        return Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations(ubicaciones);
    }

    private void migrarALa25() {
        flyway("classpath:db/migration").target("25").load().migrate();
    }

    @Test
    @DisplayName("usuario suma tope_descuento DECIMAL(5,2) que acepta NULL y no tiene default")
    void columnaNueva() {
        migrarALa25();

        Map<String, Object> tope = jdbc.queryForMap("select column_type as tipo, is_nullable as nulo, "
            + "column_default as defecto from information_schema.columns where table_schema = database() "
            + "and table_name = 'usuario' and column_name = 'tope_descuento'");
        assertThat(tope.get("tipo")).isEqualTo("decimal(5,2)");
        assertThat(tope.get("nulo")).isEqualTo("YES");
        assertThat(tope.get("defecto")).isNull();
    }

    @Test
    @DisplayName("un usuario anterior a V25 queda sin tope y con todo lo demas igual")
    void losExistentesQuedanSinTope() {
        Long f = ferreteria("30-78501251-1");
        Long vendedor = usuario(f, "v25-1@prueba.local", "VENDEDOR");
        Map<String, Object> antes = jdbc.queryForMap("select * from usuario where id = ?", vendedor);

        migrarALa25();

        Map<String, Object> despues = new java.util.HashMap<>(jdbc.queryForMap("select * from usuario where id = ?",
            vendedor));
        assertThat(despues.containsKey("tope_descuento")).isTrue();
        assertThat(despues.remove("tope_descuento")).isNull();
        assertThat(despues).isEqualTo(antes);
    }

    @Test
    @DisplayName("el tope va de 0 a 99,99 y un Dueño no puede tenerlo")
    void rangoYDueno() {
        migrarALa25();
        Long f = ferreteria("30-78501251-2");
        Long vendedor = usuario(f, "v25-2@prueba.local", "VENDEDOR");
        Long dueno = usuario(f, "v25-3@prueba.local", "DUENO");

        jdbc.update("update usuario set tope_descuento = 0 where id = ?", vendedor);
        jdbc.update("update usuario set tope_descuento = 99.99 where id = ?", vendedor);
        assertThat(jdbc.queryForObject("select tope_descuento from usuario where id = ?", BigDecimal.class, vendedor))
            .isEqualByComparingTo("99.99");
        assertThatThrownBy(() -> jdbc.update("update usuario set tope_descuento = 100 where id = ?", vendedor))
            .hasMessageContaining("ck_usuario_tope_descuento");
        assertThatThrownBy(() -> jdbc.update("update usuario set tope_descuento = -1 where id = ?", vendedor))
            .hasMessageContaining("ck_usuario_tope_descuento");
        assertThatThrownBy(() -> jdbc.update("update usuario set tope_descuento = 10 where id = ?", dueno))
            .hasMessageContaining("ck_usuario_tope_descuento");
        jdbc.update("update usuario set tope_descuento = 10 where id = ?", vendedor);
        assertThatThrownBy(() -> jdbc.update("update usuario set rol = 'DUENO' where id = ?", vendedor))
            .hasMessageContaining("ck_usuario_tope_descuento");
    }

    @Test
    @DisplayName("aditiva: no quita ninguna columna ni ninguna migracion anterior")
    void noQuitaNada() {
        List<String> columnasAntes = columnasDeUsuario();

        migrarALa25();

        List<String> columnasDespues = columnasDeUsuario();
        assertThat(columnasDespues).containsAll(columnasAntes);
        assertThat(columnasDespues).hasSize(columnasAntes.size() + 1).contains("tope_descuento");
        assertThat(jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success = 1 and version is not null", Integer.class))
            .isEqualTo(25);
    }

    @Test
    @DisplayName("datos demo: en una base dev existente V25 entra por out-of-order y los usuarios demo quedan sin tope")
    void losDatosDemoQuedanSinTope() {
        flyway("classpath:db/dev-data").ignoreMigrationPatterns("*:missing").load().migrate();
        String consulta = "select id, email, rol, activo from usuario order by id";
        List<Map<String, Object>> antes = jdbc.queryForList(consulta);
        assertThat(antes).isNotEmpty();

        flyway("classpath:db/migration", "classpath:db/dev-data").outOfOrder(true).load().migrate();

        assertThat(jdbc.queryForList(consulta)).isEqualTo(antes);
        assertThat(jdbc.queryForObject("select count(*) from usuario where tope_descuento is not null", Integer.class))
            .isZero();
    }

    @Test
    @DisplayName("rollback-v25-tope-descuento.sql saca la columna y la V25 del historial, y deja lo demas")
    void rollback() throws Exception {
        Long f = ferreteria("30-78501251-3");
        Long vendedor = usuario(f, "v25-4@prueba.local", "VENDEDOR");
        migrarALa25();
        jdbc.update("update usuario set tope_descuento = 15 where id = ?", vendedor);

        Properties propiedades = new Properties();
        propiedades.setProperty("user", "root");
        propiedades.setProperty("password", MYSQL.getPassword());
        propiedades.setProperty("allowMultiQueries", "true");
        try (Connection c = DriverManager.getConnection(url, propiedades); Statement s = c.createStatement()) {
            s.execute(scriptDeRollback());
        }

        assertThat(columnasDeUsuario()).doesNotContain("tope_descuento");
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '25'", Integer.class))
            .isZero();
        assertThat(jdbc.queryForObject("select email from usuario where id = ?", String.class, vendedor))
            .isEqualTo("v25-4@prueba.local");
        // Y V25 se puede volver a aplicar.
        migrarALa25();
        assertThat(jdbc.queryForObject("select tope_descuento from usuario where id = ?", BigDecimal.class, vendedor))
            .isNull();
    }

    private static String scriptDeRollback() throws IOException {
        return StreamUtils.copyToString(
            new ClassPathResource("auth/rollback-v25-tope-descuento.sql").getInputStream(), StandardCharsets.UTF_8);
    }

    private List<String> columnasDeUsuario() {
        return jdbc.queryForList("select column_name from information_schema.columns "
            + "where table_schema = database() and table_name = 'usuario'", String.class);
    }

    private Long ferreteria(String cuit) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
            "Ferreteria " + cuit, cuit);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
    }

    private Long usuario(Long ferreteriaId, String email, String rol) {
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) values (?, ?, 'x', ?, ?)",
            ferreteriaId, email, "Usuario " + email, rol);
        return jdbc.queryForObject("select id from usuario where email = ?", Long.class, email);
    }
}
