package com.ferreteria.stock.model;

/** Tipo de movimiento de stock. Se guarda como texto sin CHECK: CU-09 suma valores (COMPRA, MERMA...). */
public enum TipoMovimiento {
    VENTA,
    AJUSTE,
    REMITO,
    ANULACION_REMITO
}
