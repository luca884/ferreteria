package com.ferreteria.ventas.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Lo que el mostrador pide cobrar (R-001), ya sin detalles HTTP: la clave de idempotencia del ticket, los
 * renglones tal como los vio el cajero (con el precio que se mostro y su descuento), el total que se mostro (con el
 * recargo de la forma de pago, GDS-11), el descuento global (GDS-12) y como se paga: una forma unica (por todo el total)
 * o una lista de pagos (GDS-07), nunca las dos. Los montos de los pagos son la parte del total SIN recargo que cubre cada
 * forma. Si la venta sale de un presupuesto (PP-006), {@code presupuestoId} es el suyo y la venta lo deja FACTURADO; si sale
 * de un remito (RM-006), {@code remitoId} es el suyo y la venta lo deja FACTURADO y concilia el stock. Nunca los dos. La
 * ferreteria y el usuario NO viajan aca: salen del JWT. Un descuento ausente es 0 %. {@code facturable} false deja la venta
 * NO_FACTURABLE: cobra, mueve caja y stock igual pero la fase 5 no la factura nunca; lo normal es true. {@code clienteId}
 * es el cliente elegido en el mostrador (CL-004); nulo es Consumidor Final. {@code autorizaExceso} es la autorización del
 * Dueño para una venta a cuenta corriente que pasa el límite del cliente (CC-005): solo significa algo si la venta excede
 * y NO es parte del contenido de la venta (CC-009: repetirla no depende de él).
 */
public record ConfirmarVenta(String clave, List<Renglon> renglones, BigDecimal totalMostrado, Long formaPagoId,
        BigDecimal descuentoPorcentaje, List<PagoPedido> pagos, Long presupuestoId, Long remitoId, boolean facturable,
        Long clienteId, boolean autorizaExceso) {

    public ConfirmarVenta {
        descuentoPorcentaje = descuentoPorcentaje == null ? BigDecimal.ZERO : descuentoPorcentaje;
        pagos = pagos == null ? List.of() : pagos;
    }

    public ConfirmarVenta(String clave, List<Renglon> renglones, BigDecimal totalMostrado, Long formaPagoId,
            BigDecimal descuentoPorcentaje, List<PagoPedido> pagos, Long presupuestoId, Long remitoId,
            boolean facturable, Long clienteId) {
        this(clave, renglones, totalMostrado, formaPagoId, descuentoPorcentaje, pagos, presupuestoId, remitoId,
                facturable, clienteId, false);
    }

    public ConfirmarVenta(String clave, List<Renglon> renglones, BigDecimal totalMostrado, Long formaPagoId,
            BigDecimal descuentoPorcentaje, List<PagoPedido> pagos, Long presupuestoId, Long remitoId,
            boolean facturable) {
        this(clave, renglones, totalMostrado, formaPagoId, descuentoPorcentaje, pagos, presupuestoId, remitoId,
                facturable, null);
    }

    public ConfirmarVenta(String clave, List<Renglon> renglones, BigDecimal totalMostrado, Long formaPagoId,
            BigDecimal descuentoPorcentaje, List<PagoPedido> pagos, Long presupuestoId, Long remitoId) {
        this(clave, renglones, totalMostrado, formaPagoId, descuentoPorcentaje, pagos, presupuestoId, remitoId, true);
    }

    public ConfirmarVenta(String clave, List<Renglon> renglones, BigDecimal totalMostrado, Long formaPagoId,
            BigDecimal descuentoPorcentaje, List<PagoPedido> pagos, Long presupuestoId) {
        this(clave, renglones, totalMostrado, formaPagoId, descuentoPorcentaje, pagos, presupuestoId, null);
    }

    public ConfirmarVenta(String clave, List<Renglon> renglones, BigDecimal totalMostrado, Long formaPagoId,
            BigDecimal descuentoPorcentaje, List<PagoPedido> pagos) {
        this(clave, renglones, totalMostrado, formaPagoId, descuentoPorcentaje, pagos, null, null);
    }

    public ConfirmarVenta(String clave, List<Renglon> renglones, BigDecimal totalMostrado, Long formaPagoId,
            BigDecimal descuentoPorcentaje) {
        this(clave, renglones, totalMostrado, formaPagoId, descuentoPorcentaje, List.of(), null, null);
    }

    public ConfirmarVenta(String clave, List<Renglon> renglones, BigDecimal totalMostrado, Long formaPagoId) {
        this(clave, renglones, totalMostrado, formaPagoId, BigDecimal.ZERO, List.of(), null, null);
    }

    /** Un renglon del ticket: articulo, cantidad, precio unitario mostrado (con IVA) y descuento propio en %. */
    public record Renglon(Long articuloId, BigDecimal cantidad, BigDecimal precioUnitario,
            BigDecimal descuentoPorcentaje) {

        public Renglon {
            descuentoPorcentaje = descuentoPorcentaje == null ? BigDecimal.ZERO : descuentoPorcentaje;
        }

        public Renglon(Long articuloId, BigDecimal cantidad, BigDecimal precioUnitario) {
            this(articuloId, cantidad, precioUnitario, BigDecimal.ZERO);
        }
    }

    /** Una parte del pago: la forma y la parte del total, sin recargo, que se paga con ella. */
    public record PagoPedido(Long formaPagoId, BigDecimal monto) {}
}
