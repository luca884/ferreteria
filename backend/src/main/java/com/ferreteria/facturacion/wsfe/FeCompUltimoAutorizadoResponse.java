package com.ferreteria.facturacion.wsfe;

import java.util.List;

/**
 * La respuesta de {@code FECompUltimoAutorizado} ({@code FECompUltimoAutorizadoResult}, manual 4.8 p. 126-128).
 *
 * @param ptoVta {@code PtoVta}, Int (5)
 * @param cbteTipo {@code CbteTipo}, Int (3)
 * @param cbteNro {@code CbteNro}, Long (8): el último autorizado de la serie; 0 si no tiene ninguno (inferido: el manual
 *     marca el campo opcional y no dice qué devuelve sin comprobantes)
 * @param errors {@code Errors}
 */
public record FeCompUltimoAutorizadoResponse(int ptoVta, int cbteTipo, long cbteNro, List<Err> errors) {

    public FeCompUltimoAutorizadoResponse {
        errors = Campos.lista(errors);
    }
}
