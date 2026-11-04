package com.ferreteria.catalogo.model;

/** Como fija la oferta el precio de venta (ofertas, S1): un precio fijo o un descuento en % o en $ sobre el normal. */
public enum TipoOferta {
    PRECIO_FIJO,
    DESCUENTO_PORCENTAJE,
    DESCUENTO_MONTO
}
