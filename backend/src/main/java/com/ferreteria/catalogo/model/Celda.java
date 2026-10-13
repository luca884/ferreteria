package com.ferreteria.catalogo.model;

import java.math.BigDecimal;

/**
 * Una celda leida de una lista. {@code numero} viene solo de las celdas numericas de Excel; para el
 * resto es null y el valor esta en {@code texto}. El texto nunca es null.
 */
public record Celda(String texto, BigDecimal numero) {

    public static final Celda VACIA = new Celda("", null);

    public Celda {
        if (texto == null) {
            texto = "";
        }
    }

    public boolean vacia() {
        return numero == null && texto.isBlank();
    }
}
