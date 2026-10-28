package com.ferreteria.facturacion.wsfe;

/**
 * Una observación del comprobante ({@code Obs} de {@code Observaciones}, manual 4.8 p. 39). Con {@code Resultado} A es una
 * validación no excluyente: el comprobante sale con CAE igual. Con R es el motivo del rechazo (el manual llama «Obs» a las
 * dos cosas).
 *
 * @param code {@code Code}, Int (5)
 * @param msg {@code Msg}, String (255)
 */
public record Obs(int code, String msg) {
}
