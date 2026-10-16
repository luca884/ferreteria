package com.ferreteria.catalogo.model;

import java.math.BigDecimal;

/**
 * Datos externos al articulo que necesita el calculo de su precio. Todos los margenes son
 * porcentajes.
 *
 * @param margenRubro      margen del rubro del articulo; null solo si el articulo no tiene rubro
 * @param margenDefecto    margen por defecto de la ferreteria
 * @param redondeoMultiplo multiplo al que se redondea hacia arriba el precio final
 * @param cotizacionUsd    pesos por dolar con los que se calculan los articulos en USD (el dolar vigente de la
 *                         ferreteria); null si no se necesita o no hay ninguno
 */
public record ContextoPrecio(
        BigDecimal margenRubro, BigDecimal margenDefecto, BigDecimal redondeoMultiplo, BigDecimal cotizacionUsd) {

    /** Contexto sin cotizacion del dolar: sirve para calcular articulos en pesos. */
    public ContextoPrecio(BigDecimal margenRubro, BigDecimal margenDefecto, BigDecimal redondeoMultiplo) {
        this(margenRubro, margenDefecto, redondeoMultiplo, null);
    }

    /**
     * Pesos por unidad de la moneda: 1 para pesos; el dolar del contexto para USD.
     *
     * @throws IllegalStateException si la moneda es USD y el contexto no tiene dolar
     */
    public BigDecimal cotizacionPara(Moneda moneda) {
        if (moneda == Moneda.USD) {
            if (cotizacionUsd == null) {
                throw new IllegalStateException(
                        "No hay cotización del dólar para calcular el precio de un artículo en dólares.");
            }
            return cotizacionUsd;
        }
        return BigDecimal.ONE;
    }
}
