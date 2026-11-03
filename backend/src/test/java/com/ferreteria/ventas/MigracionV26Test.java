package com.ferreteria.ventas;

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
 * Migracion V26 (reportes de ventas): item_comprobante suma costo_unitario DECIMAL(15,4) y costo DECIMAL(15,2), nulos.
 * Aditiva: las ventas existentes quedan sin costo (no se completan con el costo de hoy). Usa un MySQL de Testcontainers
 * propio de la clase y una base nueva por test: migra hasta la V25, siembra por JDBC y recien despues migra a la V26.
 * CUIT: bloque 30-7860126x.
 */
class MigracionV26Test {

    private static final MySQLContainer<?> MYSQL = PlantillaDeMigracion.MYSQL;

    private String url;
    private JdbcTemplate jdbc;

    @BeforeEach
    void baseNuevaEnLaVersion25() {
        url = PlantillaDeMigracion.url(PlantillaDeMigracion.baseEnLaVersion("v26", "25"));
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration flyway(String... ubicaciones) {
        return Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations(ubicaciones);
    }

    private void migrarALa26() {
        flyway("classpath:db/migration").target("26").load().migrate();
    }

    @Test
    @DisplayName("item_comprobante suma costo_unitario DECIMAL(15,4) y costo DECIMAL(15,2), nulos y sin default")
    void columnasNuevas() {
        migrarALa26();

        Map<String, Object> unitario = columna("costo_unitario");
        assertThat(unitario.get("tipo")).isEqualTo("decimal(15,4)");
        assertThat(unitario.get("nulo")).isEqualTo("YES");
        assertThat(unitario.get("defecto")).isNull();
        Map<String, Object> costo = columna("costo");
        assertThat(costo.get("tipo")).isEqualTo("decimal(15,2)");
        assertThat(costo.get("nulo")).isEqualTo("YES");
        assertThat(costo.get("defecto")).isNull();
    }

    @Test
    @DisplayName("un renglon vendido antes de V26 queda sin costo y con todo lo demas igual")
    void lasVentasExistentesQuedanSinCosto() {
        long item = renglonVendido("30-78601261-1");
        Map<String, Object> antes = jdbc.queryForMap("select * from item_comprobante where id = ?", item);

        migrarALa26();

        Map<String, Object> despues = new HashMap<>(jdbc.queryForMap("select * from item_comprobante where id = ?", item));
        assertThat(despues.remove("costo_unitario")).isNull();
        assertThat(despues.remove("costo")).isNull();
        assertThat(despues).isEqualTo(antes);
    }

    @Test
    @DisplayName("el costo va completo o vacio, y nunca negativo")
    void costoCompletoYNoNegativo() {
        long item = renglonVendido("30-78601261-2");
        migrarALa26();
        String actualizar = "update item_comprobante set costo_unitario = ?, costo = ? where id = ?";

        jdbc.update(actualizar, new BigDecimal("12.3456"), new BigDecimal("24.69"), item);
        jdbc.update(actualizar, BigDecimal.ZERO, BigDecimal.ZERO, item);
        jdbc.update(actualizar, null, null, item);
        assertThatThrownBy(() -> jdbc.update(actualizar, new BigDecimal("1"), null, item))
            .hasMessageContaining("ck_item_comprobante_costo");
        assertThatThrownBy(() -> jdbc.update(actualizar, null, new BigDecimal("1"), item))
            .hasMessageContaining("ck_item_comprobante_costo");
        assertThatThrownBy(() -> jdbc.update(actualizar, new BigDecimal("-1"), new BigDecimal("1"), item))
            .hasMessageContaining("ck_item_comprobante_costo");
        assertThatThrownBy(() -> jdbc.update(actualizar, new BigDecimal("1"), new BigDecimal("-1"), item))
            .hasMessageContaining("ck_item_comprobante_costo");
    }

    @Test
    @DisplayName("aditiva: no quita ninguna columna ni ninguna migracion anterior")
    void noQuitaNada() {
        List<String> columnasAntes = columnasDeItem();

        migrarALa26();

        List<String> columnasDespues = columnasDeItem();
        assertThat(columnasDespues).containsAll(columnasAntes);
        assertThat(columnasDespues).hasSize(columnasAntes.size() + 2).contains("costo_unitario", "costo");
        assertThat(jdbc.queryForObject(
            "select count(*) from flyway_schema_history where success = 1 and version is not null", Integer.class))
            .isEqualTo(26);
    }

    @Test
    @DisplayName("datos demo: en una base dev existente V26 entra por out-of-order")
    void losDatosDemoSiguenIgual() {
        flyway("classpath:db/dev-data").ignoreMigrationPatterns("*:missing").load().migrate();
        String consulta = "select id, codigo_interno, costo, precio_final from articulo order by id";
        List<Map<String, Object>> antes = jdbc.queryForList(consulta);
        assertThat(antes).isNotEmpty();

        flyway("classpath:db/migration", "classpath:db/dev-data").outOfOrder(true).load().migrate();

        assertThat(jdbc.queryForList(consulta)).isEqualTo(antes);
        assertThat(columnasDeItem()).contains("costo_unitario", "costo");
    }

    @Test
    @DisplayName("rollback-v26-costo-vendido.sql saca las columnas y la V26 del historial, y deja lo demas")
    void rollback() throws Exception {
        long item = renglonVendido("30-78601261-3");
        migrarALa26();
        jdbc.update("update item_comprobante set costo_unitario = 10, costo = 10 where id = ?", item);

        Properties propiedades = new Properties();
        propiedades.setProperty("user", "root");
        propiedades.setProperty("password", MYSQL.getPassword());
        propiedades.setProperty("allowMultiQueries", "true");
        try (Connection c = DriverManager.getConnection(url, propiedades); Statement s = c.createStatement()) {
            s.execute(scriptDeRollback());
        }

        assertThat(columnasDeItem()).doesNotContain("costo_unitario", "costo");
        assertThat(jdbc.queryForObject("select count(*) from flyway_schema_history where version = '26'", Integer.class))
            .isZero();
        assertThat(jdbc.queryForObject("select descripcion from item_comprobante where id = ?", String.class, item))
            .isEqualTo("Tornillo");
        // Y V26 se puede volver a aplicar.
        migrarALa26();
        assertThat(jdbc.queryForObject("select costo from item_comprobante where id = ?", BigDecimal.class, item))
            .isNull();
    }

    private static String scriptDeRollback() throws IOException {
        return StreamUtils.copyToString(
            new ClassPathResource("ventas/rollback-v26-costo-vendido.sql").getInputStream(), StandardCharsets.UTF_8);
    }

    private Map<String, Object> columna(String nombre) {
        return jdbc.queryForMap("select column_type as tipo, is_nullable as nulo, column_default as defecto "
            + "from information_schema.columns where table_schema = database() and table_name = 'item_comprobante' "
            + "and column_name = ?", nombre);
    }

    private List<String> columnasDeItem() {
        return jdbc.queryForList("select column_name from information_schema.columns "
            + "where table_schema = database() and table_name = 'item_comprobante'", String.class);
    }

    /** Siembra una ferreteria, un usuario, un articulo y una venta de un renglon; devuelve el id del renglon. */
    private long renglonVendido(String cuit) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
            "Ferreteria " + cuit, cuit);
        long f = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) values (?, ?, 'x', 'U', "
            + "'DUENO')", f, cuit + "@prueba.local");
        long u = jdbc.queryForObject("select id from usuario where ferreteria_id = ?", Long.class, f);
        jdbc.update("insert into comprobante (ferreteria_id, tipo, estado, numero, clave_idempotencia, usuario_id, neto, "
            + "iva, total, subtotal) values (?, 'VENTA', 'SIN_FACTURAR', 1, ?, ?, 82.64, 17.36, 100, 100)", f, cuit, u);
        long comprobante = jdbc.queryForObject("select id from comprobante where ferreteria_id = ?", Long.class, f);
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, "
            + "alicuota_iva, unidad, stock_actual, stock_minimo, activo) values (?, 'V26-A', 'Tornillo', 50, 100, "
            + "'IVA_21', 'UNIDAD', 0, 0, true)", f);
        long articulo = jdbc.queryForObject("select id from articulo where ferreteria_id = ?", Long.class, f);
        jdbc.update("insert into item_comprobante (ferreteria_id, comprobante_id, renglon, articulo_id, descripcion, "
            + "unidad, cantidad, precio_unitario, alicuota_iva, bruto, descuento_porcentaje, total, neto, iva) "
            + "values (?, ?, 1, ?, 'Tornillo', 'UNIDAD', 1, 100, 'IVA_21', 100, 0, 100, 82.64, 17.36)",
            f, comprobante, articulo);
        return jdbc.queryForObject("select id from item_comprobante where ferreteria_id = ?", Long.class, f);
    }
}
