package com.ferreteria.ventas.model;

/**
 * Estado del presupuesto (PP-002). "Vencido" no es un estado: se calcula con la fecha de vencimiento. FACTURADO solo
 * lo pone la venta hecha desde el presupuesto. Se guarda como texto sin CHECK, como el del comprobante.
 */
public enum EstadoPresupuesto {
    PENDIENTE,
    ACEPTADO,
    RECHAZADO,
    FACTURADO
}
