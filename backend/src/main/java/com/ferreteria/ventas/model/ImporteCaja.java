package com.ferreteria.ventas.model;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Valida la plata de la caja (CU-10): el efectivo inicial y el contado (cero vale) y el importe de un movimiento (mayor
 * que cero). Mismo tope que el resto de la plata, DECIMAL(15,2), y hasta 2 decimales. Los mensajes van tal cual al
 * usuario.
 */
public final class ImporteCaja {

    private ImporteCaja() {
    }

    /** Efectivo que se cuenta (inicial o contado al cerrar): cero o mas. {@code nombre} es "efectivo inicial", etc. */
    public static Optional<String> validarEfectivo(String nombre, BigDecimal valor) {
        if (valor == null) {
            return Optional.of("Cargá el " + nombre + ".");
        }
        if (valor.signum() < 0) {
            return Optional.of("El " + nombre + " no puede ser negativo.");
        }
        return validarForma("El " + nombre, valor);
    }

    /** Importe de un ingreso, gasto o retiro: mayor que cero. */
    public static Optional<String> validarImporte(BigDecimal valor) {
        if (valor == null) {
            return Optional.of("Cargá el importe.");
        }
        if (valor.signum() <= 0) {
            return Optional.of("El importe tiene que ser mayor que cero.");
        }
        return validarForma("El importe", valor);
    }

    private static Optional<String> validarForma(String sujeto, BigDecimal valor) {
        if (Math.max(valor.stripTrailingZeros().scale(), 0) > 2) {
            return Optional.of(sujeto + " admite hasta 2 decimales.");
        }
        if (valor.compareTo(LimitesVenta.IMPORTE_MAXIMO) > 0) {
            return Optional.of(sujeto + " es demasiado grande: el máximo es $ 9.999.999.999.999,99.");
        }
        return Optional.empty();
    }
}
