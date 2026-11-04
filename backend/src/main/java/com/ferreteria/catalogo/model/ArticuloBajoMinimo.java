package com.ferreteria.catalogo.model;

import com.ferreteria.catalogo.api.UltimasCompras.UltimaCompra;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Una fila del listado de articulos bajo el stock minimo (CU-09, G72). Rubro y proveedor son null si el articulo no
 * tiene. A reponer es minimo menos stock, la columna «Mínimo a reponer» de GDS. El codigo del proveedor y la ultima
 * compra (fecha y proveedor) son de mejoras-ferreteros-bloque-2 (S5, S6); null si no hay.
 */
public record ArticuloBajoMinimo(Long articuloId, String codigoInterno, String codigoProveedor, String descripcion,
                                 String rubro, String proveedor, BigDecimal costo, String moneda, String unidad,
                                 BigDecimal stockActual, BigDecimal stockMinimo, BigDecimal aReponer,
                                 LocalDate ultimaCompraFecha, String ultimaCompraProveedor) {

    public static ArticuloBajoMinimo de(Long articuloId, String codigoInterno, String codigoProveedor,
                                        String descripcion, String rubro, String proveedor, BigDecimal costo,
                                        String moneda, String unidad, BigDecimal stockActual, BigDecimal stockMinimo) {
        return new ArticuloBajoMinimo(articuloId, codigoInterno, codigoProveedor, descripcion, rubro, proveedor, costo,
                moneda, unidad, stockActual, stockMinimo, stockMinimo.subtract(stockActual), null, null);
    }

    /** La misma fila con su ultima compra; sin compra (null) queda igual. */
    public ArticuloBajoMinimo conUltimaCompra(UltimaCompra compra) {
        if (compra == null) return this;
        return new ArticuloBajoMinimo(articuloId, codigoInterno, codigoProveedor, descripcion, rubro, proveedor, costo,
                moneda, unidad, stockActual, stockMinimo, aReponer, compra.fecha(), compra.proveedor());
    }
}
