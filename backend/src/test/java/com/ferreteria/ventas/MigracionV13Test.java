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
 * Migracion V13 (CU-07, GDS-11, RC-006): el recargo en el comprobante, en cada renglon y en cada pago, y la forma de
 * pago de cada pago. Usa un MySQL de Testcontainers propio y una base nueva por test: migra hasta la V12, siembra ventas
 * y pagos por JDBC y recien despues migra a la V13.
 */
class MigracionV13Test {

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
        String base = "v13_" + CONTADOR.incrementAndGet();
        try (Connection c = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
                Statement s = c.createStatement()) {
            s.execute("create database " + base + " character set utf8mb4");
        }
        url = MYSQL.getJdbcUrl().replaceFirst("/[^/?]+(\\?|$)", "/" + base + "$1");
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
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
                + "values (?, ?, 'x', 'Dueno V13', 'DUENO')", id, "v13-" + cuit + "@test.local");
        return id;
    }

    private long venta(long ferreteria, int numero, String total) {
        long usuario = jdbc.queryForObject("select min(id) from usuario where ferreteria_id = ?", Long.class, ferreteria);
        jdbc.update("insert into comprobante (ferreteria_id, tipo, estado, numero, clave_idempotencia, usuario_id, neto, "
                + "iva, total, subtotal) values (?, 'VENTA', 'SIN_FACTURAR', ?, ?, ?, 100, 21, ?, ?)", ferreteria, numero,
                "v13-" + ferreteria + "-" + numero, usuario, new BigDecimal(total), new BigDecimal(total));
        return jdbc.queryForObject("select id from comprobante where ferreteria_id = ? and numero = ?", Long.class,
                ferreteria, numero);
    }

    private void pago(long ferreteria, long comprobante, String medio, String monto) {
        jdbc.update("insert into pago (ferreteria_id, comprobante_id, medio, monto) values (?, ?, ?, ?)", ferreteria,
                comprobante, medio, new BigDecimal(monto));
    }

    private Map<String, Object> pagoDe(long comprobante, String medio) {
        return jdbc.queryForMap("select p.*, f.id as forma_id, f.nombre as forma_nombre from pago p "
                + "join forma_pago f on f.id = p.forma_pago_id where p.comprobante_id = ? and p.medio = ?", comprobante,
                medio);
    }

    @Test
    @DisplayName("los pagos anteriores apuntan a la forma de su medio, con su nombre, sin recargo y con el mismo monto")
    void losPagosExistentesApuntanALaFormaDeSuMedio() {
        migrarHasta("11");
        long f = ferreteria("30-71300001-1");
        long c = venta(f, 1, "121.00");
        pago(f, c, "EFECTIVO", "100.00");
        pago(f, c, "DEBITO", "21.00");

        migrarHasta("13");

        Map<String, Object> efectivo = pagoDe(c, "EFECTIVO");
        assertThat(efectivo.get("forma_pago_id")).isEqualTo(efectivo.get("forma_id"));
        assertThat(efectivo.get("forma_pago_nombre")).isEqualTo("Efectivo");
        assertThat((BigDecimal) efectivo.get("recargo_porcentaje")).isEqualByComparingTo("0");
        assertThat((BigDecimal) efectivo.get("recargo")).isEqualByComparingTo("0");
        assertThat((BigDecimal) efectivo.get("monto")).isEqualByComparingTo("100.00");
        Map<String, Object> debito = pagoDe(c, "DEBITO");
        assertThat(debito.get("forma_pago_nombre")).isEqualTo("Débito");
        assertThat((BigDecimal) debito.get("monto")).isEqualByComparingTo("21.00");
    }

    @Test
    @DisplayName("una ferreteria con pagos pero sin formas (creada despues de la V12) recibe las de siempre antes de enlazar")
    void ferreteriaSinFormasLasRecibe() {
        migrarHasta("12");
        long f = ferreteria("30-71300002-2");
        long c = venta(f, 1, "50.00");
        pago(f, c, "TRANSFERENCIA", "50.00");

        migrarHasta("13");

        assertThat(jdbc.queryForObject("select count(*) from forma_pago where ferreteria_id = ?", Long.class, f))
                .isEqualTo(4);
        assertThat(pagoDe(c, "TRANSFERENCIA").get("forma_pago_nombre")).isEqualTo("Transferencia");
    }

    @Test
    @DisplayName("una ferreteria que ya tiene formas propias no recibe las cuatro de nuevo")
    void noSeVuelveASembrar() {
        migrarHasta("12");
        long f = ferreteria("30-71300003-3");
        jdbc.update("insert into forma_pago (ferreteria_id, nombre, medio, orden) values (?, 'Contado', 'EFECTIVO', 1)", f);
        long c = venta(f, 1, "10.00");
        pago(f, c, "EFECTIVO", "10.00");

        migrarHasta("13");

        assertThat(jdbc.queryForList("select nombre from forma_pago where ferreteria_id = ?", String.class, f))
                .containsExactly("Contado");
        assertThat(pagoDe(c, "EFECTIVO").get("forma_pago_nombre")).isEqualTo("Contado");
    }

    @Test
    @DisplayName("los comprobantes y renglones anteriores quedan con recargo 0")
    void comprobantesAnterioresSinRecargo() {
        migrarHasta("11");
        long f = ferreteria("30-71300004-4");
        long c = venta(f, 1, "121.00");

        migrarHasta("13");

        assertThat(jdbc.queryForObject("select recargo from comprobante where id = ?", BigDecimal.class, c))
                .isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("las columnas nuevas: tipos, nulabilidad y defaults")
    void columnasNuevas() {
        migrarHasta("13");

        Map<String, String> esperadas = Map.of(
                "comprobante.recargo", "decimal(15,2) NO 0.00",
                "item_comprobante.recargo", "decimal(15,2) NO 0.00",
                "pago.forma_pago_id", "bigint NO null",
                "pago.forma_pago_nombre", "varchar(60) NO null",
                "pago.recargo_porcentaje", "decimal(5,2) NO 0.00",
                "pago.recargo", "decimal(15,2) NO 0.00");
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
    @DisplayName("el recargo no puede ser negativo en ninguna de las tres tablas")
    void recargoNoNegativo() {
        migrarHasta("13");
        long f = ferreteria("30-71300005-5");
        jdbc.update("insert into forma_pago (ferreteria_id, nombre, medio, orden) values (?, 'Efectivo', 'EFECTIVO', 1)", f);
        long forma = jdbc.queryForObject("select id from forma_pago where ferreteria_id = ? and medio = 'EFECTIVO'",
                Long.class, f);
        long c = venta(f, 1, "10.00");

        assertThatThrownBy(() -> jdbc.update("update comprobante set recargo = -1 where id = ?", c))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_comprobante_recargo");
        assertThatThrownBy(() -> jdbc.update("insert into pago (ferreteria_id, comprobante_id, medio, monto, "
                + "forma_pago_id, forma_pago_nombre, recargo) values (?, ?, 'EFECTIVO', 10, ?, 'Efectivo', -1)", f, c,
                forma)).isInstanceOf(DataAccessException.class).hasMessageContaining("ck_pago_recargo");
    }

    @Test
    @DisplayName("un pago no puede apuntar a una forma que no existe")
    void formaInexistente() {
        migrarHasta("13");
        long f = ferreteria("30-71300006-6");
        long c = venta(f, 1, "10.00");

        assertThatThrownBy(() -> jdbc.update("insert into pago (ferreteria_id, comprobante_id, medio, monto, "
                + "forma_pago_id, forma_pago_nombre) values (?, ?, 'EFECTIVO', 10, 999999, 'Efectivo')", f, c))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("fk_pago_forma_pago");
    }

    @Test
    @DisplayName("los pagos de una forma se buscan por indice")
    void indiceDeLaForma() {
        migrarHasta("13");

        List<String> columnas = jdbc.queryForList("select column_name from information_schema.statistics "
                + "where table_schema = database() and table_name = 'pago' and index_name = 'ix_pago_forma_pago' "
                + "order by seq_in_index", String.class);

        assertThat(columnas).containsExactly("ferreteria_id", "forma_pago_id");
    }
}
