package com.ferreteria.catalogo.model;

/** Que hace una actualizacion masiva (CU-05). */
public enum ModoMasivo {
    /** Cambia el costo en un porcentaje (positivo sube, negativo baja) y recalcula el precio. */
    COSTO_PORCENTAJE,
    /** Suma un monto al costo (positivo sube, negativo baja), en la moneda de cada articulo, y recalcula el precio. */
    COSTO_MONTO,
    /** Fija el margen propio de cada articulo y recalcula el precio. */
    MARGEN
}
