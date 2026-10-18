package com.ferreteria.ventas.model;

/** Movimientos de plata de la caja que no son ventas (CU-10). INGRESO suma al efectivo; GASTO y RETIRO restan. */
public enum TipoMovimientoCaja {
    /** Ingresos varios (por ejemplo, cambio que trae el dueño). */
    INGRESO,
    /** Gastos varios pagados con la plata de la caja. */
    GASTO,
    /** El dueño saca plata de la caja. Solo lo carga el Dueño. */
    RETIRO
}
