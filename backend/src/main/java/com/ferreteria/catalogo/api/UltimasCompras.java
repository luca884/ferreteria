package com.ferreteria.catalogo.api;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;

/**
 * Puerto que catalogo pide y compras implementa (mejoras-ferreteros-bloque-2, S6): la ultima compra de cada articulo,
 * para el listado de stock bajo minimo. Catalogo no conoce a compras (ModulosArchTest): compras se engancha aca.
 */
public interface UltimasCompras {

    /**
     * La ultima compra de cada uno de esos articulos en la ferreteria del request: la de fecha de comprobante mas nueva
     * (a igual fecha, la ultima cargada). Un articulo sin compras no esta en el mapa.
     */
    Map<Long, UltimaCompra> deLosArticulos(Collection<Long> articuloIds);

    /**
     * Id de la compra, texto de su comprobante («Factura A 00013-00605684»), fecha del comprobante y nombre del
     * proveedor tal como quedo en la compra.
     */
    record UltimaCompra(Long compraId, String comprobante, LocalDate fecha, String proveedor) {
    }
}
