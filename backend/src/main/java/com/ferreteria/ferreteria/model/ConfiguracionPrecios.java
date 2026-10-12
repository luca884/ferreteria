package com.ferreteria.ferreteria.model;

import java.math.BigDecimal;

/**
 * Configuracion de precios de una ferreteria: margen por defecto (porcentaje) y multiplo al que
 * se redondea hacia arriba el precio final.
 */
public record ConfiguracionPrecios(BigDecimal margenDefectoPorcentaje, BigDecimal redondeoMultiplo) {
}
