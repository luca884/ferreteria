package com.ferreteria.facturacion.model;

import java.math.BigDecimal;

/**
 * Las alícuotas de IVA de WSFEv1, con su {@code Id} y su porcentaje. Es propia de {@code facturacion}: quien pide la
 * factura traduce la del artículo a esta. Van declaradas en el orden de su {@code Id}, que es el orden en que el
 * desglose las informa.
 */
public enum Alicuota {
    IVA_0(3, "0"),
    IVA_10_5(4, "10.5"),
    IVA_21(5, "21"),
    IVA_27(6, "27"),
    IVA_5(8, "5"),
    IVA_2_5(9, "2.5");

    private final int id;
    private final BigDecimal porcentaje;

    Alicuota(int id, String porcentaje) {
        this.id = id;
        this.porcentaje = new BigDecimal(porcentaje);
    }

    /** El {@code Id} de la alícuota en WSFEv1. */
    public int id() {
        return id;
    }

    /** Porcentaje: 21 significa 21 %. */
    public BigDecimal porcentaje() {
        return porcentaje;
    }
}
