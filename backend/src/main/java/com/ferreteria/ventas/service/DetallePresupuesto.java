package com.ferreteria.ventas.service;

import com.ferreteria.ventas.model.ItemPresupuesto;
import com.ferreteria.ventas.model.Presupuesto;

import java.util.List;

/**
 * Un presupuesto con sus renglones, en el orden del ticket. {@code facturadoPor} es la venta que lo cobro y solo viene en
 * el detalle de un presupuesto FACTURADO (el estado interno de «pasado a venta»); en cualquier otro caso es null.
 */
public record DetallePresupuesto(Presupuesto presupuesto, List<ItemPresupuesto> items, FacturadoPor facturadoPor) {

    public DetallePresupuesto(Presupuesto presupuesto, List<ItemPresupuesto> items) {
        this(presupuesto, items, null);
    }

    /**
     * La venta que cobro el presupuesto: su id y su numero, y la factura («Factura B 00001-00000012») solo si la venta
     * tiene una valida como factura; si no, null. Misma forma que la del remito.
     */
    public record FacturadoPor(Long ventaId, Long numero, String factura) {}
}
