package com.ferreteria.compras;

import com.ferreteria.support.IntegrationTestBase;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Datos de prueba de las compras (CU-13) con su propia ferreteria por test: margen 30 %, sin redondeo, IVA 21 %. Las
 * ferreterias traen un CUIT valido (con su digito verificador), que es el que lee la factura en PDF.
 */
abstract class ComprasTestBase extends IntegrationTestBase {

    static final String AYER = LocalDate.now().minusDays(1).toString();

    record Tienda(long id, String cuit, String emailDueno, String emailVendedor) {}

    /** Un CUIT 30-xxxxxxxx-d valido, con guiones. */
    static String cuitValido() {
        int[] pesos = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};
        String base = "30" + "%08d".formatted(ThreadLocalRandom.current().nextInt(100_000_000));
        int suma = 0;
        for (int i = 0; i < pesos.length; i++) {
            suma += (base.charAt(i) - '0') * pesos[i];
        }
        int verificador = 11 - suma % 11 == 11 ? 0 : 11 - suma % 11;
        if (verificador == 10) {
            return cuitValido();
        }
        return base.substring(0, 2) + "-" + base.substring(2) + "-" + verificador;
    }

    Tienda tienda() {
        String sufijo = UUID.randomUUID().toString().substring(0, 8);
        String cuit = cuitValido();
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva, margen_defecto_porcentaje, "
                + "redondeo_multiplo) values (?, ?, 'RESPONSABLE_INSCRIPTO', 30.00, 0.00)", "Compras " + sufijo, cuit);
        long id = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        String hash = jdbc.queryForObject(
                "select password_hash from usuario where email = 'vendedor@tornillo.demo'", String.class);
        String dueno = "dueno-" + sufijo + "@compras.demo";
        String vendedor = "vendedor-" + sufijo + "@compras.demo";
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, ?, 'Dueno Compras', 'DUENO')", id, dueno, hash);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, ?, 'Vendedor Compras', 'VENDEDOR')", id, vendedor, hash);
        return new Tienda(id, cuit, dueno, vendedor);
    }

    long proveedor(long ferreteriaId, String nombre, String cuit) {
        jdbc.update("insert into proveedor (ferreteria_id, nombre, cuit, activo) values (?, ?, ?, true)",
                ferreteriaId, nombre, cuit);
        return jdbc.queryForObject("select id from proveedor where ferreteria_id = ? and nombre = ?",
                Long.class, ferreteriaId, nombre);
    }

    /** Un articulo activo por unidad del proveedor (puede ser null) con ese codigo de proveedor (puede ser null). */
    long articulo(long ferreteriaId, String codigo, String descripcion, String costo, String precio,
                  Long proveedorId, String codigoProveedor) {
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, unidad, "
                        + "alicuota_iva, stock_actual, activo, proveedor_id, codigo_proveedor) "
                        + "values (?, ?, ?, ?, ?, 'UNIDAD', 'IVA_21', 0, true, ?, ?)",
                ferreteriaId, codigo, descripcion, costo, precio, proveedorId, codigoProveedor);
        return jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?",
                Long.class, ferreteriaId, codigo);
    }
}
