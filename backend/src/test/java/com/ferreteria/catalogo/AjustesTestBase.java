package com.ferreteria.catalogo;

import com.ferreteria.cotizacion.CotizacionTestBase;
import java.math.BigDecimal;

/**
 * Siembra para los tests HTTP del corte 3 (desglose y ajustes): proveedores, rubros y articulos que vienen de una
 * lista, con la base guardada (bonificaciones y si la lista incluye IVA) y los ajustes que se indiquen. Inserta
 * por SQL, sin pasar por la API, para fijar costo y precio guardados tal cual.
 */
abstract class AjustesTestBase extends CotizacionTestBase {

    protected Long proveedor(Long ferreteriaId, String nombre) {
        jdbc.update("insert into proveedor (ferreteria_id, nombre) values (?, ?)", ferreteriaId, nombre);
        return jdbc.queryForObject("select id from proveedor where ferreteria_id = ? and nombre = ?", Long.class,
                ferreteriaId, nombre);
    }

    protected Long rubro(Long ferreteriaId, String nombre, String margen) {
        jdbc.update("insert into rubro (ferreteria_id, nombre, margen_porcentaje) values (?, ?, ?)", ferreteriaId,
                nombre, new BigDecimal(margen));
        return jdbc.queryForObject("select id from rubro where ferreteria_id = ? and nombre = ?", Long.class,
                ferreteriaId, nombre);
    }

    /** Un articulo cargado a mano: sin precio de lista, sin proveedor y sin base. */
    protected Long manual(Long ferreteriaId, String codigo, String costo, String precioFinal) {
        return articulo(ferreteriaId, codigo, "ARS", costo, precioFinal, true);
    }

    /**
     * Un articulo importado: precio de lista, base guardada y ajustes. Moneda ARS, IVA 21 y margen propio 30 salvo que
     * el test lo cambie despues. Los arreglos son {@code {b1, b2, b3}} y {@code {a1, a2, a3}}; un null es "sin valor".
     */
    protected Long importado(Long ferreteriaId, String codigo, Long proveedorId, String lista, boolean incluyeIva,
                             String[] bonificaciones, String[] ajustes, String costo, String precioFinal) {
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, proveedor_id, costo, moneda, "
                + "margen_porcentaje, precio_final, precio_lista_proveedor, lista_incluye_iva, bonificacion1, "
                + "bonificacion2, bonificacion3, ajuste1, ajuste2, ajuste3) "
                + "values (?, ?, ?, ?, ?, 'ARS', 30.00, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                ferreteriaId, codigo, "Articulo " + codigo, proveedorId, new BigDecimal(costo),
                new BigDecimal(precioFinal), new BigDecimal(lista), incluyeIva, bd(bonificaciones[0]),
                bd(bonificaciones[1]), bd(bonificaciones[2]), bd(ajustes[0]), bd(ajustes[1]), bd(ajustes[2]));
        return jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?",
                Long.class, ferreteriaId, codigo);
    }

    protected static String[] valores(String a, String b, String c) {
        return new String[] {a, b, c};
    }

    protected static final String[] NADA = {null, null, null};

    protected static BigDecimal bd(String valor) {
        return valor == null ? null : new BigDecimal(valor);
    }

    /** Lo que importa del articulo en la base, para probar que una operacion no lo toco. */
    protected java.util.Map<String, Object> filaDe(Long articuloId) {
        return jdbc.queryForMap("select costo, precio_final, precio_lista_proveedor, margen_porcentaje, moneda, "
                + "ajuste1, ajuste2, ajuste3, bonificacion1, bonificacion2, bonificacion3, lista_incluye_iva, "
                + "proveedor_id, rubro_id, ultima_importacion_id, stock_actual, activo "
                + "from articulo where id = ?", articuloId);
    }

    protected long historialDelArticulo(Long articuloId) {
        return jdbc.queryForObject("select count(*) from historial_precio where articulo_id = ?", Long.class,
                articuloId);
    }
}
