package com.ferreteria.facturacion.model;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * Lo que el desglose fiscal necesita de un renglón del comprobante: su alícuota y su total con IVA incluido (lo que se
 * cobra). El total queda con escala 2.
 *
 * @param alicuota la alícuota de IVA del renglón
 * @param total el total del renglón con IVA incluido, en pesos
 */
public record RenglonFiscal(Alicuota alicuota, BigDecimal total) {

    public RenglonFiscal {
        Objects.requireNonNull(alicuota, "Falta la alícuota del renglón.");
        Objects.requireNonNull(total, "Falta el total del renglón.");
        if (total.signum() < 0) {
            throw new IllegalArgumentException("El total de un renglón no puede ser negativo.");
        }
        if (total.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("El total de un renglón va en pesos con dos decimales como mucho.");
        }
        total = total.setScale(2);
    }
}
