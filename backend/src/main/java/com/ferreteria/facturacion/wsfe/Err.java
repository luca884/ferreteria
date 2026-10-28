package com.ferreteria.facturacion.wsfe;

/**
 * Un error de la respuesta ({@code Err} de {@code Errors}, manual 4.8 p. 39): una falla del pedido o de la autenticación,
 * antes de validar el comprobante (en {@code FECAESolicitar}, el rechazo de un comprobante va en sus {@code Observaciones}).
 *
 * @param code {@code Code}, Int
 * @param msg {@code Msg}, String
 */
public record Err(int code, String msg) {
}
