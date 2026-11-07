package com.ferreteria.ventas.service;

import com.ferreteria.ventas.model.ItemRemito;
import com.ferreteria.ventas.model.Remito;

import java.util.List;

/**
 * Un remito con sus renglones, en el orden del ticket. {@code facturadoPor} es la venta que lo facturo y solo viene en el
 * detalle de un remito FACTURADO; en cualquier otro caso, y en la anulacion, es null. {@code usuario} es el nombre de
 * quien lo emitio y solo viene en el detalle (informacion-y-links, S4).
 */
public record DetalleRemito(Remito remito, List<ItemRemito> items, FacturadoPor facturadoPor, String usuario) {

    public DetalleRemito(Remito remito, List<ItemRemito> items) {
        this(remito, items, null, null);
    }

    /**
     * La venta que facturo el remito: su id y su numero, y la factura («Factura B 00001-00000012») solo si la venta tiene
     * una valida como factura; si no, null.
     */
    public record FacturadoPor(Long ventaId, Long numero, String factura) {}
}
