package com.ferreteria.ventas.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Totales del comprobante (R-006, GDS-37): la suma de los totales, netos e IVA de sus renglones. Nunca
 * se recalculan sobre el total: por construccion, neto + IVA = total y el total es la suma de los renglones.
 */
public record TotalesVenta(BigDecimal total, BigDecimal neto, BigDecimal iva) {

    public static TotalesVenta de(List<RenglonVenta> renglones) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal neto = BigDecimal.ZERO;
        BigDecimal iva = BigDecimal.ZERO;
        for (RenglonVenta renglon : renglones) {
            total = total.add(renglon.total());
            neto = neto.add(renglon.neto());
            iva = iva.add(renglon.iva());
        }
        return new TotalesVenta(total, neto, iva);
    }
}
