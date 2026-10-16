package com.ferreteria.catalogo.dto;

import java.math.BigDecimal;

/**
 * Resultado de recalcular los precios de los articulos en dolares.
 *
 * @param cotizacion           el dolar con el que se calcularon (pesos por dolar)
 * @param articulosRecalculados cuantos articulos cambiaron de precio; los que quedaron igual no cuentan
 */
public record RecalculoCotizacionResponse(BigDecimal cotizacion, int articulosRecalculados) {
}
