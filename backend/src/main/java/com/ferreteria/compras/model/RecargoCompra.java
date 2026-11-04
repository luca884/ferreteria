package com.ferreteria.compras.model;

import com.ferreteria.catalogo.api.RecargoDeCompra;

import java.math.BigDecimal;

/**
 * Un recargo sobre el costo que traia la compra (CU-13), por ejemplo «Embalaje y carga 4,44 %». Se guarda en
 * {@code compra.recargos} (JSON) ya validado y normalizado por catalogo ({@code ArticulosParaCompra#validarRecargos}):
 * las reglas no se repiten aca.
 */
public record RecargoCompra(String nombre, BigDecimal porcentaje) {

    public static RecargoCompra de(RecargoDeCompra recargo) {
        return new RecargoCompra(recargo.nombre(), recargo.porcentaje());
    }

    public RecargoDeCompra aApi() {
        return new RecargoDeCompra(nombre, porcentaje);
    }
}
