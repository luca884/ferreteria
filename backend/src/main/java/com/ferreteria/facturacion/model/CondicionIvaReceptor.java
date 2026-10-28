package com.ferreteria.facturacion.model;

/**
 * La condición frente al IVA de quien recibe el comprobante, con el {@code CondicionIVAReceptorId} que pide WSFEv1
 * (RG 5616). Es propia de {@code facturacion}: quien pide la factura traduce la del cliente a esta. La venta sin
 * cliente se informa como {@link #CONSUMIDOR_FINAL} (ver {@link EncuadreFiscal#sinCliente}).
 */
public enum CondicionIvaReceptor {
    RESPONSABLE_INSCRIPTO(1),
    EXENTO(4),
    CONSUMIDOR_FINAL(5),
    MONOTRIBUTISTA(6);

    private final int condicionIvaReceptorId;

    CondicionIvaReceptor(int condicionIvaReceptorId) {
        this.condicionIvaReceptorId = condicionIvaReceptorId;
    }

    /** El {@code CondicionIVAReceptorId} de WSFEv1. */
    public int condicionIvaReceptorId() {
        return condicionIvaReceptorId;
    }
}
