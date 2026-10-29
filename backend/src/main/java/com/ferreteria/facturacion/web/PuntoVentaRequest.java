package com.ferreteria.facturacion.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** El punto de venta habilitado en ARCA para factura electrónica (web service), de 1 a 99998 (S2). */
public record PuntoVentaRequest(
        @NotNull(message = "Indicá el punto de venta.")
        @Min(value = 1, message = "El punto de venta va de 1 a 99998.")
        @Max(value = 99998, message = "El punto de venta va de 1 a 99998.")
        Integer puntoVenta) {
}
