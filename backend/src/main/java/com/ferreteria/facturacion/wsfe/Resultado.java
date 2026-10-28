package com.ferreteria.facturacion.wsfe;

/**
 * El {@code Resultado} de un comprobante (String (1), manual 4.8 p. 37-38). Con un solo comprobante por pedido no hay
 * {@code P} (parcial): ese valor solo aparece en la cabecera de un lote.
 */
public enum Resultado {
    /** Aprobado: con CAE y su vencimiento, y quizás con observaciones. */
    A,
    /** Rechazado: sin CAE; el número no se consumió. */
    R
}
