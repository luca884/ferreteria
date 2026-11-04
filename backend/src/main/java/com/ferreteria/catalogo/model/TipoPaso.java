package com.ferreteria.catalogo.model;

/** Un paso del desglose del precio de un articulo, en el orden en que se aplican. */
public enum TipoPaso {
    /** El precio de lista del proveedor. */
    LISTA,
    /** La lista sin el IVA que trae incluido (solo si la lista lo incluye). */
    SIN_IVA,
    /** Una bonificacion del proveedor; el porcentaje positivo descuenta. */
    BONIFICACION,
    /** Un descuento o recargo del articulo; el porcentaje negativo descuenta. */
    AJUSTE,
    /** El costo guardado en el articulo. */
    COSTO,
    /** Un recargo del costo (CU-13); el importe es lo que suma al costo (negativo si descuenta) y el porcentaje, el del recargo. */
    RECARGO,
    /** El costo con todos sus recargos (CU-13): de este costo parten la cotizacion, el margen y el IVA. */
    COSTO_REAL,
    /** El costo pasado a pesos con la cotizacion (solo articulos en dolares). */
    COTIZACION,
    MARGEN,
    IVA,
    /** El redondeo hacia arriba al multiplo de la ferreteria; el importe del paso es el multiplo. */
    REDONDEO,
    /** El precio final guardado en el articulo. */
    PRECIO_FINAL
}
