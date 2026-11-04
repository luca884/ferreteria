package com.ferreteria.catalogo.api;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Puerto de catalogo hacia compras (CU-13). Es lo unico de catalogo que compras puede usar, junto con
 * {@link ArticulosParaVenta} (lo vigila ModulosArchTest). Todo se resuelve con la ferreteria del request.
 * El costo de la compra pisa el del articulo (ultimo costo, como GDS) y el precio se recalcula con la regla de precios.
 * Los recargos de la compra pisan los del articulo (vacios, los borran) y el precio sale del costo real.
 */
public interface ArticulosParaCompra {

    /** Un articulo de la ferreteria hallado por el codigo que le puso su proveedor. */
    record ArticuloPorCodigo(String codigoProveedor, Long id, String descripcion, String unidad) {
    }

    /**
     * Valida los recargos con las reglas de los recargos del costo (nombre de 1 a 40, porcentaje de -99,99 a 99,99 y no 0,
     * hasta 5) y los devuelve normalizados (nombre recortado, porcentaje a 2 decimales). Una regla rota lanza un 400 en
     * {@code recargosCosto}. No lee la base.
     */
    List<RecargoDeCompra> validarRecargos(List<RecargoDeCompra> recargos);

    /**
     * Los articulos activos de la ferreteria cuyo codigo de proveedor es uno de {@code codigos}, de ese proveedor. Sin
     * coincidencia, el codigo no viene en la lista.
     */
    List<ArticuloPorCodigo> porCodigoDeProveedor(Long proveedorId, Collection<String> codigos);

    /** {@link #planear(Map, List)} sin recargos. */
    default List<CostoDeCompra> planear(Map<Long, BigDecimal> costos) {
        return planear(costos, List.of());
    }

    /**
     * Lo que haria la compra con cada articulo, sin escribir nada, por id ascendente. Los ajenos o inexistentes no
     * vienen. Con algun articulo en USD y sin cotizacion guardada responde 409.
     */
    List<CostoDeCompra> planear(Map<Long, BigDecimal> costos, List<RecargoDeCompra> recargos);

    /** {@link #aplicar(Map, List, Long)} sin recargos. */
    default List<CostoDeCompra> aplicar(Map<Long, BigDecimal> costos, Long usuarioId) {
        return aplicar(costos, List.of(), usuarioId);
    }

    /**
     * Pisa el costo y los recargos de cada articulo, recalcula su precio y guarda historial COMPRA si el costo o el precio
     * cambiaron. Exige una transaccion abierta. Si algun id no existe en la ferreteria lanza IllegalStateException y la
     * transaccion debe revertirse. Devuelve lo aplicado, por id ascendente.
     */
    List<CostoDeCompra> aplicar(Map<Long, BigDecimal> costos, List<RecargoDeCompra> recargos, Long usuarioId);
}
