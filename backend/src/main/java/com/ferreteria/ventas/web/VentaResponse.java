package com.ferreteria.ventas.web;

import com.ferreteria.ventas.model.ItemComprobante;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.service.VentaRegistrada;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * La venta guardada (R-001). Sin costo ni margen. {@code sinStock} lista los articulos que quedaron con stock
 * negativo (R-016); en una venta repetida viene vacio.
 */
public record VentaResponse(
        Long ventaId, Long numero, BigDecimal total, LocalDateTime fecha, MedioPago medioPago,
        List<RenglonResponse> renglones, List<SinStockResponse> sinStock) {

    public record RenglonResponse(
            Integer renglon, Long articuloId, String descripcion, String unidad, BigDecimal cantidad,
            BigDecimal precioUnitario, String alicuotaIva, BigDecimal total, BigDecimal neto, BigDecimal iva) {

        static RenglonResponse de(ItemComprobante i) {
            return new RenglonResponse(i.getRenglon(), i.getArticuloId(), i.getDescripcion(), i.getUnidad(),
                    i.getCantidad(), i.getPrecioUnitario(), i.getAlicuotaIva(), i.getTotal(), i.getNeto(), i.getIva());
        }
    }

    public record SinStockResponse(Long articuloId, String descripcion, BigDecimal stockActual) {}

    public static VentaResponse de(VentaRegistrada venta) {
        return new VentaResponse(
                venta.comprobante().getId(), venta.comprobante().getNumero(), venta.comprobante().getTotal(),
                venta.comprobante().getFecha(), venta.pago().getMedio(),
                venta.items().stream().map(RenglonResponse::de).toList(),
                venta.sinStock().stream()
                        .map(s -> new SinStockResponse(s.articuloId(), s.descripcion(), s.stockActual()))
                        .toList());
    }
}
