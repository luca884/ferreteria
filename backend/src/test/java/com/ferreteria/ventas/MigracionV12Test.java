package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
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
 * Migracion V12 (CU-07, GDS-11, RC-001 y RC-002): la tabla forma_pago y sus cuatro formas por defecto en cada
 * ferreteria que ya existe. Usa un MySQL de Testcontainers propio y una base nueva por test: migra hasta la V11,
 * siembra por JDBC y recien despues migra a la V12.
 */
class MigracionV12Test {

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
    void baseNuevaEnLaVersion11() throws SQLException {
        String base = "v12_" + CONTADOR.incrementAndGet();
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
                Statement s = c.createStatement()) {
            s.execute("create database " + base + " character set utf8mb4");
        }
        url = MYSQL.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + base + "$1");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
        Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations("classpath:db/migration")
                .target("11").load().migrate();
    }

    private void migrarALa12() {
        Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations("classpath:db/migration")
                .target("12").load().migrate();
    }

    private long ferreteria(String cuit) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
                "Ferreteria " + cuit, cuit);
        return jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
    }

    private List<Map<String, Object>> formas(long ferreteria) {
        return jdbc.queryForList("select nombre, medio, recargo_porcentaje, activa, orden from forma_pago "
                + "where ferreteria_id = ? order by orden", ferreteria);
    }

    @Test
    @DisplayName("cada ferreteria que ya existia queda con Efectivo, Debito, Credito y Transferencia, sin recargo y activas")
    void lasFerreteriasExistentesQuedanConLasCuatroFormas() {
        long a = ferreteria("30-71200001-1");
        long b = ferreteria("30-71200002-2");

        migrarALa12();

        for (long f : new long[] {a, b}) {
            List<Map<String, Object>> formas = formas(f);
            assertThat(formas).extracting(m -> m.get("nombre"))
                    .containsExactly("Efectivo", "Débito", "Crédito", "Transferencia");
            assertThat(formas).extracting(m -> m.get("medio"))
                    .containsExactly("EFECTIVO", "DEBITO", "CREDITO", "TRANSFERENCIA");
            assertThat(formas).extracting(m -> m.get("orden")).containsExactly(1, 2, 3, 4);
            assertThat(formas).allSatisfy(m -> {
                assertThat((BigDecimal) m.get("recargo_porcentaje")).isEqualByComparingTo("0");
                assertThat(m.get("activa")).isIn(true, 1);
            });
        }
    }

    @Test
    @DisplayName("sin ferreterias, la migracion no siembra nada")
    void sinFerreteriasNoSiembra() {
        migrarALa12();

        assertThat(jdbc.queryForObject("select count(*) from forma_pago", Long.class)).isZero();
    }

    @Test
    @DisplayName("el nombre es unico por ferreteria sin distinguir mayusculas ni tildes, pero otra ferreteria puede repetirlo")
    void nombreUnicoPorFerreteria() {
        long a = ferreteria("30-71200003-3");
        long b = ferreteria("30-71200004-4");
        migrarALa12();

        assertThatThrownBy(() -> insertar(a, "EFECTIVO", "EFECTIVO", "0"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("uk_forma_pago_nombre");
        assertThatThrownBy(() -> insertar(a, "debito", "DEBITO", "0"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("uk_forma_pago_nombre");
        assertThatThrownBy(() -> insertar(a, "Credito", "CREDITO", "0"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("uk_forma_pago_nombre");
        assertThat(jdbc.queryForObject("select count(*) from forma_pago where nombre = 'Efectivo' "
                + "and ferreteria_id in (?, ?)", Long.class, a, b)).isEqualTo(2);
    }

    @Test
    @DisplayName("el recargo va de 0 a 200 tambien en la base")
    void recargoAcotado() {
        long f = ferreteria("30-71200005-5");
        migrarALa12();

        insertar(f, "Visa 12 cuotas", "CREDITO", "200.00");
        assertThatThrownBy(() -> insertar(f, "Demasiado", "CREDITO", "200.01"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_forma_pago_recargo");
        assertThatThrownBy(() -> insertar(f, "Negativo", "CREDITO", "-0.01"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_forma_pago_recargo");
    }

    @Test
    @DisplayName("sin recargo, activa ni orden en el INSERT: 0, activa y orden 0")
    void valoresPorDefecto() {
        long f = ferreteria("30-71200006-6");
        migrarALa12();

        jdbc.update("insert into forma_pago (ferreteria_id, nombre, medio) values (?, 'Mercado Pago', 'TRANSFERENCIA')",
                f);

        Map<String, Object> fila = jdbc.queryForMap("select recargo_porcentaje, activa, orden from forma_pago "
                + "where ferreteria_id = ? and nombre = 'Mercado Pago'", f);
        assertThat((BigDecimal) fila.get("recargo_porcentaje")).isEqualByComparingTo("0");
        assertThat(fila.get("activa")).isIn(true, 1);
        assertThat(fila.get("orden")).isEqualTo(0);
    }

    private void insertar(long ferreteria, String nombre, String medio, String recargo) {
        jdbc.update("insert into forma_pago (ferreteria_id, nombre, medio, recargo_porcentaje, activa, orden) "
                + "values (?, ?, ?, ?, true, 9)", ferreteria, nombre, medio, new BigDecimal(recargo));
    }
}
