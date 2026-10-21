package com.ferreteria.compras.service;

import com.ferreteria.catalogo.api.CostoDeCompra;

import java.math.BigDecimal;
import java.util.List;

/**
 * La vista previa de una compra (CU-13): por renglon, en el orden del pedido, el costo y el precio anteriores y nuevos.
 * {@code suben}, {@code bajan} e {@code iguales} cuentan por el costo. El neto es la suma de los subtotales.
 */
public record PlanCompra(
        String proveedorNombre,
        String moneda,
        List<RenglonPlaneado> renglones,
        BigDecimal neto,
        int suben,
        int bajan,
        int iguales) {

    public record RenglonPlaneado(CostoDeCompra costo, BigDecimal cantidad, BigDecimal subtotal) {
    }
}
