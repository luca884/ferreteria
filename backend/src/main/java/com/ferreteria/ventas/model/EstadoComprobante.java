package com.ferreteria.ventas.model;

/**
 * Estado del comprobante. Se guarda como texto sin CHECK: la fase 5 suma valores (con CAE, etc.). SIN_FACTURAR es la
 * venta que todavia se va a facturar; NO_FACTURABLE es la que el cajero marco al cobrar para que no se facture nunca, y
 * no cambia de estado.
 */
public enum EstadoComprobante {
    SIN_FACTURAR,
    NO_FACTURABLE
}
