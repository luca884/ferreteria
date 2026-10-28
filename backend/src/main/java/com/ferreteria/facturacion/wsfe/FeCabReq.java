package com.ferreteria.facturacion.wsfe;

/**
 * La cabecera de {@code FECAESolicitar} ({@code FeCabReq}, manual 4.8 p. 27). El motor pide de a un comprobante, así que
 * {@code CantReg} va siempre en 1.
 *
 * @param cantReg {@code CantReg}, Int (4): siempre 1
 * @param ptoVta {@code PtoVta}, Int (5)
 * @param cbteTipo {@code CbteTipo}, Int (3)
 */
public record FeCabReq(int cantReg, int ptoVta, int cbteTipo) {

    public FeCabReq {
        if (cantReg != 1) {
            throw new IllegalArgumentException("El motor pide de a un comprobante: CantReg va en 1, no en " + cantReg + ".");
        }
        Campos.digitos("PtoVta", ptoVta, 5);
        Campos.digitos("CbteTipo", cbteTipo, 3);
    }

    /** La cabecera de un pedido de un solo comprobante. */
    public static FeCabReq de(int ptoVta, int cbteTipo) {
        return new FeCabReq(1, ptoVta, cbteTipo);
    }
}
