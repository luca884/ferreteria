package com.ferreteria.catalogo.model;

import java.math.BigDecimal;

/**
 * Una fila del listado de articulos bajo el stock minimo (CU-09, G72). Rubro y proveedor son null si el articulo no
 * tiene. A reponer es minimo menos stock, la columna «Mínimo a reponer» de GDS.
 */
public record ArticuloBajoMinimo(Long articuloId, String codigoInterno, String descripcion, String rubro,
                                 String proveedor, BigDecimal costo, String moneda, String unidad,
                                 BigDecimal stockActual, BigDecimal stockMinimo, BigDecimal aReponer) {

    public static ArticuloBajoMinimo de(Long articuloId, String codigoInterno, String descripcion, String rubro,
                                        String proveedor, BigDecimal costo, String moneda, String unidad,
                                        BigDecimal stockActual, BigDecimal stockMinimo) {
        return new ArticuloBajoMinimo(articuloId, codigoInterno, descripcion, rubro, proveedor, costo, moneda, unidad,
                stockActual, stockMinimo, stockMinimo.subtract(stockActual));
    }
}
