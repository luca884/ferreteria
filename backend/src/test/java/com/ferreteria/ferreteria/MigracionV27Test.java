package com.ferreteria.ferreteria;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.StreamUtils;
import org.testcontainers.containers.MySQLContainer;

import com.ferreteria.support.PlantillaDeMigracion;

/**
 * Migracion V27 (tope de Consumidor Final, GDS-32): ferreteria suma tope_consumidor_final DECIMAL(15,2) NOT NULL con
 * default 10.000.000,00 (RG 5700/2025) y un CHECK de mayor que cero. Aditiva: las ferreterias existentes quedan con el
 * default. Usa un MySQL de Testcontainers propio de la clase y una base nueva por test: migra hasta la V26, siembra por
 * JDBC y recien despues migra a la V27. CUIT: bloque 30-7860127x.
 */
class MigracionV27Test {

    private static final MySQLContainer<?> MYSQL = PlantillaDeMigracion.MYSQL;

    private String url;
    private JdbcTemplate jdbc;

    @BeforeEach
    void baseNuevaEnLaVersion26() {
        url = PlantillaDeMigracion.url(PlantillaDeMigracion.baseEnLaVersion("v27", "26"));
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration flyway(String... ubicaciones) {
        return Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations(ubicaciones);
    }

    private void migrarALa27() {
        flyway("classpath:db/migration").target("27").load().migrate();
    }

    @Test
    @DisplayName("ferreteria suma tope_consumidor_final DECIMAL(15,2), no nulo y con default 10.000.000,00")
    void columnaNueva() {
        migrarALa27();

        Map<String, Object> tope = jdbc.queryForMap("select column_type as tipo, is_nullable as nulo, "
            + "column_default as defecto from information_schema.columns where table_schema = database() "
            + "and table_name = 'ferreteria' and column_name = 'tope_consumidor_final'");
        assertThat(tope.get("tipo")).isEqualTo("decimal(15,2)");
        assertThat(tope.get("nulo")).isEqualTo("NO");
        assertThat(new BigDecimal(tope.get("defecto").toString())).isEqualByComparingTo("10000000.00");
    }

    @Test
    @DisplayName("una ferreteria de antes de V27 queda con el tope por defecto y con todo lo demas igual")
    void lasFerreteriasExistentesQuedanConElDefault() {
        long f = ferreteria("30-78601271-1");
        Map<String, Object> antes = jdbc.queryForMap("select * from ferreteria where id = ?", f);

        migrarALa27();

        Map<String, Object> despues = new HashMap<>(jdbc.queryForMap("select * from ferreteria where id = ?", f));
        assertThat((BigDecimal) despues.remove("tope_consumidor_final")).isEqualByComparingTo("10000000.00");
        assertThat(despues).isEqualTo(antes);
    }

    @Test
    @DisplayName("el tope tiene que ser mayor que cero")
    void topeMayorQueCero() {
        long f = ferreteria("30-78601271-2");
        migrarALa27();
        String actualizar = "update ferreteria set tope_consumidor_final = ? where id = ?";

        jdbc.update(actualizar, new BigDecimal("0.01"), f);
        assertThatThrownBy(() -> jdbc.update(actualizar, BigDecimal.ZERO, f))
            .hasMessageContaining("ck_ferreteria_tope_consumidor_final");
        assertThatThrownBy(() -> jdbc.update(actualizar, new BigDecimal("-1"), f))
            .hasMessageContaining("ck_ferreteria_tope_consumidor_final");
    }

    @Test
    @DisplayName("aditiva: no quita ninguna columna ni ninguna migracion anterior")
    void noQuitaNada() {
        List<String> columnasAntes = columnasDeFerreteria();

        migrarALa27();

        List<String> columnasDespues = columnasDeFerreteria();
        assertThat(columnasDespues).containsAll(columnasAntes);
        assertThat(columnasDespues).hasSize(columnasAntes.size() + 1).contains("tope_consumidor_final");
        assertThat(jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success = 1 and version is not null", Integer.class))
            .isEqualTo(27);
    }

    @Test
    @DisplayName("datos demo: en una base dev existente V27 entra por out-of-order y las ferreterias toman el default")
    void losDatosDemoSiguenIgual() {
        flyway("classpath:db/dev-data").ignoreMigrationPatterns("*:missing").load().migrate();
        String consulta = "select id, razon_social, cuit from ferreteria order by id";
        List<Map<String, Object>> antes = jdbc.queryForList(consulta);
        assertThat(antes).isNotEmpty();

        flyway("classpath:db/migration", "classpath:db/dev-data").outOfOrder(true).load().migrate();

        assertThat(jdbc.queryForList(consulta)).isEqualTo(antes);
        assertThat(jdbc.queryForObject(
            "select count(*) from ferreteria where tope_consumidor_final <> 10000000.00", Integer.class)).isZero();
    }

    @Test
    @DisplayName("rollback-v27-tope-consumidor-final.sql saca la columna, el CHECK y la V27 del historial, y deja lo demas")
    void rollback() throws Exception {
        long f = ferreteria("30-78601271-3");
        List<String> columnasAntes = columnasDeFerreteria();
        migrarALa27();
        jdbc.update("update ferreteria set tope_consumidor_final = 5000 where id = ?", f);

        Properties propiedades = new Properties();
        propiedades.setProperty("user", "root");
        propiedades.setProperty("password", MYSQL.getPassword());
        propiedades.setProperty("allowMultiQueries", "true");
        try (Connection c = DriverManager.getConnection(url, propiedades); Statement s = c.createStatement()) {
            s.execute(scriptDeRollback());
        }

        assertThat(columnasDeFerreteria()).containsExactlyInAnyOrderElementsOf(columnasAntes);
        assertThat(jdbc.queryForObject("select count(*) from information_schema.check_constraints "
            + "where constraint_schema = database() and constraint_name = 'ck_ferreteria_tope_consumidor_final'",
            Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '27'", Integer.class))
            .isZero();
        assertThat(jdbc.queryForObject("select razon_social from ferreteria where id = ?", String.class, f))
            .isEqualTo("Ferreteria 30-78601271-3");
        // Y V27 se puede volver a aplicar: vuelve el default, no el tope que se habia cargado.
        migrarALa27();
        assertThat(jdbc.queryForObject("select tope_consumidor_final from ferreteria where id = ?", BigDecimal.class, f))
            .isEqualByComparingTo("10000000.00");
    }

    private static String scriptDeRollback() throws IOException {
        return StreamUtils.copyToString(
            new ClassPathResource("ferreteria/rollback-v27-tope-consumidor-final.sql").getInputStream(),
            StandardCharsets.UTF_8);
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
