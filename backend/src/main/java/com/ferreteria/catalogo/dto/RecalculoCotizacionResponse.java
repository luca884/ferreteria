package com.ferreteria.catalogo.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Resultado de recalcular los precios de los articulos en dolares.
 *
 * @param cotizacion           el dolar con el que se calcularon (pesos por dolar)
 * @param articulosRecalculados cuantos articulos cambiaron de precio; los que quedaron igual no cuentan
 * @param avisos                uno por articulo que conserva su precio porque sus ajustes sobre el precio final lo
 *                              dejarian en 0 o menos (S7); vacio si no hubo ninguno
 */
public record RecalculoCotizacionResponse(BigDecimal cotizacion, int articulosRecalculados, List<String> avisos) {
}
