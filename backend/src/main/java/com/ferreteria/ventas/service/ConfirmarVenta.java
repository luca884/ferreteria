package com.ferreteria.ventas.service;

import java.math.BigDecimal;
import java.util.List;

/**
 * Lo que el mostrador pide cobrar (R-001), ya sin detalles HTTP: la clave de idempotencia del ticket, los
 * renglones tal como los vio el cajero (con el precio que se mostro y su descuento), el total que se mostro (con el
 * recargo de la forma de pago, GDS-11), el descuento global (GDS-12) y como se paga: una forma unica (por todo el total)
 * o una lista de pagos (GDS-07), nunca las dos. Los montos de los pagos son la parte del total SIN recargo que cubre cada
 * forma. Si la venta sale de un presupuesto (PP-006), {@code presupuestoId} es el suyo y la venta lo deja FACTURADO. La
 * ferreteria y el usuario NO viajan aca: salen del JWT. Un descuento ausente es 0 %.
 */
public record ConfirmarVenta(String clave, List<Renglon> renglones, BigDecimal totalMostrado, Long formaPagoId,
        BigDecimal descuentoPorcentaje, List<PagoPedido> pagos, Long presupuestoId) {

    public ConfirmarVenta {
        descuentoPorcentaje = descuentoPorcentaje == null ? BigDecimal.ZERO : descuentoPorcentaje;
        pagos = pagos == null ? List.of() : pagos;
    }

    public ConfirmarVenta(String clave, List<Renglon> renglones, BigDecimal totalMostrado, Long formaPagoId,
            BigDecimal descuentoPorcentaje, List<PagoPedido> pagos) {
        this(clave, renglones, totalMostrado, formaPagoId, descuentoPorcentaje, pagos, null);
    }

    public ConfirmarVenta(String clave, List<Renglon> renglones, BigDecimal totalMostrado, Long formaPagoId,
            BigDecimal descuentoPorcentaje) {
        this(clave, renglones, totalMostrado, formaPagoId, descuentoPorcentaje, List.of(), null);
    }

    public ConfirmarVenta(String clave, List<Renglon> renglones, BigDecimal totalMostrado, Long formaPagoId) {
        this(clave, renglones, totalMostrado, formaPagoId, BigDecimal.ZERO, List.of(), null);
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
