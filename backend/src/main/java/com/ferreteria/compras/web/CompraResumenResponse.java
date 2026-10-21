package com.ferreteria.compras.web;

import com.ferreteria.compras.model.TipoCompra;
import com.ferreteria.compras.service.CompraConsulta.ResumenCompra;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Una fila del listado de compras (CU-13). */
public record CompraResumenResponse(
        Long id,
        LocalDate fecha,
        TipoCompra tipo,
        String comprobante,
        Long proveedorId,
        String proveedorNombre,
        String moneda,
        BigDecimal neto,
        long renglones) {

    public static CompraResumenResponse de(ResumenCompra r) {
        return new CompraResumenResponse(r.compra().getId(), r.compra().getFecha(), r.compra().getTipo(),
                r.compra().comprobante(), r.compra().getProveedorId(), r.compra().getProveedorNombre(),
                r.compra().getMoneda(), r.compra().getNeto(), r.renglones());
    }
}
