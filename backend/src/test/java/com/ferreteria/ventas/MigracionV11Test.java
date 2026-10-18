package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/** V11 (CU-07 corte 2): descuentos del renglon y global, con el subtotal y el bruto de las ventas anteriores. */
class MigracionV11Test extends IntegrationTestBase {

    private long ferreteria(String cuit) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) values ('Migracion V11', ?, "
                + "'RESPONSABLE_INSCRIPTO') on duplicate key update razon_social = razon_social", cuit);
        long id = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        String hash = jdbc.queryForObject(
                "select password_hash from usuario where email = 'vendedor@tornillo.demo'", String.class);
        jdbc.update("insert ignore into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, ?, 'Usuario V11', 'DUENO')", id, "v11-" + cuit + "@test.local", hash);
        return id;
    }

    private long usuario(long ferreteria) {
        return jdbc.queryForObject("select min(id) from usuario where ferreteria_id = ?", Long.class, ferreteria);
    }

    private void comprobante(long f, long usuario, String clave, String descuento) {
        jdbc.update("insert into comprobante (ferreteria_id, tipo, estado, numero, clave_idempotencia, usuario_id, neto, "
                + "iva, total, subtotal, descuento_porcentaje) values (?, 'VENTA', 'SIN_FACTURAR', 1, ?, ?, 100, 21, 121, "
                + "121, " + descuento + ")", f, clave, usuario);
    }

    @Test
    @DisplayName("un comprobante insertado sin descuento queda con el porcentaje en 0")
    void descuentoPorDefectoCero() {
        long f = ferreteria("30-71110001-1");
        long u = usuario(f);
        jdbc.update("insert into comprobante (ferreteria_id, tipo, estado, numero, clave_idempotencia, usuario_id, neto, "
                + "iva, total, subtotal) values (?, 'VENTA', 'SIN_FACTURAR', 1, 'v11-defecto', ?, 100, 21, 121, 121)", f, u);

        Map<String, Object> fila = jdbc.queryForMap(
                "select descuento_porcentaje, subtotal from comprobante where ferreteria_id = ?", f);

        assertThat((BigDecimal) fila.get("descuento_porcentaje")).isEqualByComparingTo("0");
        assertThat((BigDecimal) fila.get("subtotal")).isEqualByComparingTo("121");
    }

    @Test
    @DisplayName("el descuento del comprobante admite 99,99 y rechaza 100 y los negativos tambien en la base")
    void descuentoDelComprobanteAcotado() {
        long f = ferreteria("30-71110002-2");
        long u = usuario(f);

        comprobante(f, u, "v11-99", "99.99");
        assertThatThrownBy(() -> comprobante(f, u, "v11-100", "100.00"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_comprobante_descuento");
        assertThatThrownBy(() -> comprobante(f, u, "v11-neg", "-1.00"))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_comprobante_descuento");
    }

    @Test
    @DisplayName("el descuento del item tambien va de 0 a 99,99 en la base")
    void descuentoDelItemAcotado() {
        long f = ferreteria("30-71110003-3");
        long u = usuario(f);
        comprobante(f, u, "v11-item", "0");
        long comprobante = jdbc.queryForObject("select id from comprobante where ferreteria_id = ?", Long.class, f);
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, "
                + "alicuota_iva, unidad, stock_actual, stock_minimo, activo) values (?, 'V11-A', 'Tornillo', 1, 100, "
                + "'IVA_21', 'UNIDAD', 0, 0, true)", f);
        long articulo = jdbc.queryForObject("select id from articulo where ferreteria_id = ?", Long.class, f);
        String insertar = "insert into item_comprobante (ferreteria_id, comprobante_id, renglon, articulo_id, descripcion, "
                + "unidad, cantidad, precio_unitario, alicuota_iva, bruto, descuento_porcentaje, total, neto, iva) "
                + "values (?, ?, 1, ?, 'x', 'UNIDAD', 1, 100, 'IVA_21', 100, ?, 100, 82.64, 17.36)";

        jdbc.update(insertar, f, comprobante, articulo, new BigDecimal("99.99"));
        assertThatThrownBy(() -> jdbc.update(insertar, f, comprobante, articulo, new BigDecimal("100.00")))
                .isInstanceOf(DataAccessException.class).hasMessageContaining("ck_item_comprobante_descuento");
    }
}
