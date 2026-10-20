package com.ferreteria.ventas.model;

/**
 * Estado de un recibo de cobranza (CC-069). Nace {@code EMITIDO} y solo puede pasar a {@code ANULADO}, una vez y sin vuelta
 * atrás: anular un cobro no se deshace, se vuelve a cobrar con un recibo nuevo (CC-067). Los nombres van en una columna
 * VARCHAR(10) con CHECK.
 */
public enum EstadoRecibo {
    EMITIDO,
    ANULADO
}
