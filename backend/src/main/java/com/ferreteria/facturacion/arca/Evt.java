package com.ferreteria.facturacion.arca;

/**
 * Un evento de la respuesta ({@code Evt} de {@code Events}): un aviso de ARCA que no cambia el resultado (por ejemplo, una
 * novedad del servicio). Los puertos del motor no lo llevan; queda en {@link ConEventos}.
 *
 * @param code {@code Code}, Int
 * @param msg {@code Msg}, String
 */
public record Evt(int code, String msg) {
}
