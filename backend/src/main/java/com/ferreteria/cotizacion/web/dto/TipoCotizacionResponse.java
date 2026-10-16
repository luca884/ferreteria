package com.ferreteria.cotizacion.web.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Un tipo de cotizacion que la ferreteria eligio ver. {@code actualizadaEn} es la fecha del mercado segun la fuente;
 * {@code obtenidaEn}, cuando el sistema lo obtuvo; {@code desactualizada}, si ese valor lleva mas de 1 hora sin
 * renovarse (cada tipo tiene la suya: un tipo que ninguna fuente trae se queda con su valor viejo). Sin valor
 * guardado, los importes y las fechas vienen vacios.
 */
public record TipoCotizacionResponse(
        String codigo,
        String nombre,
        BigDecimal compra,
        BigDecimal venta,
        String fuente,
        Instant actualizadaEn,
        Instant obtenidaEn,
        boolean desactualizada,
        boolean esDePrecios) {}
