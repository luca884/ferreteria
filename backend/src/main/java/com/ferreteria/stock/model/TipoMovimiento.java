package com.ferreteria.stock.model;

/** Tipo de movimiento de stock. Se guarda como texto sin CHECK: CU-09 suma valores (COMPRA, MERMA...). */
public enum TipoMovimiento {
    VENTA,
    AJUSTE,
    REMITO,
    ANULACION_REMITO,
    /** Entrada por una compra al proveedor (CU-13). */
    COMPRA,
    /**
     * Entrada por la nota de credito o la anulacion interna de una venta (CU-08): repone todo lo vendido, tambien lo que
     * salio por el remito de origen.
     */
    NOTA_CREDITO
}
