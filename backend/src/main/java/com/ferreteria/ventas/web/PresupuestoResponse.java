package com.ferreteria.ventas.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ferreteria.common.HoraArgentina;
import com.ferreteria.ventas.model.EstadoPresupuesto;
import com.ferreteria.ventas.model.ItemPresupuesto;
import com.ferreteria.ventas.model.Presupuesto;
import com.ferreteria.ventas.service.DetallePresupuesto;
import com.ferreteria.ventas.service.PresupuestoEmitido;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * El presupuesto guardado (PP-003). Sin costo ni margen. El subtotal es antes de descuentos; {@code descuento} es lo
 * descontado en toda la venta y {@code descuentoPorcentaje} el global. {@code vencido} se calcula al responder: el estado
 * guardado no cambia cuando vence (PP-002). {@code facturadoPor} es la venta que lo cobro y solo viene en el detalle de
 * un presupuesto FACTURADO (pasado a venta).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PresupuestoResponse(
        Long presupuestoId, Long numero, EstadoPresupuesto estado, LocalDateTime fecha, LocalDate vence, boolean vencido,
        Long clienteId, String clienteNombre, String observaciones, BigDecimal subtotal, BigDecimal descuentoPorcentaje,
        BigDecimal descuento, BigDecimal neto, BigDecimal iva, BigDecimal total, List<RenglonResponse> renglones,
        FacturadoPorResponse facturadoPor) {

    /** La venta que cobro el presupuesto; {@code factura} («Factura B 00001-00000012») solo si la venta tiene una valida. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FacturadoPorResponse(Long ventaId, Long numero, String factura) {

        static FacturadoPorResponse de(DetallePresupuesto.FacturadoPor f) {
            return f == null ? null : new FacturadoPorResponse(f.ventaId(), f.numero(), f.factura());
        }
    }

    public record RenglonResponse(
            Integer renglon, Long articuloId, String descripcion, String unidad, BigDecimal cantidad,
            BigDecimal precioUnitario, String alicuotaIva, BigDecimal bruto, BigDecimal descuentoPorcentaje,
            BigDecimal total, BigDecimal neto, BigDecimal iva) {

        static RenglonResponse de(ItemPresupuesto i) {
            return new RenglonResponse(i.getRenglon(), i.getArticuloId(), i.getDescripcion(), i.getUnidad(),
                    i.getCantidad(), i.getPrecioUnitario(), i.getAlicuotaIva(), i.getBruto(),
                    i.getDescuentoPorcentaje(), i.getTotal(), i.getNeto(), i.getIva());
        }
    }

    public static PresupuestoResponse de(PresupuestoEmitido emitido) {
        return de(emitido.presupuesto(), emitido.items(), null);
    }

    public static PresupuestoResponse de(DetallePresupuesto detalle) {
        return de(detalle.presupuesto(), detalle.items(), FacturadoPorResponse.de(detalle.facturadoPor()));
    }

    private static PresupuestoResponse de(Presupuesto p, List<ItemPresupuesto> items, FacturadoPorResponse facturadoPor) {
        return new PresupuestoResponse(p.getId(), p.getNumero(), p.getEstado(), p.getFecha(), p.getVence(),
                p.estaVencido(HoraArgentina.hoy()), p.getClienteId(), p.getClienteNombre(), p.getObservaciones(),
                p.getSubtotal(),
                p.getDescuentoPorcentaje(), p.getSubtotal().subtract(p.getTotal()), p.getNeto(), p.getIva(),
                p.getTotal(), items.stream().map(RenglonResponse::de).toList(), facturadoPor);
    }
}
