package com.ferreteria.ventas.service;

import com.ferreteria.ventas.model.MedioPago;

import java.math.BigDecimal;
import java.util.List;

/**
 * Lo que el mostrador pide cobrar (R-001), ya sin detalles HTTP: la clave de idempotencia del ticket, los
 * renglones tal como los vio el cajero (con el precio que se mostro y su descuento), el total que se mostro,
 * el medio de pago y el descuento global (GDS-12). La ferreteria y el usuario NO viajan aca: salen del JWT.
 * Un descuento ausente es 0 %.
 */
public record ConfirmarVenta(String clave, List<Renglon> renglones, BigDecimal totalMostrado, MedioPago medioPago,
        BigDecimal descuentoPorcentaje) {

    public ConfirmarVenta {
        descuentoPorcentaje = descuentoPorcentaje == null ? BigDecimal.ZERO : descuentoPorcentaje;
    }

    public ConfirmarVenta(String clave, List<Renglon> renglones, BigDecimal totalMostrado, MedioPago medioPago) {
        this(clave, renglones, totalMostrado, medioPago, BigDecimal.ZERO);
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
}
