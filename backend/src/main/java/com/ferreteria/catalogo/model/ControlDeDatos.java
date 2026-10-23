package com.ferreteria.catalogo.model;

/**
 * Los «Controles de datos» de GDS (G81) que se aplican al catalogo, mas los de GDS-18: artículos con costo 0, sin rubro,
 * con margen negativo (costo en pesos mayor que el precio sin IVA) o con IVA 0.
 */
public enum ControlDeDatos {
    COSTO_CERO,
    SIN_RUBRO,
    MARGEN_NEGATIVO,
    IVA_CERO
}
