package com.ferreteria.ventas.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ferreteria.ventas.model.EstadoRemito;
import com.ferreteria.ventas.model.ItemRemito;
import com.ferreteria.ventas.model.Remito;
import com.ferreteria.ventas.service.DetalleRemito;
import com.ferreteria.ventas.service.RemitoEmitido;
import com.ferreteria.ventas.web.VentaResponse.SinStockResponse;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * El remito guardado (RM-004). Sin costo ni margen. El subtotal es antes de descuentos; {@code descuento} es lo descontado
 * en toda la venta y {@code descuentoPorcentaje} el global. {@code sinStock} lista los articulos que quedaron con stock
 * negativo al emitirlo y solo viene al emitir (en un remito repetido viene vacio). {@code facturadoPor} es la venta que lo
 * facturo y solo viene en el detalle de un remito FACTURADO. {@code usuario} es quien lo emitio y solo viene en el detalle
 * (informacion-y-links, S4). {@code fechaAnulacion} y {@code anuladoPor} dicen cuando y quien lo anulo (#81): solo en un
 * remito anulado desde la V47, y el nombre solo en el detalle y en la anulacion.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RemitoResponse(
        Long remitoId, Long numero, EstadoRemito estado, LocalDateTime fecha, Long clienteId, String clienteNombre,
        String observaciones, BigDecimal subtotal, BigDecimal descuentoPorcentaje, BigDecimal descuento, BigDecimal neto, BigDecimal iva,
        BigDecimal total, List<RenglonResponse> renglones, List<SinStockResponse> sinStock,
        FacturadoPorResponse facturadoPor, String usuario, LocalDateTime fechaAnulacion, String anuladoPor) {

    /** La venta que facturo el remito; {@code factura} («Factura B 00001-00000012») solo si la venta tiene una valida. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FacturadoPorResponse(Long ventaId, Long numero, String factura, List<DiferenciaResponse> diferencias) {

        /** {@code diferencias} solo viene si la venta facturo algun articulo en otra cantidad (informacion-y-links, S5). */
        static FacturadoPorResponse de(DetalleRemito.FacturadoPor f) {
            if (f == null) {
                return null;
            }
            List<DiferenciaResponse> diferencias = f.diferencias().stream()
                    .map(d -> new DiferenciaResponse(d.articuloId(), d.descripcion(), d.unidad(), d.remitido(),
                            d.facturado()))
                    .toList();
            return new FacturadoPorResponse(f.ventaId(), f.numero(), f.factura(),
                    diferencias.isEmpty() ? null : diferencias);
        }
    }

    /** Un articulo que la venta facturo en otra cantidad que la remitida. */
    public record DiferenciaResponse(Long articuloId, String descripcion, String unidad, BigDecimal remitido,
            BigDecimal facturado) {}

    public record RenglonResponse(
            Integer renglon, Long articuloId, String descripcion, String unidad, BigDecimal cantidad,
            BigDecimal precioUnitario, String alicuotaIva, BigDecimal bruto, BigDecimal descuentoPorcentaje,
            BigDecimal total, BigDecimal neto, BigDecimal iva) {

        static RenglonResponse de(ItemRemito i) {
            return new RenglonResponse(i.getRenglon(), i.getArticuloId(), i.getDescripcion(), i.getUnidad(),
                    i.getCantidad(), i.getPrecioUnitario(), i.getAlicuotaIva(), i.getBruto(),
                    i.getDescuentoPorcentaje(), i.getTotal(), i.getNeto(), i.getIva());
        }
    }

    public static RemitoResponse de(RemitoEmitido emitido) {
        return de(emitido.remito(), emitido.items(), emitido.sinStock().stream()
                .map(s -> new SinStockResponse(s.articuloId(), s.descripcion(), s.stockActual()))
                .toList(), null, null, null);
    }

    public static RemitoResponse de(Remito r, List<ItemRemito> items) {
        return de(r, items, null, null, null, null);
    }

    public static RemitoResponse de(DetalleRemito detalle) {
        return de(detalle.remito(), detalle.items(), null, FacturadoPorResponse.de(detalle.facturadoPor()),
                detalle.usuario(), detalle.anuladoPor());
    }

    private static RemitoResponse de(Remito r, List<ItemRemito> items, List<SinStockResponse> sinStock,
            FacturadoPorResponse facturadoPor, String usuario, String anuladoPor) {
        return new RemitoResponse(r.getId(), r.getNumero(), r.getEstado(), r.getFecha(), r.getClienteId(),
                r.getClienteNombre(), r.getObservaciones(), r.getSubtotal(), r.getDescuentoPorcentaje(),
                r.getSubtotal().subtract(r.getTotal()), r.getNeto(), r.getIva(), r.getTotal(),
                items.stream().map(RenglonResponse::de).toList(), sinStock, facturadoPor, usuario,
                r.getFechaAnulacion(), anuladoPor);
    }
}
