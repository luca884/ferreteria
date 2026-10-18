package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

/**
 * Migracion V15 (CU-07, GDS-13, PP-006): el comprobante guarda el presupuesto desde el que se hizo la venta. Usa un
 * MySQL de Testcontainers propio y una base nueva por test: migra hasta la V14, siembra ventas por JDBC y recien despues
 * migra a la V15.
 */
class MigracionV15Test {

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
    void baseNuevaEnLaVersion14() throws SQLException {
        String base = "v15_" + CONTADOR.incrementAndGet();
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
                Statement s = c.createStatement()) {
            s.execute("create database " + base + " character set utf8mb4");
        }
        url = MYSQL.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + base + "$1");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
        migrarHasta("14");
    }

    private void migrarHasta(String version) {
        Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations("classpath:db/migration")
                .target(version).load().migrate();
    }

    private long ferreteria(String cuit) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
                "Ferreteria " + cuit, cuit);
        long id = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, 'x', 'Dueno V15', 'DUENO')", id, "v15-" + cuit + "@test.local");
        return id;
    }

    private long usuario(long ferreteria) {
        return jdbc.queryForObject("select min(id) from usuario where ferreteria_id = ?", Long.class, ferreteria);
    }

    private long presupuesto(long ferreteria, int numero) {
        jdbc.update("insert into presupuesto (ferreteria_id, numero, clave_idempotencia, usuario_id, vence, estado, "
                + "subtotal, descuento_porcentaje, neto, iva, total) "
                + "values (?, ?, ?, ?, '2026-11-02', 'PENDIENTE', 121, 0, 100, 21, 121)", ferreteria, numero,
                "p15-" + ferreteria + "-" + numero, usuario(ferreteria));
        return jdbc.queryForObject("select id from presupuesto where ferreteria_id = ? and numero = ?", Long.class,
                ferreteria, numero);
    }

    private void venta(long ferreteria, int numero, Long presupuestoId) {
        jdbc.update("insert into comprobante (ferreteria_id, tipo, estado, numero, clave_idempotencia, usuario_id, neto, "
                + "iva, total, subtotal, presupuesto_id) values (?, 'VENTA', 'SIN_FACTURAR', ?, ?, ?, 100, 21, 121, 121, ?)",
                ferreteria, numero, "v15-" + ferreteria + "-" + numero, usuario(ferreteria), presupuestoId);
    }

    @Test
    @DisplayName("las ventas anteriores quedan sin presupuesto")
    void ventasAnterioresSinPresupuesto() {
        long f = ferreteria("30-71500001-1");
        jdbc.update("insert into comprobante (ferreteria_id, tipo, estado, numero, clave_idempotencia, usuario_id, neto, "
                + "iva, total, subtotal) values (?, 'VENTA', 'SIN_FACTURAR', 1, 'vieja', ?, 100, 21, 121, 121)", f,
                usuario(f));

        migrarHasta("15");

        assertThat(jdbc.queryForObject("select presupuesto_id from comprobante where ferreteria_id = ?", Long.class, f))
                .isNull();
    }

    @Test
    @DisplayName("la columna nueva es un BIGINT que admite nulo")
    void columnaNueva() {
        migrarHasta("15");

        Map<String, Object> fila = jdbc.queryForMap("select column_type as tipo, is_nullable as nulo, "
                + "column_default as defecto from information_schema.columns where table_schema = database() "
                + "and table_name = 'comprobante' and column_name = 'presupuesto_id'");

        assertThat(fila.get("tipo") + " " + fila.get("nulo") + " " + fila.get("defecto")).isEqualTo("bigint YES null");
    }

    @Test
    @DisplayName("una venta puede venir de un presupuesto, y las que no vienen de ninguno pueden ser muchas")
    void ventaDeUnPresupuesto() {
        migrarHasta("15");
        long f = ferreteria("30-71500002-2");
        long p = presupuesto(f, 1);

        venta(f, 1, p);
        venta(f, 2, null);
        venta(f, 3, null);

        assertThat(jdbc.queryForObject("select count(*) from comprobante where presupuesto_id = ?", Long.class, p))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("un presupuesto se factura una sola vez: dos ventas del mismo presupuesto chocan")
    void unPresupuestoUnaVenta() {
        migrarHasta("15");
        long f = ferreteria("30-71500003-3");
        long p = presupuesto(f, 1);
        venta(f, 1, p);

        assertThatThrownBy(() -> venta(f, 2, p)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_comprobante_presupuesto");
    }

    @Test
    @DisplayName("una venta no puede apuntar a un presupuesto que no existe")
    void presupuestoInexistente() {
        migrarHasta("15");
        long f = ferreteria("30-71500004-4");

        assertThatThrownBy(() -> venta(f, 1, 999_999L)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_comprobante_presupuesto");
    }
}
