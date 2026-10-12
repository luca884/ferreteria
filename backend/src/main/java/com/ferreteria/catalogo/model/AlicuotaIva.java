package com.ferreteria.catalogo.model;

import java.math.BigDecimal;

/** Alicuotas de IVA que admite ARCA para un articulo. */
public enum AlicuotaIva {
    IVA_0("0.00"),
    IVA_2_5("2.50"),
    IVA_5("5.00"),
    IVA_10_5("10.50"),
    IVA_21("21.00"),
    IVA_27("27.00");

    private final BigDecimal porcentaje;

    AlicuotaIva(String porcentaje) {
        this.porcentaje = new BigDecimal(porcentaje);
    }

    /** Porcentaje: 21.00 significa 21 %. */
    public BigDecimal porcentaje() {
        return porcentaje;
    }
}
