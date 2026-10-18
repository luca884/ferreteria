package com.ferreteria.ventas.service;

import com.ferreteria.ventas.model.MedioPago;

import java.math.BigDecimal;
import java.util.List;

/**
 * Lo que el mostrador pide cobrar (R-001), ya sin detalles HTTP: la clave de idempotencia del ticket, los
 * renglones tal como los vio el cajero (con el precio que se mostro y su descuento), el total que se mostro,
 * el descuento global (GDS-12) y como se paga: un medio unico (por el total) o una lista de pagos (GDS-07),
 * nunca los dos. La ferreteria y el usuario NO viajan aca: salen del JWT. Un descuento ausente es 0 %.
 */
public record ConfirmarVenta(String clave, List<Renglon> renglones, BigDecimal totalMostrado, MedioPago medioPago,
        BigDecimal descuentoPorcentaje, List<PagoPedido> pagos) {

    public ConfirmarVenta {
        descuentoPorcentaje = descuentoPorcentaje == null ? BigDecimal.ZERO : descuentoPorcentaje;
        pagos = pagos == null ? List.of() : pagos;
    }

    public ConfirmarVenta(String clave, List<Renglon> renglones, BigDecimal totalMostrado, MedioPago medioPago,
            BigDecimal descuentoPorcentaje) {
        this(clave, renglones, totalMostrado, medioPago, descuentoPorcentaje, List.of());
    }

    public ConfirmarVenta(String clave, List<Renglon> renglones, BigDecimal totalMostrado, MedioPago medioPago) {
        this(clave, renglones, totalMostrado, medioPago, BigDecimal.ZERO, List.of());
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

    /** Una parte del pago: el medio y cuanto se paga con el. */
    public record PagoPedido(MedioPago medio, BigDecimal monto) {}
}
