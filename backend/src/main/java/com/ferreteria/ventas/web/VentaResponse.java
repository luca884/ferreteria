package com.ferreteria.ventas.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ferreteria.ventas.model.ItemComprobante;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.service.VentaRegistrada;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * La venta guardada (R-001). Sin costo ni margen. El subtotal es antes de descuentos; {@code descuento} es lo
 * descontado en toda la venta (subtotal menos total) y {@code descuentoPorcentaje} el global. {@code pagos} es un
 * pago por medio (GDS-07); {@code medioPago} solo viene si se pago con un unico medio. {@code sinStock} lista los articulos que quedaron con stock
 * negativo (R-016); en una venta repetida viene vacio.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VentaResponse(
        Long ventaId, Long numero, BigDecimal total, LocalDateTime fecha, MedioPago medioPago,
        List<PagoResponse> pagos, List<RenglonResponse> renglones, List<SinStockResponse> sinStock, BigDecimal subtotal,
        BigDecimal descuentoPorcentaje, BigDecimal descuento) {

    public record RenglonResponse(
            Integer renglon, Long articuloId, String descripcion, String unidad, BigDecimal cantidad,
            BigDecimal precioUnitario, String alicuotaIva, BigDecimal total, BigDecimal neto, BigDecimal iva,
            BigDecimal bruto, BigDecimal descuentoPorcentaje) {

        static RenglonResponse de(ItemComprobante i) {
            return new RenglonResponse(i.getRenglon(), i.getArticuloId(), i.getDescripcion(), i.getUnidad(),
                    i.getCantidad(), i.getPrecioUnitario(), i.getAlicuotaIva(), i.getTotal(), i.getNeto(), i.getIva(),
                    i.getBruto(), i.getDescuentoPorcentaje());
        }
    }

    public record PagoResponse(MedioPago medio, BigDecimal monto) {}

    public record SinStockResponse(Long articuloId, String descripcion, BigDecimal stockActual) {}

    public static VentaResponse de(VentaRegistrada venta) {
        return new VentaResponse(
                venta.comprobante().getId(), venta.comprobante().getNumero(), venta.comprobante().getTotal(),
                venta.comprobante().getFecha(),
                venta.pagos().size() == 1 ? venta.pagos().get(0).getMedio() : null,
                venta.pagos().stream().map(p -> new PagoResponse(p.getMedio(), p.getMonto())).toList(),
                venta.items().stream().map(RenglonResponse::de).toList(),
                venta.sinStock().stream()
                        .map(s -> new SinStockResponse(s.articuloId(), s.descripcion(), s.stockActual()))
                        .toList(),
                venta.comprobante().getSubtotal(), venta.comprobante().getDescuentoPorcentaje(),
                venta.comprobante().getSubtotal().subtract(venta.comprobante().getTotal()));
    }
}
