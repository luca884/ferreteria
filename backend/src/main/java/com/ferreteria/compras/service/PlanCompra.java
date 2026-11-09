package com.ferreteria.compras.service;

import com.ferreteria.catalogo.api.CostoDeCompra;
import com.ferreteria.catalogo.api.RecargoDeCompra;

import java.math.BigDecimal;
import java.util.List;

/**
 * La vista previa de una compra (CU-13): por renglon, en el orden del pedido, el costo y el precio anteriores y nuevos.
 * {@code suben}, {@code bajan} e {@code iguales} cuentan por el costo real: el de la compra con sus recargos contra el
 * del articulo con los suyos, porque la compra los pisa (vacios, los borra). El neto es la suma de los subtotales.
 * {@code recargos} son los de la compra ya validados y normalizados por catalogo.
 * {@code subtotalSinIva} es el neto con los recargos de la compra (el mismo factor del costo real) y
 * {@code recargosImporte} su diferencia con el neto. {@code iva} va por alicuota (base e importe a 2 decimales HALF_UP,
 * como una factura) y solo en Factura A y B; en Factura C y Remito esta vacia y el {@code total} es el subtotal.
 */
public record PlanCompra(
        String proveedorNombre,
        String moneda,
        List<RenglonPlaneado> renglones,
        BigDecimal neto,
        int suben,
        int bajan,
        int iguales,
        List<RecargoDeCompra> recargos,
        BigDecimal subtotalSinIva,
        BigDecimal recargosImporte,
        List<IvaPorAlicuota> iva,
        BigDecimal total) {

    public record IvaPorAlicuota(BigDecimal porcentaje, BigDecimal base, BigDecimal importe) {
    }

    public record RenglonPlaneado(CostoDeCompra costo, BigDecimal cantidad, BigDecimal subtotal) {
    }
}
