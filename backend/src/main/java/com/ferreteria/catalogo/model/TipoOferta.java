package com.ferreteria.catalogo.model;

import com.ferreteria.common.exception.MensajeDeValorInvalido;

/** Como fija la oferta el precio de venta (ofertas, S1): un precio fijo o un descuento en % o en $ sobre el normal. */
@MensajeDeValorInvalido(TipoOferta.MENSAJE_INVALIDO)
public enum TipoOferta {
    PRECIO_FIJO,
    DESCUENTO_PORCENTAJE,
    DESCUENTO_MONTO;

    public static final String MENSAJE_INVALIDO =
            "Elegí si la oferta es un precio fijo, un descuento en % o un descuento en $.";
}
