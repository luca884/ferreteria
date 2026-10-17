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

    /** Valida la cantidad a sumar (+) o restar (-) del modo CANTIDAD. Lanza IllegalArgumentException con el mensaje al usuario. */
    public static void validarCantidad(BigDecimal cantidad, boolean admiteDecimales) {
        if (cantidad == null) {
            throw new IllegalArgumentException("La cantidad es obligatoria.");
        }
        if (cantidad.signum() == 0) {
            throw new IllegalArgumentException("La cantidad no puede ser cero.");
        }
        if (cantidad.abs().compareTo(MAXIMO) > 0) {
            throw new IllegalArgumentException("La cantidad es demasiado grande: el máximo es 999.999.999.999,999.");
        }
        int decimales = Math.max(cantidad.stripTrailingZeros().scale(), 0);
        if (!admiteDecimales && decimales > 0) {
            throw new IllegalArgumentException(
                    "Este artículo se cuenta por unidad: la cantidad tiene que ser un número entero.");
        }
        if (decimales > 3) {
            throw new IllegalArgumentException("La cantidad admite hasta 3 decimales.");
        }
    }

    /**
     * Stock que quedaria al aplicar la cantidad (con signo) al stock vigente. Sacar nunca lo deja negativo:
     * sacar de mas lanza IllegalArgumentException ("No podés sacar 5: hay 3 unidades."). Sumar siempre se acepta,
     * aunque el stock siga negativo: la venta sin stock lo deja asi y un ingreso parcial tiene que poder cargarse.
     */
    public static BigDecimal stockResultante(BigDecimal stockVigente, BigDecimal cantidad, String unidad) {
        BigDecimal resultante = stockVigente.add(cantidad);
        if (cantidad.signum() < 0 && resultante.signum() < 0) {
            throw new IllegalArgumentException("No podés sacar " + texto(cantidad.negate()) + ": hay "
                    + texto(stockVigente) + " " + nombreDeUnidad(unidad, stockVigente) + ".");
        }
        if (resultante.compareTo(MAXIMO) > 0) {
            throw new IllegalArgumentException("El stock quedaría demasiado grande: el máximo es 999.999.999.999,999.");
        }
        return resultante;
    }

    private static String texto(BigDecimal valor) {
        BigDecimal limpio = valor.stripTrailingZeros();
        return (limpio.signum() == 0 ? "0" : limpio.toPlainString()).replace('.', ',');
    }

    private static String nombreDeUnidad(String unidad, BigDecimal cantidad) {
        boolean singular = cantidad.compareTo(BigDecimal.ONE) == 0;
        return switch (unidad) {
            case "METRO" -> singular ? "metro" : "metros";
            case "KILO" -> singular ? "kilo" : "kilos";
            case "LITRO" -> singular ? "litro" : "litros";
            default -> singular ? "unidad" : "unidades";
        };
    }
}
