package com.ferreteria.catalogo.api;

import java.math.BigDecimal;

/**
 * El efecto de una compra (CU-13) sobre el costo y el precio de un articulo. Solo tipos del JDK: la unidad y la moneda
 * viajan por nombre (UNIDAD, METRO..., ARS, USD). {@code advertencia} es el texto de variacion fuerte (+-50 %, el mismo
 * de CU-04 y CU-05) o null.
 */
public record CostoDeCompra(
        Long articuloId,
        String codigoInterno,
        String descripcion,
        String unidad,
        String moneda,
        boolean activo,
        BigDecimal costoAnterior,
        BigDecimal costoNuevo,
        BigDecimal precioAnterior,
        BigDecimal precioNuevo,
        String advertencia) {
}
