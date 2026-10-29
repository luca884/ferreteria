package com.ferreteria.ventas.model;

/**
 * Tipo de comprobante. Se guarda como texto sin CHECK: la fase 5 suma valores. NOTA_CREDITO (CU-08, V37) es la nota de
 * credito y tambien la anulacion interna (la que lleva motivo): anula la venta asociada, y los lectores la restan.
 */
public enum TipoComprobante {
    VENTA,
    NOTA_CREDITO
}
