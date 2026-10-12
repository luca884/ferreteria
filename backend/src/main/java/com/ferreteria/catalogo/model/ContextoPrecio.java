package com.ferreteria.catalogo.model;

import java.math.BigDecimal;

/**
 * Datos externos al articulo que necesita el calculo de su precio. Todos los margenes son
 * porcentajes.
 *
 * @param margenRubro      margen del rubro del articulo; null solo si el articulo no tiene rubro
 * @param margenDefecto    margen por defecto de la ferreteria
 * @param redondeoMultiplo multiplo al que se redondea hacia arriba el precio final
 */
public record ContextoPrecio(BigDecimal margenRubro, BigDecimal margenDefecto, BigDecimal redondeoMultiplo) {
}
