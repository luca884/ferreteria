package com.ferreteria.stock.model;

import java.math.BigDecimal;

/**
 * Ajuste manual de stock (CU-09): el Dueno carga el stock REAL que conto y el sistema calcula la diferencia
 * contra el stock que tiene registrado. Dominio puro. UNIDAD se cuenta en enteros; METRO, KILO y LITRO
 * admiten hasta 3 decimales (la escala de la columna). Los mensajes van tal cual al usuario.
 */
public final class AjusteStock {

    /** DECIMAL(15, 3): hasta 12 digitos enteros. */
    private static final BigDecimal MAXIMO = new BigDecimal("999999999999.999");

    private AjusteStock() {
    }

    /** Valida el stock real contado, sin mirar el actual. Lanza IllegalArgumentException con el mensaje al usuario. */
    public static void validarStockReal(BigDecimal stockReal, boolean admiteDecimales) {
        if (stockReal == null) {
            throw new IllegalArgumentException("El stock real es obligatorio.");
        }
        if (stockReal.signum() < 0) {
            throw new IllegalArgumentException("El stock real no puede ser negativo.");
        }
        if (stockReal.compareTo(MAXIMO) > 0) {
            throw new IllegalArgumentException("El stock real es demasiado grande: el máximo es 999.999.999.999,999.");
        }
        int decimales = Math.max(stockReal.stripTrailingZeros().scale(), 0);
        if (!admiteDecimales && decimales > 0) {
            throw new IllegalArgumentException(
                    "Este artículo se cuenta por unidad: el stock real tiene que ser un número entero.");
        }
        if (decimales > 3) {
            throw new IllegalArgumentException("El stock real admite hasta 3 decimales.");
        }
    }

    /**
     * Cuanto hay que sumar al stock actual para llegar al real (con signo). Si son iguales no hay ajuste:
     * lanza IllegalArgumentException, igual que con un stock real invalido.
     */
    public static BigDecimal diferencia(BigDecimal stockActual, BigDecimal stockReal, boolean admiteDecimales) {
        validarStockReal(stockReal, admiteDecimales);
        BigDecimal diferencia = stockReal.subtract(stockActual);
        if (diferencia.signum() == 0) {
            throw new IllegalArgumentException("El stock real es igual al actual: no hay nada que ajustar.");
        }
        return diferencia;
    }
}
