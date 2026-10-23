package com.ferreteria.catalogo.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * La configuracion de precios ya guardada, cuantos articulos cambiaron de precio y los avisos de los que se saltearon
 * porque sus ajustes los dejarian en 0 o menos.
 */
public record ConfiguracionPreciosGuardadaResponse(
        BigDecimal margenDefectoPorcentaje, BigDecimal redondeoMultiplo, int articulosRecalculados, List<String> avisos) {
}
