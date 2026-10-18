package com.ferreteria.ventas.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Totales del comprobante (R-006, GDS-37): la suma de los totales, netos e IVA de sus renglones. Nunca
 * se recalculan sobre el total: por construccion, neto + IVA = total y el total es la suma de los renglones.
 * El subtotal es la suma de los brutos (antes de descuentos); sin descuentos ni recargo es igual al total. El recargo
 * (GDS-11) es la suma de los de los renglones: subtotal - descuento + recargo = total.
 */
public record TotalesVenta(BigDecimal total, BigDecimal neto, BigDecimal iva, BigDecimal subtotal, BigDecimal recargo) {

    public TotalesVenta(BigDecimal total, BigDecimal neto, BigDecimal iva, BigDecimal subtotal) {
        this(total, neto, iva, subtotal, BigDecimal.ZERO.setScale(2));
    }

    public TotalesVenta(BigDecimal total, BigDecimal neto, BigDecimal iva) {
        this(total, neto, iva, total);
    }

    public static TotalesVenta de(List<RenglonVenta> renglones) {
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal neto = BigDecimal.ZERO;
        BigDecimal iva = BigDecimal.ZERO;
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal recargo = BigDecimal.ZERO;
        for (RenglonVenta renglon : renglones) {
            total = total.add(renglon.total());
            neto = neto.add(renglon.neto());
            iva = iva.add(renglon.iva());
            subtotal = subtotal.add(renglon.bruto());
            recargo = recargo.add(renglon.recargo());
        }
        return new TotalesVenta(total, neto, iva, subtotal, recargo);
    }

    /** Lo descontado en toda la venta: subtotal menos lo que se cobra sin el recargo. */
    public BigDecimal descuento() {
        return subtotal.subtract(total.subtract(recargo));
    }
}
