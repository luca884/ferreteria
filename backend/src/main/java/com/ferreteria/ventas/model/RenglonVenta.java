package com.ferreteria.ventas.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Un renglon del ticket ya calculado (R-005). El precio es el final, con IVA incluido. El total es
 * precio x cantidad a 2 decimales; el neto sale de ese total (total x 100 / (100 + %)) y el IVA es la
 * diferencia, asi que neto + IVA = total siempre. Todo HALF_UP, nunca HALF_EVEN.
 */
public record RenglonVenta(BigDecimal precioUnitario, BigDecimal cantidad, BigDecimal total, BigDecimal neto,
        BigDecimal iva) {

    private static final int ESCALA = 2;
    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    public static RenglonVenta de(BigDecimal precioUnitario, BigDecimal cantidad, BigDecimal porcentajeIva) {
        BigDecimal total = precioUnitario.multiply(cantidad).setScale(ESCALA, RoundingMode.HALF_UP);
        BigDecimal neto = total.multiply(CIEN).divide(CIEN.add(porcentajeIva), ESCALA, RoundingMode.HALF_UP);
        return new RenglonVenta(precioUnitario, cantidad, total, neto, total.subtract(neto));
    }
}
