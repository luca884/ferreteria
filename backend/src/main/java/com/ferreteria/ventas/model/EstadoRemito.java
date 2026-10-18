package com.ferreteria.ventas.model;

/**
 * Estado del remito (RM-002). EMITIDO al emitirlo; FACTURADO solo lo pone la venta hecha desde el remito y ANULADO la
 * anulacion que devuelve el stock. Los dos ultimos son finales. Se guarda como texto sin CHECK, como el del comprobante.
 */
public enum EstadoRemito {
    EMITIDO,
    FACTURADO,
    ANULADO
}
