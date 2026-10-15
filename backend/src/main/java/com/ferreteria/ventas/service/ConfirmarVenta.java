package com.ferreteria.ventas.service;

import com.ferreteria.ventas.model.MedioPago;

import java.math.BigDecimal;
import java.util.List;

/**
 * Lo que el mostrador pide cobrar (R-001), ya sin detalles HTTP: la clave de idempotencia del ticket, los
 * renglones tal como los vio el cajero (con el precio que se mostro), el total que se mostro y el medio de
 * pago. La ferreteria y el usuario NO viajan aca: salen del JWT.
 */
public record ConfirmarVenta(String clave, List<Renglon> renglones, BigDecimal totalMostrado, MedioPago medioPago) {

    /** Un renglon del ticket: articulo, cantidad y precio unitario mostrado (con IVA). */
    public record Renglon(Long articuloId, BigDecimal cantidad, BigDecimal precioUnitario) {}
}
