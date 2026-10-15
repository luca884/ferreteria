package com.ferreteria.ventas.model;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Regla de la cantidad de un renglon (R-007): mayor que 0, hasta 3 decimales y entera si el articulo
 * se vende por unidad. Devuelve el mensaje para el usuario, o vacio si la cantidad sirve.
 */
public final class CantidadVendible {

    /** Escala de DECIMAL(15,3) de item_comprobante.cantidad y movimiento_stock.cantidad. */
    public static final int DECIMALES_MAXIMOS = 3;

    private CantidadVendible() {}

    public static Optional<String> validar(String descripcion, BigDecimal cantidad, boolean admiteDecimales) {
        if (cantidad.signum() <= 0) {
            return Optional.of("La cantidad de \"" + descripcion + "\" tiene que ser mayor que 0.");
        }
        int decimales = Math.max(cantidad.stripTrailingZeros().scale(), 0);
        if (!admiteDecimales && decimales > 0) {
            return Optional.of("\"" + descripcion
                    + "\" se vende por unidad: la cantidad tiene que ser un número entero.");
        }
        if (decimales > DECIMALES_MAXIMOS) {
            return Optional.of("La cantidad de \"" + descripcion + "\" admite hasta " + DECIMALES_MAXIMOS
                    + " decimales.");
        }
        return Optional.empty();
    }
}
