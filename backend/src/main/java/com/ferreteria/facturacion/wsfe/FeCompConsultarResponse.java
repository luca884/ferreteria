package com.ferreteria.facturacion.wsfe;

import java.util.List;

/**
 * La respuesta de {@code FECompConsultar} ({@code FECompConsultarResult}, manual 4.8 p. 190-195).
 *
 * @param resultGet el comprobante, o null si ARCA no lo tiene (entonces {@code errors} dice por qué)
 * @param errors {@code Errors}
 */
public record FeCompConsultarResponse(ResultGet resultGet, List<Err> errors) {

    public FeCompConsultarResponse {
        errors = Campos.lista(errors);
    }
}
