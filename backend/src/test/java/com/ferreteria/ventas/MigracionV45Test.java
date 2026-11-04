package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.support.PlantillaDeMigracion;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

/**
 * Migracion V45 (descuento en efectivo, S1 y S2): el descuento % de la forma de pago y el recargo con signo (negativo es
 * el descuento de la forma) en el comprobante, el renglon y el pago. Base nueva por test en la V44, siembra por JDBC y
 * recien despues migra a la V45. CUIT: bloque 30-7899997x.
 */
class MigracionV45Test {

    private static final MySQLContainer<?> MYSQL = PlantillaDeMigracion.MYSQL;

    private String url;
    private JdbcTemplate jdbc;

    @BeforeEach
    void baseNuevaEnLaVersion44() {
        url = PlantillaDeMigracion.url(PlantillaDeMigracion.baseEnLaVersion("v45", "44"));
        jdbc = new JdbcTemplate(new DriverManagerDataSource(url, "root", MYSQL.getPassword()));
    }

    private void migrarALa45() {
        Flyway.configure().dataSource(url, "root", MYSQL.getPassword()).locations("classpath:db/migration")
                .target("45").load().migrate();
    }

    @Test
    @DisplayName("las formas, ventas y pagos de antes quedan igual, y cada forma con descuento 0")
    void losDatosExistentesQuedanIgual() {
        long f = ferreteria("30-78999971-1");
        long forma = forma(f, "Visa", "CREDITO", "10.00");
        long venta = venta(f, 1, "110.00", "10.00");
        pago(f, venta, forma, "CREDITO", "10.00", "10.00", "110.00");
        Map<String, Object> formaAntes = jdbc.queryForMap("select * from forma_pago where id = ?", forma);
        Map<String, Object> ventaAntes = jdbc.queryForMap("select * from comprobante where id = ?", venta);
        Map<String, Object> pagoAntes = jdbc.queryForMap("select * from pago where comprobante_id = ?", venta);

        migrarALa45();

        Map<String, Object> formaDespues = new HashMap<>(jdbc.queryForMap("select * from forma_pago where id = ?", forma));
        assertThat((BigDecimal) formaDespues.remove("descuento_porcentaje")).isEqualByComparingTo("0");
        assertThat(formaDespues).isEqualTo(formaAntes);
        assertThat(jdbc.queryForMap("select * from comprobante where id = ?", venta)).isEqualTo(ventaAntes);
        assertThat(jdbc.queryForMap("select * from pago where comprobante_id = ?", venta)).isEqualTo(pagoAntes);
    }

    @Test
    @DisplayName("el descuento de la forma va de 0 a 99,99 y no convive con un recargo")
    void elDescuentoTieneLimites() {
        long f = ferreteria("30-78999971-2");
        long efectivo = forma(f, "Contado", "EFECTIVO", "0");
        long visa = forma(f, "Visa", "CREDITO", "5.00");
        migrarALa45();
        String fijar = "update forma_pago set descuento_porcentaje = ? where id = ?";

        assertThatThrownBy(() -> jdbc.update(fijar, "-0.01", efectivo)).hasMessageContaining("ck_forma_pago_descuento");
        assertThatThrownBy(() -> jdbc.update(fijar, "100.00", efectivo)).hasMessageContaining("ck_forma_pago_descuento");
        assertThatThrownBy(() -> jdbc.update(fijar, "10.00", visa))
                .hasMessageContaining("ck_forma_pago_recargo_o_descuento");
        assertThatCode(() -> jdbc.update(fijar, "99.99", efectivo)).doesNotThrowAnyException();
        assertThatCode(() -> jdbc.update(fijar, "0", visa)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("el recargo con signo: negativo en la venta, el renglon y el pago; el pago no admite -100 % ni signos cruzados")
    void elRecargoPuedeSerNegativo() {
        long f = ferreteria("30-78999971-3");
        long forma = forma(f, "Contado", "EFECTIVO", "0");
        migrarALa45();
        long venta = venta(f, 1, "900.00", "-100.00");

        assertThatCode(() -> pago(f, venta, forma, "EFECTIVO", "-10.00", "-100.00", "900.00"))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> pago(f, venta, forma, "EFECTIVO", "-100.00", "-1000.00", "0.01"))
                .hasMessageContaining("ck_pago_recargo_v45");
        assertThatThrownBy(() -> pago(f, venta, forma, "EFECTIVO", "10.00", "-1.00", "99.00"))
                .hasMessageContaining("ck_pago_recargo_v45");
        assertThatThrownBy(() -> pago(f, venta, forma, "EFECTIVO", "-10.00", "1.00", "101.00"))
                .hasMessageContaining("ck_pago_recargo_v45");
        assertThat(jdbc.queryForObject("select count(*) from information_schema.table_constraints "
                + "where constraint_schema = database() and constraint_name = 'ck_item_comprobante_recargo'",
                Integer.class)).isZero();
    }

    private long ferreteria(String cuit) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values (?, ?, 'RESPONSABLE_INSCRIPTO')",
                "Ferreteria " + cuit, cuit);
        long id = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, 'x', 'Dueno V45', 'DUENO')", id, "v45-" + cuit + "@test.local");
        return id;
    }

    private long forma(long ferreteria, String nombre, String medio, String recargo) {
        jdbc.update("insert into forma_pago (ferreteria_id, nombre, medio, recargo_porcentaje, activa, orden) "
                + "values (?, ?, ?, ?, 1, 1)", ferreteria, nombre, medio, new BigDecimal(recargo));
        return jdbc.queryForObject("select id from forma_pago where ferreteria_id = ? and nombre = ?", Long.class,
                ferreteria, nombre);
    }

    private long venta(long ferreteria, int numero, String total, String recargo) {
        long usuario = jdbc.queryForObject("select min(id) from usuario where ferreteria_id = ?", Long.class, ferreteria);
        BigDecimal subtotal = new BigDecimal(total).subtract(new BigDecimal(recargo));
        jdbc.update("insert into comprobante (ferreteria_id, tipo, estado, numero, clave_idempotencia, usuario_id, neto, "
                + "iva, total, subtotal, recargo) values (?, 'VENTA', 'SIN_FACTURAR', ?, ?, ?, 100, 21, ?, ?, ?)",
                ferreteria, numero, "v45-" + ferreteria + "-" + numero, usuario, new BigDecimal(total), subtotal,
                new BigDecimal(recargo));
        return jdbc.queryForObject("select id from comprobante where ferreteria_id = ? and numero = ?", Long.class,
                ferreteria, numero);
    }

    private void pago(long ferreteria, long comprobante, long forma, String medio, String porcentaje, String recargo,
            String monto) {
        jdbc.update("insert into pago (ferreteria_id, comprobante_id, medio, monto, forma_pago_id, forma_pago_nombre, "
                + "recargo_porcentaje, recargo) values (?, ?, ?, ?, ?, 'Forma', ?, ?)", ferreteria, comprobante, medio,
                new BigDecimal(monto), forma, new BigDecimal(porcentaje), new BigDecimal(recargo));
    }
}
