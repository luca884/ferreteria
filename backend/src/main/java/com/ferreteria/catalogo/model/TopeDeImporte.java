package com.ferreteria.catalogo.model;

import java.math.BigDecimal;

/** Lo que entra en una columna DECIMAL(15,2) de plata (costo, precio final): hasta 13 digitos enteros. */
public final class TopeDeImporte {

    public static final int MAXIMO_DIGITOS_ENTEROS = 13;

    private TopeDeImporte() {
    }

    public static boolean entra(BigDecimal valor) {
        return digitosEnteros(valor) <= MAXIMO_DIGITOS_ENTEROS;
    }

    private static int digitosEnteros(BigDecimal valor) {
        return Math.max(0, valor.precision() - valor.scale());
    }
}
