package com.ferreteria.stock.model;

/**
 * Como el Dueno carga el ajuste (CU-09): STOCK_REAL es el total contado (el sistema calcula la diferencia contra
 * el stock vigente) y CANTIDAD es lo que entra (+) o sale (-), que se suma al stock vigente al confirmar.
 */
public enum ModoAjuste {
    STOCK_REAL,
    CANTIDAD
}
