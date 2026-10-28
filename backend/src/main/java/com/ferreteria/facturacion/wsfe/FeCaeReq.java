package com.ferreteria.facturacion.wsfe;

/**
 * El pedido de {@code FECAESolicitar} ({@code FeCAEReq}, manual 4.8 p. 26): la cabecera y el detalle del único comprobante.
 *
 * @param feCabReq la cabecera, con {@code CantReg} 1, {@code PtoVta} y {@code CbteTipo}
 * @param feDetReq el detalle ({@code FECAEDetRequest}) del comprobante
 */
public record FeCaeReq(FeCabReq feCabReq, FeCaeDetRequest feDetReq) {

    public FeCaeReq {
        Campos.requerido("FeCabReq", feCabReq);
        Campos.requerido("FeDetReq", feDetReq);
    }
}
