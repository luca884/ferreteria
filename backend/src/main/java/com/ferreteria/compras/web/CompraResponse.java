package com.ferreteria.compras.web;

import com.ferreteria.compras.model.Compra;
import com.ferreteria.compras.model.ItemCompra;
import com.ferreteria.compras.model.TipoCompra;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/** Una compra con sus renglones (CU-13). */
public record CompraResponse(
        Long id,
        TipoCompra tipo,
        Integer puntoVenta,
        Long numero,
        String comprobante,
        LocalDate fecha,
        Long proveedorId,
        String proveedorNombre,
        String moneda,
        BigDecimal neto,
        LocalDateTime registrada,
        List<Renglon> renglones) {

    public record Renglon(
            Integer renglon,
            Long articuloId,
            String codigoInterno,
            String descripcion,
            String unidad,
            BigDecimal cantidad,
            BigDecimal costoUnitario,
            BigDecimal subtotal,
            BigDecimal costoAnterior,
            BigDecimal precioAnterior,
            BigDecimal precioNuevo) {

        static Renglon de(ItemCompra i) {
            return new Renglon(i.getRenglon(), i.getArticuloId(), i.getCodigoInterno(), i.getDescripcion(),
                    i.getUnidad(), i.getCantidad(), i.getCostoUnitario(), i.getSubtotal(), i.getCostoAnterior(),
                    i.getPrecioAnterior(), i.getPrecioNuevo());
        }
    }

    public static CompraResponse de(Compra c, List<ItemCompra> items) {
        return new CompraResponse(c.getId(), c.getTipo(), c.getPuntoVenta(), c.getNumero(), c.comprobante(),
                c.getFecha(), c.getProveedorId(), c.getProveedorNombre(), c.getMoneda(), c.getNeto(), c.getRegistrada(),
                items.stream().map(Renglon::de).toList());
    }
}
