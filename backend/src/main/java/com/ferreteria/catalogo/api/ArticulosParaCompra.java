package com.ferreteria.catalogo.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Puerto de catalogo hacia compras (CU-13). Es lo unico de catalogo que compras puede usar, junto con
 * {@link ArticulosParaVenta} (lo vigila ModulosArchTest). Todo se resuelve con la ferreteria del request.
 * El costo de la compra pisa el del articulo (ultimo costo, como GDS) y el precio se recalcula con la regla de precios.
 */
public interface ArticulosParaCompra {

    /**
     * Lo que haria la compra con cada articulo, sin escribir nada, por id ascendente. Los ajenos o inexistentes no
     * vienen. Con algun articulo en USD y sin cotizacion guardada responde 409.
     */
    List<CostoDeCompra> planear(Map<Long, BigDecimal> costos);

    /**
     * Pisa el costo de cada articulo, recalcula su precio y guarda historial COMPRA si el costo o el precio cambiaron.
     * Exige una transaccion abierta. Si algun id no existe en la ferreteria lanza IllegalStateException y la
     * transaccion debe revertirse. Devuelve lo aplicado, por id ascendente.
     */
    List<CostoDeCompra> aplicar(Map<Long, BigDecimal> costos, Long usuarioId);
}
