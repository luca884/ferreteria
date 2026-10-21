package com.ferreteria.catalogo.model;

/** Que provoco un cambio de costo o de precio (queda en el historial de precios). */
public enum OrigenPrecio {
    IMPORTACION,
    MASIVO,
    MANUAL,
    /** El precio de un articulo en dolares cambio porque cambio la cotizacion (recalculo diario o manual). */
    COTIZACION,
    /** Una compra (CU-13) piso el costo con el del renglon y se recalculo el precio. */
    COMPRA
}
