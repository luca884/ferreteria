package com.ferreteria.facturacion.wsfe;

import java.time.LocalDate;

/**
 * Un comprobante asociado ({@code CbteAsoc}, manual 4.8 p. 31): el que ajusta una nota de crédito o de débito.
 *
 * @param tipo {@code Tipo}, Int (3)
 * @param ptoVta {@code PtoVta}, Int (5)
 * @param nro {@code Nro}, Long (8)
 * @param cuit {@code Cuit}, String (11), opcional (null: la CUIT emisora)
 * @param cbteFch {@code CbteFch}, String (8) yyyymmdd, opcional
 */
public record CbteAsoc(int tipo, int ptoVta, long nro, String cuit, LocalDate cbteFch) {

    public CbteAsoc {
        Campos.digitos("CbteAsoc.Tipo", tipo, 3);
        Campos.digitos("CbteAsoc.PtoVta", ptoVta, 5);
        Campos.digitos("CbteAsoc.Nro", nro, 8);
        if (cuit != null) {
            Campos.texto("CbteAsoc.Cuit", cuit, 11);
        }
    }
}
