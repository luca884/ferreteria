package com.ferreteria.facturacion.wsfe;

import java.util.List;

/**
 * La respuesta de {@code FECAESolicitar} ({@code FECAESolicitarResult}, manual 4.8 p. 36-39). Con un solo comprobante, el
 * {@code Resultado} de la cabecera y el del detalle coinciden. Cada falla va donde la pone el manual (p. 25 y 76, ejemplos 3
 * y 4 en p. 83-86):
 * <ul>
 *   <li>una falla del pedido o de la autenticación (cabecera, {@code Auth}): en {@code errors}, con R en la cabecera y
 *       {@code feDetResp} null;</li>
 *   <li>una validación excluyente del comprobante: en las {@code Observaciones} del detalle, con R y sin CAE;</li>
 *   <li>una validación no excluyente: en las {@code Observaciones} del detalle, con A y CAE.</li>
 * </ul>
 * Quien la lee decide por el {@code Resultado} de la cabecera, y guarda {@code Errors} y {@code Observaciones} tal cual.
 *
 * @param feCabResp la cabecera
 * @param feDetResp el detalle del comprobante; null si el pedido falló antes de validarlo
 * @param errors {@code Errors}, cada uno {@code Err{Code, Msg}}
 */
public record FeCaeResponse(FeCabResp feCabResp, FeCaeDetResponse feDetResp, List<Err> errors) {

    public FeCaeResponse {
        errors = Campos.lista(errors);
    }
}
