package com.ferreteria.facturacion.arca;

import com.ferreteria.facturacion.wsfe.Err;
import java.util.List;

/**
 * La respuesta de {@code FEParamGetPtosVenta} ({@code FEParamGetPtosVentaResult}, tipo {@code FEPtoVentaResponse}).
 *
 * @param resultGet los puntos de venta ({@code ResultGet/PtoVenta}); vacío si no vino ninguno
 * @param errors {@code Errors}
 */
public record FeParamGetPtosVentaResponse(List<PtoVenta> resultGet, List<Err> errors) {

    public FeParamGetPtosVentaResponse {
        resultGet = resultGet == null ? List.of() : List.copyOf(resultGet);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
