package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
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
 * Migracion V14 (CU-07, GDS-13, PP-001): las tablas del presupuesto y su numerador propio. Usa un MySQL de Testcontainers
 * propio y una base nueva por test.
 */
class MigracionV14Test {

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
    void baseNueva() throws SQLException {
        String base = "v14_" + CONTADOR.incrementAndGet();
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
                Statement s = c.createStatement()) {
            s.execute("create database " + base + " character set utf8mb4");
        }
        url = MYSQL.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + base + "$1");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
        Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations("classpath:db/migration")
                .target("14").load().migrate();
    }

    private long ferreteria(String cuit) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
                "Ferreteria " + cuit, cuit);
        long id = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, 'x', 'Dueno V14', 'DUENO')", id, "v14-" + cuit + "@test.local");
        return id;
    }

    private void presupuesto(long ferreteria, long numero, String clave) {
        long usuario = jdbc.queryForObject("select min(id) from usuario where ferreteria_id = ?", Long.class, ferreteria);
        jdbc.update("insert into presupuesto (ferreteria_id, numero, clave_idempotencia, usuario_id, vence, estado, "
                + "subtotal, descuento_porcentaje, neto, iva, total) "
                + "values (?, ?, ?, ?, '2026-11-02', 'PENDIENTE', 121, 0, 100, 21, 121)", ferreteria, numero, clave,
                usuario);
    }

    @Test
    @DisplayName("las columnas del presupuesto: tipos, nulabilidad y defaults")
    void columnasDelPresupuesto() {
        Map<String, String> esperadas = Map.ofEntries(
                Map.entry("presupuesto.numero", "bigint NO null"),
                Map.entry("presupuesto.clave_idempotencia", "varchar(36) NO null"),
                Map.entry("presupuesto.vence", "date NO null"),
                Map.entry("presupuesto.estado", "varchar(20) NO null"),
                Map.entry("presupuesto.cliente_nombre", "varchar(80) YES null"),
                Map.entry("presupuesto.observaciones", "varchar(200) YES null"),
                Map.entry("presupuesto.subtotal", "decimal(15,2) NO null"),
                Map.entry("presupuesto.descuento_porcentaje", "decimal(5,2) NO null"),
                Map.entry("presupuesto.total", "decimal(15,2) NO null"),
                Map.entry("item_presupuesto.cantidad", "decimal(15,3) NO null"),
                Map.entry("item_presupuesto.precio_unitario", "decimal(15,2) NO null"),
                Map.entry("item_presupuesto.alicuota_iva", "varchar(10) NO null"),
                Map.entry("item_presupuesto.descuento_porcentaje", "decimal(5,2) NO null"),
                Map.entry("item_presupuesto.descripcion", "varchar(200) NO null"),
                Map.entry("numerador_presupuesto.ultimo_numero", "bigint NO null"));
        for (Map.Entry<String, String> e : esperadas.entrySet()) {
            String[] tablaColumna = e.getKey().split("\\.");
            Map<String, Object> fila = jdbc.queryForMap("select column_type as tipo, is_nullable as nulo, "
                    + "column_default as defecto from information_schema.columns where table_schema = database() "
                    + "and table_name = ? and column_name = ?", tablaColumna[0], tablaColumna[1]);
            assertThat(fila.get("tipo") + " " + fila.get("nulo") + " " + fila.get("defecto")).as(e.getKey())
                    .isEqualTo(e.getValue());
        }
    }

    @Test
    @DisplayName("el numero y la clave son unicos por ferreteria, y otra ferreteria puede repetirlos")
    void numeroYClaveUnicosPorFerreteria() {
        long a = ferreteria("30-71400001-1");
        long b = ferreteria("30-71400002-2");
        presupuesto(a, 1, "clave-1");

        assertThatThrownBy(() -> presupuesto(a, 1, "clave-2")).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_presupuesto_numero");
        assertThatThrownBy(() -> presupuesto(a, 2, "clave-1")).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_presupuesto_clave");
        presupuesto(b, 1, "clave-1");

        assertThat(jdbc.queryForObject("select count(*) from presupuesto", Long.class)).isEqualTo(2);
    }

    @Test
    @DisplayName("un renglon no puede apuntar a un presupuesto ni a un articulo que no existen")
    void renglonConReferenciasValidas() {
        long f = ferreteria("30-71400003-3");

        assertThatThrownBy(() -> jdbc.update("insert into item_presupuesto (ferreteria_id, presupuesto_id, renglon, "
                + "articulo_id, descripcion, unidad, cantidad, precio_unitario, alicuota_iva, bruto, "
                + "descuento_porcentaje, total, neto, iva) values (?, 999999, 1, 999999, 'x', 'UNIDAD', 1, 121, "
                + "'IVA_21', 121, 0, 121, 100, 21)", f)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("fk_item_presupuesto_presupuesto");
    }

    @Test
    @DisplayName("el numerador es uno por ferreteria")
    void numeradorUnoPorFerreteria() {
        long f = ferreteria("30-71400004-4");
        jdbc.update("insert into numerador_presupuesto (ferreteria_id, ultimo_numero) values (?, 1)", f);

        assertThatThrownBy(() -> jdbc.update("insert into numerador_presupuesto (ferreteria_id, ultimo_numero) "
                + "values (?, 2)", f)).isInstanceOf(DataAccessException.class)
                .hasMessageContaining("uk_numerador_presupuesto_ferreteria");
    }

    @Test
    @DisplayName("los presupuestos se buscan por indices que empiezan por la ferreteria")
    void indices() {
        List<String> porEstado = jdbc.queryForList("select column_name from information_schema.statistics "
                + "where table_schema = database() and table_name = 'presupuesto' and index_name = 'ix_presupuesto_estado' "
                + "order by seq_in_index", String.class);
        List<String> renglones = jdbc.queryForList("select column_name from information_schema.statistics "
                + "where table_schema = database() and table_name = 'item_presupuesto' "
                + "and index_name = 'ix_item_presupuesto' order by seq_in_index", String.class);

        assertThat(porEstado).containsExactly("ferreteria_id", "estado", "fecha");
        assertThat(renglones).containsExactly("ferreteria_id", "presupuesto_id", "renglon");
    }
}
