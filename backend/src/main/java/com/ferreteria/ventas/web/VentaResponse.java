package com.ferreteria.ventas.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ferreteria.facturacion.api.FacturacionParaVenta.EstadoFiscalVenta;
import com.ferreteria.ventas.model.EstadoComprobante;
import com.ferreteria.ventas.model.ItemComprobante;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.service.VentaRegistrada;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * La venta guardada (R-001). Sin costo ni margen. El subtotal es antes de descuentos; {@code descuento} es lo
 * descontado en toda la venta y {@code descuentoPorcentaje} el global; {@code recargo} es el de las formas de pago
 * (GDS-11) y ya esta sumado al total: subtotal - descuento + recargo = total. {@code pagos} es un pago por forma
 * (GDS-07); {@code formaPagoId} y {@code medioPago} solo vienen si se pago con una unica forma. {@code sinStock} lista los
 * articulos que quedaron con stock negativo (R-016); en una venta repetida viene vacio. {@code presupuestoId} solo viene si
 * la venta salio de un presupuesto (PP-006) y {@code remitoId} solo si salio de un remito (RM-006). {@code facturable} es
 * false si la venta quedo NO_FACTURABLE (NF-002). {@code clienteId} y {@code clienteNombre} (copia de su razon social) solo
 * vienen si se eligio un cliente (CL-004). {@code usuario} (el nombre de quien la cobro) solo viene en la consulta
 * (G60), no al cobrar. {@code fiscal} (CU-07 paso 5) solo viene al cobrar una venta con registro fiscal: el estado de su
 * factura al terminar la espera del CAE (o el actual, en la venta repetida); la venta sin registro fiscal no lo trae.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VentaResponse(
        Long ventaId, Long numero, BigDecimal total, LocalDateTime fecha, Long formaPagoId, MedioPago medioPago,
        List<PagoResponse> pagos, List<RenglonResponse> renglones, List<SinStockResponse> sinStock, BigDecimal subtotal,
        BigDecimal descuentoPorcentaje, BigDecimal descuento, BigDecimal recargo, Long presupuestoId,
        Long remitoId, boolean facturable, Long clienteId, String clienteNombre, String usuario, FiscalResponse fiscal) {

    /**
     * El estado de la factura de la venta (CU-07 paso 5). Trae todos sus campos: {@code numero} es null mientras no tiene
     * número fiscal, y {@code cae} y {@code caeVencimiento}, mientras no está autorizada.
     *
     * @param estado PENDIENTE, EN_CURSO, EN_VERIFICACION, AUTORIZADO, RECHAZADO, FUERA_DE_FECHA, CAE_NO_COINCIDE o
     *     SERIE_DESINCRONIZADA
     * @param clase FACTURA
     * @param letra A, B o C
     * @param ambiente SIMULADOR, HOMOLOGACION o PRODUCCION
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FiscalResponse(String estado, String clase, String letra, int puntoVenta, Long numero, String cae,
            LocalDate caeVencimiento, String ambiente) {

        static FiscalResponse de(EstadoFiscalVenta e) {
            return e == null ? null : new FiscalResponse(e.estado(), e.clase(), e.letra(), e.puntoVenta(), e.numero(),
                    e.cae(), e.caeVencimiento(), e.ambiente());
        }
    }

    public record RenglonResponse(
            Integer renglon, Long articuloId, String descripcion, String unidad, BigDecimal cantidad,
            BigDecimal precioUnitario, String alicuotaIva, BigDecimal total, BigDecimal neto, BigDecimal iva,
            BigDecimal bruto, BigDecimal descuentoPorcentaje, BigDecimal recargo) {

        static RenglonResponse de(ItemComprobante i) {
            return new RenglonResponse(i.getRenglon(), i.getArticuloId(), i.getDescripcion(), i.getUnidad(),
                    i.getCantidad(), i.getPrecioUnitario(), i.getAlicuotaIva(), i.getTotal(), i.getNeto(), i.getIva(),
                    i.getBruto(), i.getDescuentoPorcentaje(), i.getRecargo());
        }
    }

    /** {@code monto} es lo que se cobro con esa forma (la parte del total mas su recargo). */
    public record PagoResponse(Long formaPagoId, String formaPago, MedioPago medio, BigDecimal recargoPorcentaje,
            BigDecimal recargo, BigDecimal monto) {}

    public record SinStockResponse(Long articuloId, String descripcion, BigDecimal stockActual) {}

    public static VentaResponse de(VentaRegistrada venta) {
        return de(venta, null);
    }

    public static VentaResponse de(VentaRegistrada venta, String usuario) {
        return new VentaResponse(
                venta.comprobante().getId(), venta.comprobante().getNumero(), venta.comprobante().getTotal(),
                venta.comprobante().getFecha(),
                venta.pagos().size() == 1 ? venta.pagos().get(0).getFormaPagoId() : null,
                venta.pagos().size() == 1 ? venta.pagos().get(0).getMedio() : null,
                venta.pagos().stream().map(p -> new PagoResponse(p.getFormaPagoId(), p.getFormaPagoNombre(), p.getMedio(),
                        p.getRecargoPorcentaje(), p.getRecargo(), p.getMonto())).toList(),
                venta.items().stream().map(RenglonResponse::de).toList(),
                venta.sinStock().stream()
                        .map(s -> new SinStockResponse(s.articuloId(), s.descripcion(), s.stockActual()))
                        .toList(),
                venta.comprobante().getSubtotal(), venta.comprobante().getDescuentoPorcentaje(),
                venta.comprobante().getSubtotal()
                        .subtract(venta.comprobante().getTotal().subtract(venta.comprobante().getRecargo())),
                venta.comprobante().getRecargo(), venta.comprobante().getPresupuestoId(),
                venta.comprobante().getRemitoId(),
                venta.comprobante().getEstado() != EstadoComprobante.NO_FACTURABLE,
                venta.comprobante().getClienteId(), venta.comprobante().getClienteNombre(), usuario,
                FiscalResponse.de(venta.fiscal()));
    }
}
