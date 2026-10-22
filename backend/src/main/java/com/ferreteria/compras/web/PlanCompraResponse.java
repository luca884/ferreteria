package com.ferreteria.compras.web;

import com.ferreteria.catalogo.api.CostoDeCompra;
import com.ferreteria.compras.service.PlanCompra;

import java.math.BigDecimal;
import java.util.List;

/** La vista previa de la compra (CU-13): por renglon, costo y precio anteriores y nuevos y la advertencia (o null). */
public record PlanCompraResponse(
        String proveedorNombre,
        String moneda,
        BigDecimal neto,
        int suben,
        int bajan,
        int iguales,
        List<Renglon> renglones) {

    public record Renglon(
            Long articuloId,
            String codigoInterno,
            String descripcion,
            String unidad,
            BigDecimal cantidad,
            BigDecimal costoAnterior,
            BigDecimal costoNuevo,
            BigDecimal precioAnterior,
            BigDecimal precioNuevo,
            BigDecimal subtotal,
            String advertencia) {
    }

    public static PlanCompraResponse de(PlanCompra plan) {
        return new PlanCompraResponse(plan.proveedorNombre(), plan.moneda(), plan.neto(), plan.suben(), plan.bajan(),
                plan.iguales(), plan.renglones().stream().map(r -> {
                    CostoDeCompra c = r.costo();
                    return new Renglon(c.articuloId(), c.codigoInterno(), c.descripcion(), c.unidad(), r.cantidad(),
                            c.costoAnterior(), c.costoNuevo(), c.precioAnterior(), c.precioNuevo(), r.subtotal(),
                            c.advertencia());
                }).toList());
    }
}
