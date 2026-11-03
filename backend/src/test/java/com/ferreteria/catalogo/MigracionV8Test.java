package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
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
 * Migracion V8 (corte 3, lote 5b): articulo.version BIGINT NOT NULL DEFAULT 0 para el bloqueo optimista. Aditiva.
 * Usa un MySQL de Testcontainers propio de la clase y una base nueva por test: migra hasta la V7, siembra por JDBC y
 * recien despues migra a la V8. CUIT: bloque 30-7840100x.
 */
class MigracionV8Test {

    private static final MySQLContainer<?> MYSQL = PlantillaDeMigracion.MYSQL;

    private String url;
    private JdbcTemplate jdbc;

    @BeforeEach
    void baseNuevaEnLaVersion7() {
        url = PlantillaDeMigracion.url(PlantillaDeMigracion.baseEnLaVersion("v8", "7"));
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration flyway(String... ubicaciones) {
        return Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations(ubicaciones);
    }

    private void migrarALa8() {
        flyway("classpath:db/migration").target("8").load().migrate();
    }

    @Test
    @DisplayName("articulo suma version BIGINT NOT NULL con default 0")
    void columnaNueva() {
        migrarALa8();

        List<Map<String, Object>> filas = jdbc.queryForList(
            "select column_type as tipo, is_nullable as nulo, column_default as defecto from information_schema.columns "
                + "where table_schema = database() and table_name = 'articulo' and column_name = 'version'");
        assertThat(filas).hasSize(1);
        assertThat(filas.get(0).get("tipo")).isEqualTo("bigint");
        assertThat(filas.get(0).get("nulo")).isEqualTo("NO");
        assertThat(filas.get(0).get("defecto")).isEqualTo("0");
    }

    @Test
    @DisplayName("un articulo anterior a V8 queda con version 0 y todo lo demas igual")
    void losExistentesQuedanEnCero() {
        Long f = ferreteria("30-78401001-1");
        Long articulo = articulo(f, "V8-1", "900.00", "1420.00", 10);
        jdbc.update("update articulo set ajuste1 = -5.00, bonificacion1 = 10.00, lista_incluye_iva = true, "
            + "precio_lista_proveedor = 1000.00 where id = ?", articulo);
        Map<String, Object> antes = jdbc.queryForMap("select * from articulo where id = ?", articulo);

        migrarALa8();

        Map<String, Object> despues = new java.util.HashMap<>(jdbc.queryForMap("select * from articulo where id = ?", articulo));
        assertThat(despues.remove("version")).isEqualTo(0L);
        assertThat(despues).isEqualTo(antes);
        assertThat((BigDecimal) despues.get("costo")).isEqualByComparingTo("900.00");
        assertThat((BigDecimal) despues.get("precio_final")).isEqualByComparingTo("1420");
        assertThat((BigDecimal) despues.get("stock_actual")).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("un insert sin version toma 0 y version NULL se rechaza")
    void defaultYNotNull() {
        migrarALa8();
        Long f = ferreteria("30-78401001-2");

        Long articulo = articulo(f, "V8-2", "100.00", "170.00", 0);

        assertThat(jdbc.queryForObject("select version from articulo where id = ?", Long.class, articulo)).isZero();
        assertThatThrownBy(() -> jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, "
            + "precio_final, version) values (?, 'V8-NULL', 'Articulo', 1.00, 2.00, null)", f))
            .hasMessageContaining("version");
    }

    @Test
    @DisplayName("aditiva: no quita ninguna columna ni ninguna migracion anterior")
    void noQuitaNada() {
        List<String> columnasAntes = columnasDeArticulo();

        migrarALa8();

        List<String> columnasDespues = columnasDeArticulo();
        assertThat(columnasDespues).containsAll(columnasAntes);
        assertThat(columnasDespues).hasSize(columnasAntes.size() + 1).contains("version");
        assertThat(jdbc.queryForList(
            "select version from flyway_schema_history where success = 1 order by installed_rank", String.class))
            .containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
    }

    @Test
    @DisplayName("datos demo: en una base dev existente V8 entra por out-of-order y los articulos demo quedan con version 0")
    void losDatosDemoNoCambian() {
        flyway("classpath:db/dev-data").ignoreMigrationPatterns("*:missing").load().migrate();
        String consulta = "select id, costo, precio_final, stock_actual from articulo order by id";
        List<Map<String, Object>> antes = jdbc.queryForList(consulta);
        assertThat(antes).isNotEmpty();

        flyway("classpath:db/migration", "classpath:db/dev-data").outOfOrder(true).load().migrate();

        assertThat(jdbc.queryForList(consulta)).isEqualTo(antes);
        assertThat(jdbc.queryForObject("select count(*) from articulo where version <> 0", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select success from flyway_schema_history where version = '8'", Integer.class))
            .isEqualTo(1);
    }

    @Test
    @DisplayName("rollback-v8-version-articulo.sql saca la columna y la V8 del historial, y deja lo demas")
    void rollback() throws Exception {
        Long f = ferreteria("30-78401001-3");
        Long articulo = articulo(f, "V8-RB", "900.00", "1420.00", 4);
        migrarALa8();

        Properties propiedades = new Properties();
        propiedades.setProperty("user", "root");
        propiedades.setProperty("password", MYSQL.getPassword());
        propiedades.setProperty("allowMultiQueries", "true");
        try (Connection c = DriverManager.getConnection(url, propiedades); Statement s = c.createStatement()) {
            s.execute(scriptDeRollback());
        }

        assertThat(columnasDeArticulo()).doesNotContain("version");
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '8'", Integer.class))
            .isZero();
        Map<String, Object> fila = jdbc.queryForMap("select costo, precio_final, stock_actual from articulo where id = ?",
            articulo);
        assertThat((BigDecimal) fila.get("costo")).isEqualByComparingTo("900");
        assertThat((BigDecimal) fila.get("stock_actual")).isEqualByComparingTo("4");
        // Y V8 se puede volver a aplicar.
        migrarALa8();
        assertThat(jdbc.queryForObject("select version from articulo where id = ?", Long.class, articulo)).isZero();
    }

    private static String scriptDeRollback() throws IOException {
        return StreamUtils.copyToString(
            new ClassPathResource("catalogo/rollback-v8-version-articulo.sql").getInputStream(), StandardCharsets.UTF_8);
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
