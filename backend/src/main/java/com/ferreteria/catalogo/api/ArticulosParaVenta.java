package com.ferreteria.catalogo.api;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Puerto de catalogo hacia ventas y stock (C-004). Es lo unico de catalogo que esos modulos pueden usar
 * (lo vigila ModulosArchTest). Todo se resuelve con la ferreteria del request.
 */
public interface ArticulosParaVenta {

    /** Los articulos de la ferreteria del request, activos o no, con esos ids. Los ajenos o inexistentes no vienen. */
    List<ArticuloVendible> buscar(Collection<Long> ids);

    /**
     * Suma cada delta al stock de su articulo, por id ASCENDENTE (orden fijo de locks) y con un UPDATE
     * atomico, y devuelve el stock resultante de cada uno. Exige una transaccion abierta. Si algun id no
     * existe en la ferreteria lanza IllegalStateException y la transaccion debe revertirse.
     */
    Map<Long, BigDecimal> ajustarStock(Map<Long, BigDecimal> deltas);
}
