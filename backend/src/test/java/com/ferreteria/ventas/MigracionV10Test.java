package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.support.IntegrationTestBase;
import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/** V10 (CU-10): la caja, sus movimientos y la caja de cada comprobante. */
class MigracionV10Test extends IntegrationTestBase {

    private long ferreteria(String cuit) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values ('Migracion V10', ?, "
                + "'RESPONSABLE_INSCRIPTO') on duplicate key update razon_social = razon_social", cuit);
        long id = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        String hash = jdbc.queryForObject(
                "select password_hash from usuario where email = 'vendedor@tornillo.demo'", String.class);
        jdbc.update("insert ignore into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, ?, 'Usuario V10', 'DUENO')", id, "v10-" + cuit + "@test.local", hash);
        return id;
    }

    @Test
    @DisplayName("abierta solo admite TRUE o NULL: FALSE no se cuela como una caja cerrada a medias")
    void abiertaSinFalse() {
        long f = ferreteria("30-71010001-1");
        Long usuario = jdbc.queryForObject("select min(id) from usuario where ferreteria_id = ?", Long.class, f);

        assertThatThrownBy(() -> jdbc.update("insert into caja (ferreteria_id, abierta, fecha_apertura, "
                + "usuario_apertura_id, efectivo_inicial) values (?, false, now(6), ?, 0)", f, usuario))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_caja_abierta");
    }

    @Test
    @DisplayName("varias cajas cerradas (abierta NULL) conviven con una abierta en la misma ferreteria")
    void variasCerradasYUnaAbierta() {
        long f = ferreteria("30-71010002-2");
        Long usuario = jdbc.queryForObject("select min(id) from usuario where ferreteria_id = ?", Long.class, f);
        for (int i = 0; i < 2; i++) {
            jdbc.update("insert into caja (ferreteria_id, abierta, fecha_apertura, usuario_apertura_id, "
                    + "efectivo_inicial, fecha_cierre) values (?, null, now(6), ?, 0, now(6))", f, usuario);
        }
        cajaAbiertaHoy(f);

        assertThat(jdbc.queryForObject("select count(*) from caja where ferreteria_id = ?", Long.class, f))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("el importe de un movimiento de caja tiene que ser mayor que cero tambien en la base")
    void importePositivo() {
        long f = ferreteria("30-71010003-3");
        long caja = cajaAbiertaHoy(f);
        Long usuario = jdbc.queryForObject("select min(id) from usuario where ferreteria_id = ?", Long.class, f);

        assertThatThrownBy(() -> jdbc.update("insert into movimiento_caja (ferreteria_id, caja_id, tipo, importe, "
                + "motivo, usuario_id, fecha) values (?, ?, 'GASTO', 0, 'x', ?, now(6))", f, caja, usuario))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_movimiento_caja_importe");
    }

    @Test
    @DisplayName("un comprobante insertado como antes de V10, sin caja_id, entra con la caja en NULL")
    void comprobanteSinCaja() {
        long f = ferreteria("30-71010004-4");
        Long usuario = jdbc.queryForObject("select min(id) from usuario where ferreteria_id = ?", Long.class, f);
        jdbc.update("insert into comprobante (ferreteria_id, tipo, estado, numero, clave_idempotencia, usuario_id, neto, "
                + "iva, total) values (?, 'VENTA', 'SIN_FACTURAR', 1, 'v10-sin-caja', ?, 100, 21, 121)", f, usuario);

        assertThat(jdbc.queryForObject("select caja_id from comprobante where ferreteria_id = ?", Long.class, f))
                .isNull();
        assertThat(jdbc.queryForObject("select total from comprobante where ferreteria_id = ?", BigDecimal.class, f))
                .isEqualByComparingTo("121");
    }
}
