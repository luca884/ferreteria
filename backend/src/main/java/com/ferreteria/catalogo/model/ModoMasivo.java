package com.ferreteria.catalogo.model;

/** Que hace una actualizacion masiva (CU-05). */
public enum ModoMasivo {
    /** Cambia el costo en un porcentaje (positivo sube, negativo baja) y recalcula el precio. */
    COSTO_PORCENTAJE,
    /** Suma un monto al costo (positivo sube, negativo baja), en la moneda de cada articulo, y recalcula el precio. */
    COSTO_MONTO,
    /** Fija el margen propio de cada articulo y recalcula el precio. */
    MARGEN,
    /** Cambia el precio final en un porcentaje; el articulo queda con precio manual (CU-14). No toca el costo. */
    PRECIO_PORCENTAJE,
    /** Suma un monto en pesos al precio final; el articulo queda con precio manual (CU-14). No toca el costo. */
    PRECIO_MONTO,
    /**
     * Pasa los articulos a otro rubro (que ya existe) y recalcula el precio con el margen de ese rubro. No lleva valor:
     * lleva el rubro destino. Los de margen propio o precio manual cambian de rubro pero no de precio.
     */
    CAMBIAR_RUBRO;

    /** Los modos que cambian el precio final y lo dejan manual. */
    public boolean esDePrecioFinal() {
        return this == PRECIO_PORCENTAJE || this == PRECIO_MONTO;
    }
}
