package com.ferreteria.catalogo.model;

import java.math.BigDecimal;

/**
 * Una celda leida de una lista. {@code numero} viene solo de las celdas numericas de Excel; para el
 * resto es null y el valor esta en {@code texto}. El texto nunca es null. {@code porcentaje} es true cuando
 * el formato de la celda Excel es de porcentaje: 21 % se guarda como 0,21 y se ve 21 (ver {@link ParserPorcentaje}).
 */
public record Celda(String texto, BigDecimal numero, boolean porcentaje) {

    public static final Celda VACIA = new Celda("", null);

    public Celda {
        if (texto == null) {
            texto = "";
        }
    }

    public Celda(String texto, BigDecimal numero) {
        this(texto, numero, false);
    }

    public boolean vacia() {
        return numero == null && texto.isBlank();
    }
}
