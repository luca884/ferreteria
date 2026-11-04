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
 * Migracion V46 (CU-13, recargos del costo): articulo.recargos_costo y compra.recargos, JSON NOT NULL DEFAULT [].
 * Aditiva. Usa un MySQL de Testcontainers propio de la clase y una base nueva por test: migra hasta la V45, siembra por
 * JDBC y recien despues migra a la V46. CUIT: bloque 30-7840146x.
 */
class MigracionV46Test {

    private static final MySQLContainer<?> MYSQL = PlantillaDeMigracion.MYSQL;

    private String url;
    private JdbcTemplate jdbc;

    @BeforeEach
    void baseNuevaEnLaVersion45() {
        url = PlantillaDeMigracion.url(PlantillaDeMigracion.baseEnLaVersion("v46", "45"));
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
    }

    private void migrarALa46() {
        Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations("classpath:db/migration")
            .target("46").load().migrate();
    }

    @Test
    @DisplayName("articulo.recargos_costo y compra.recargos son JSON NOT NULL con default []")
    void columnasNuevas() {
        migrarALa46();

        for (String tabla : List.of("articulo", "compra")) {
            String columna = tabla.equals("articulo") ? "recargos_costo" : "recargos";
            Map<String, Object> datos = jdbc.queryForMap("select column_type as tipo, is_nullable as nulo "
                + "from information_schema.columns where table_schema = database() and table_name = ? "
                + "and column_name = ?", tabla, columna);
            assertThat(datos.get("tipo")).isEqualTo("json");
            assertThat(datos.get("nulo")).isEqualTo("NO");
        }
    }

    @Test
    @DisplayName("un articulo anterior a V46 queda sin recargos y con todo lo demas igual; NULL se rechaza")
    void losExistentesQuedanIgual() {
        Long f = ferreteria("30-78401461-1");
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, "
            + "stock_actual) values (?, 'V46-1', 'Articulo de prueba', 900.00, 1420.00, 10)", f);
        Long articulo = jdbc.queryForObject("select id from articulo where codigo_interno = 'V46-1'", Long.class);
        Map<String, Object> antes = jdbc.queryForMap("select * from articulo where id = ?", articulo);

        migrarALa46();

        Map<String, Object> despues = new java.util.HashMap<>(jdbc.queryForMap("select * from articulo where id = ?", articulo));
        assertThat(despues.remove("recargos_costo")).isEqualTo("[]");
        assertThat(despues).isEqualTo(antes);
        assertThatThrownBy(() -> jdbc.update("update articulo set recargos_costo = null where id = ?", articulo))
            .hasMessageContaining("recargos_costo");
    }

    @Test
    @DisplayName("rollback-v46-recargos-del-costo.sql saca las columnas y la V46 del historial, y deja lo demas")
    void rollback() throws Exception {
        Long f = ferreteria("30-78401461-2");
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, "
            + "stock_actual) values (?, 'V46-RB', 'Articulo de prueba', 900.00, 1420.00, 4)", f);
        Long articulo = jdbc.queryForObject("select id from articulo where codigo_interno = 'V46-RB'", Long.class);
        migrarALa46();
        jdbc.update("update articulo set recargos_costo = "
            + "json_array(json_object('nombre', 'Embalaje', 'porcentaje', 4.44)) where id = ?", articulo);

        Properties propiedades = new Properties();
        propiedades.setProperty("user", "root");
        propiedades.setProperty("password", MYSQL.getPassword());
        propiedades.setProperty("allowMultiQueries", "true");
        try (Connection c = DriverManager.getConnection(url, propiedades); Statement s = c.createStatement()) {
            s.execute(StreamUtils.copyToString(
                new ClassPathResource("catalogo/rollback-v46-recargos-del-costo.sql").getInputStream(),
                StandardCharsets.UTF_8));
        }

        assertThat(columnas("articulo")).doesNotContain("recargos_costo");
        assertThat(columnas("compra")).doesNotContain("recargos");
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '46'", Integer.class))
            .isZero();
        Map<String, Object> fila = jdbc.queryForMap("select precio_final, stock_actual from articulo where id = ?", articulo);
        assertThat((BigDecimal) fila.get("precio_final")).isEqualByComparingTo("1420");
        assertThat((BigDecimal) fila.get("stock_actual")).isEqualByComparingTo("4");
        // Y V46 se puede volver a aplicar.
        migrarALa46();
        assertThat(jdbc.queryForObject("select json_length(recargos_costo) from articulo where id = ?", Integer.class,
            articulo)).isZero();
    }

    private List<String> columnas(String tabla) {
        return jdbc.queryForList("select column_name from information_schema.columns "
            + "where table_schema = database() and table_name = ?", String.class, tabla);
    }

    private Long ferreteria(String cuit) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
            "Ferreteria " + cuit, cuit);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
    }
}
