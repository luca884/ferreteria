package com.ferreteria.catalogo.api;

import java.math.BigDecimal;

/**
 * El efecto de una compra (CU-13) sobre el costo y el precio de un articulo. Solo tipos del JDK: la unidad y la moneda
 * viajan por nombre (UNIDAD, METRO..., ARS, USD). {@code advertencia} es el texto de variacion fuerte (+-50 %, el mismo
 * de CU-04 y CU-05) o null. El costo real es el costo con los recargos del articulo: el anterior, con los que tenia; el
 * nuevo, con los de la compra. {@code stockActual} es el stock del articulo al momento de planear y {@code alicuotaIva}
 * su porcentaje (21, 10.5...).
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
        String advertencia,
        BigDecimal costoRealAnterior,
        BigDecimal costoRealNuevo,
        BigDecimal stockActual,
        BigDecimal alicuotaIva) {
}
