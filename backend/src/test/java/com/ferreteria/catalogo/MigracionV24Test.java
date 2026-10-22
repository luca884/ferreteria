package com.ferreteria.catalogo;

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
 * Migracion V24 (CU-14): articulo.precio_manual BOOLEAN NOT NULL DEFAULT FALSE y articulo.ajustes_precio JSON NOT NULL
 * DEFAULT []. Aditiva. Usa un MySQL de Testcontainers propio de la clase y una base nueva por test: migra hasta la V23,
 * siembra por JDBC y recien despues migra a la V24. CUIT: bloque 30-7840124x.
 */
class MigracionV24Test {

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
    void baseNuevaEnLaVersion23() throws SQLException {
        String base = "v24_" + CONTADOR.incrementAndGet();
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
             Statement s = c.createStatement()) {
            s.execute("create database " + base + " character set utf8mb4");
        }
        url = MYSQL.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + base + "$1");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
        flyway("classpath:db/migration").target("23").load().migrate();
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration flyway(String... ubicaciones) {
        return Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations(ubicaciones);
    }

    private void migrarALa24() {
        flyway("classpath:db/migration").target("24").load().migrate();
    }

    @Test
    @DisplayName("articulo suma precio_manual (NOT NULL, default false) y ajustes_precio (JSON NOT NULL)")
    void columnasNuevas() {
        migrarALa24();

        Map<String, Object> manual = columna("precio_manual");
        assertThat(manual.get("tipo")).isEqualTo("tinyint(1)");
        assertThat(manual.get("nulo")).isEqualTo("NO");
        assertThat(manual.get("defecto")).isEqualTo("0");
        Map<String, Object> ajustes = columna("ajustes_precio");
        assertThat(ajustes.get("tipo")).isEqualTo("json");
        assertThat(ajustes.get("nulo")).isEqualTo("NO");
    }

    @Test
    @DisplayName("un articulo anterior a V24 queda sin precio manual, sin ajustes y con todo lo demas igual")
    void losExistentesQuedanIgual() {
        Long f = ferreteria("30-78401241-1");
        Long articulo = articulo(f, "V24-1", "900.00", "1420.00", 10);
        jdbc.update("update articulo set ajuste1 = -5.00, margen_porcentaje = 40.00 where id = ?", articulo);
        Map<String, Object> antes = jdbc.queryForMap("select * from articulo where id = ?", articulo);

        migrarALa24();

        Map<String, Object> despues = new java.util.HashMap<>(jdbc.queryForMap("select * from articulo where id = ?", articulo));
        assertThat(despues.remove("precio_manual")).isEqualTo(false);
        assertThat(despues.remove("ajustes_precio")).isEqualTo("[]");
        assertThat(despues).isEqualTo(antes);
    }

    @Test
    @DisplayName("un insert sin las columnas nuevas toma los defaults, y NULL se rechaza")
    void defaultsYNotNull() {
        migrarALa24();
        Long f = ferreteria("30-78401241-2");

        Long articulo = articulo(f, "V24-2", "100.00", "170.00", 0);

        assertThat(jdbc.queryForObject("select precio_manual from articulo where id = ?", Boolean.class, articulo))
            .isFalse();
        assertThat(jdbc.queryForObject("select json_length(ajustes_precio) from articulo where id = ?", Integer.class,
            articulo)).isZero();
        assertThatThrownBy(() -> jdbc.update("update articulo set ajustes_precio = null where id = ?", articulo))
            .hasMessageContaining("ajustes_precio");
        assertThatThrownBy(() -> jdbc.update("update articulo set precio_manual = null where id = ?", articulo))
            .hasMessageContaining("precio_manual");
    }

    @Test
    @DisplayName("aditiva: no quita ninguna columna ni ninguna migracion anterior")
    void noQuitaNada() {
        List<String> columnasAntes = columnasDeArticulo();

        migrarALa24();

        List<String> columnasDespues = columnasDeArticulo();
        assertThat(columnasDespues).containsAll(columnasAntes);
        assertThat(columnasDespues).hasSize(columnasAntes.size() + 2).contains("precio_manual", "ajustes_precio");
        assertThat(jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success = 1 and version is not null", Integer.class))
            .isEqualTo(24);
    }

    @Test
    @DisplayName("datos demo: en una base dev existente V24 entra por out-of-order y los precios demo no cambian")
    void losDatosDemoNoCambian() {
        flyway("classpath:db/dev-data").ignoreMigrationPatterns("*:missing").load().migrate();
        String consulta = "select id, costo, precio_final, stock_actual from articulo order by id";
        List<Map<String, Object>> antes = jdbc.queryForList(consulta);
        assertThat(antes).isNotEmpty();

        flyway("classpath:db/migration", "classpath:db/dev-data").outOfOrder(true).load().migrate();

        assertThat(jdbc.queryForList(consulta)).isEqualTo(antes);
        assertThat(jdbc.queryForObject("select count(*) from articulo where precio_manual", Integer.class)).isZero();
    }

    @Test
    @DisplayName("rollback-v24-precio-manual.sql saca las columnas y la V24 del historial, y deja lo demas")
    void rollback() throws Exception {
        Long f = ferreteria("30-78401241-3");
        Long articulo = articulo(f, "V24-RB", "900.00", "1420.00", 4);
        migrarALa24();
        jdbc.update("update articulo set precio_manual = true, "
            + "ajustes_precio = json_array(json_object('nombre', 'Flete', 'tipo', 'MONTO', 'valor', 10)) where id = ?",
            articulo);

        Properties propiedades = new Properties();
        propiedades.setProperty("user", "root");
        propiedades.setProperty("password", MYSQL.getPassword());
        propiedades.setProperty("allowMultiQueries", "true");
        try (Connection c = DriverManager.getConnection(url, propiedades); Statement s = c.createStatement()) {
            s.execute(scriptDeRollback());
        }

        assertThat(columnasDeArticulo()).doesNotContain("precio_manual", "ajustes_precio");
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '24'", Integer.class))
            .isZero();
        Map<String, Object> fila = jdbc.queryForMap("select costo, precio_final, stock_actual from articulo where id = ?",
            articulo);
        assertThat((BigDecimal) fila.get("precio_final")).isEqualByComparingTo("1420");
        assertThat((BigDecimal) fila.get("stock_actual")).isEqualByComparingTo("4");
        // Y V24 se puede volver a aplicar.
        migrarALa24();
        assertThat(jdbc.queryForObject("select precio_manual from articulo where id = ?", Boolean.class, articulo))
            .isFalse();
    }

    private Map<String, Object> columna(String nombre) {
        return jdbc.queryForMap("select column_type as tipo, is_nullable as nulo, column_default as defecto "
            + "from information_schema.columns where table_schema = database() and table_name = 'articulo' "
            + "and column_name = ?", nombre);
    }

    private static String scriptDeRollback() throws IOException {
        return StreamUtils.copyToString(
            new ClassPathResource("catalogo/rollback-v24-precio-manual.sql").getInputStream(), StandardCharsets.UTF_8);
    }

    private List<String> columnasDeArticulo() {
        return jdbc.queryForList("select column_name from information_schema.columns "
            + "where table_schema = database() and table_name = 'articulo'", String.class);
    }

    private Long ferreteria(String cuit) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
            "Ferreteria " + cuit, cuit);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
    }

    private Long articulo(Long ferreteriaId, String codigo, String costo, String precioFinal, int stock) {
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, "
            + "stock_actual) values (?, ?, 'Articulo de prueba', ?, ?, ?)", ferreteriaId, codigo, new BigDecimal(costo),
            new BigDecimal(precioFinal), stock);
        return jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?", Long.class,
            ferreteriaId, codigo);
    }
}
