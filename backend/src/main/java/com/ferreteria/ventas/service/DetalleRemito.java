package com.ferreteria.ventas.service;

import com.ferreteria.ventas.model.ItemRemito;
import com.ferreteria.ventas.model.Remito;

import java.math.BigDecimal;
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
     * una valida como factura; si no, null. {@code diferencias} son los articulos del remito que la venta facturo en otra
     * cantidad (informacion-y-links, S5); vacia si facturo todo igual.
     */
    public record FacturadoPor(Long ventaId, Long numero, String factura, List<Diferencia> diferencias) {

        public FacturadoPor(Long ventaId, Long numero, String factura) {
            this(ventaId, numero, factura, List.of());
        }
    }

    /**
     * Un articulo del remito facturado en otra cantidad (RM-006): {@code remitido} es lo que entrego el remito y
     * {@code facturado} lo que cobro la venta, sumados por articulo. Si se facturo menos, la diferencia volvio al stock.
     */
    public record Diferencia(Long articuloId, String descripcion, String unidad, BigDecimal remitido,
            BigDecimal facturado) {}
}
