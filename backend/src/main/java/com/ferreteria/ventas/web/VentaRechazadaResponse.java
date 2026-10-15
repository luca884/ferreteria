package com.ferreteria.ventas.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ferreteria.ventas.service.VentaRechazadaException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Cuerpo de los rechazos 404, 409 y 422 de una venta (R-008 a R-011): los campos de {@code ApiError} mas el
 * {@code motivo}, los articulos involucrados y, solo en PRECIOS_CAMBIADOS, el total actual. Lo que no aplica
 * a un motivo no se serializa (el 404 solo trae los {@code articuloId} que mando el cliente).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VentaRechazadaResponse(
        int status, String message, Instant timestamp, String motivo, List<ArticuloResponse> articulos,
        BigDecimal total) {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ArticuloResponse(
            Long articuloId, String descripcion, BigDecimal precioMostrado, BigDecimal precioActual,
            BigDecimal totalRenglon) {}

    public static VentaRechazadaResponse de(VentaRechazadaException e) {
        return new VentaRechazadaResponse(e.motivo().estadoHttp(), e.getMessage(), Instant.now(), e.motivo().name(),
                e.articulos().stream()
                        .map(a -> new ArticuloResponse(a.articuloId(), a.descripcion(), a.precioMostrado(),
                                a.precioActual(), a.totalRenglon()))
                        .toList(),
                e.total());
    }
}
