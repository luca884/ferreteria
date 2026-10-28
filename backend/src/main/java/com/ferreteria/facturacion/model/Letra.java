package com.ferreteria.facturacion.model;

import java.util.Objects;

/** La letra del comprobante, con el {@code CbteTipo} de WSFEv1 de su factura y de su nota de crédito. */
public enum Letra {
    A(1, 3),
    B(6, 8),
    C(11, 13);

    private final int cbteTipoFactura;
    private final int cbteTipoNotaCredito;

    Letra(int cbteTipoFactura, int cbteTipoNotaCredito) {
        this.cbteTipoFactura = cbteTipoFactura;
        this.cbteTipoNotaCredito = cbteTipoNotaCredito;
    }

    /** El {@code CbteTipo} de WSFEv1 del comprobante de esta letra y esa clase. */
    public int cbteTipo(ClaseComprobante clase) {
        Objects.requireNonNull(clase, "Falta la clase del comprobante.");
        return switch (clase) {
            case FACTURA -> cbteTipoFactura;
            case NOTA_CREDITO -> cbteTipoNotaCredito;
        };
    }

    /** La C no discrimina IVA: el comprobante va sin alícuotas y el neto es el total. */
    public boolean discriminaIva() {
        return this != C;
    }
}
