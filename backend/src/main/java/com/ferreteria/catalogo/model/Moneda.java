package com.ferreteria.catalogo.model;

/**
 * Moneda en la que un articulo guarda su costo. El precio final siempre se guarda en pesos. El euro y el
 * real solo se muestran en la cotizacion: no son monedas de un articulo.
 */
public enum Moneda {
    ARS,
    USD
}
