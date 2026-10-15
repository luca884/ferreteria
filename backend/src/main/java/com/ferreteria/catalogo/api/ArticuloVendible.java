package com.ferreteria.catalogo.api;

import java.math.BigDecimal;

/**
 * Lo que ventas necesita saber de un articulo para venderlo (C-004). Solo tipos del JDK, para que ventas y
 * stock no dependan de catalogo.model: la unidad y la alicuota viajan por nombre (UNIDAD, METRO...,
 * IVA_21...), y el porcentaje de IVA ya viene resuelto (21.00 significa 21 %).
 */
public record ArticuloVendible(
        Long id,
        String descripcion,
        String unidad,
        boolean admiteDecimales,
        BigDecimal precioFinal,
        String alicuotaIva,
        BigDecimal porcentajeIva,
        boolean activo) {
}
