package com.ferreteria.catalogo.model;

import java.math.BigDecimal;

/**
 * Campos editables de un articulo, ya normalizados por el servicio (sin espacios sobrantes y con
 * los textos en blanco convertidos en null). No incluye stock actual ni precio final.
 */
public record DatosArticulo(
        String codigoInterno,
        String codigoBarras,
        String codigoProveedor,
        String descripcion,
        Long rubroId,
        Long proveedorId,
        BigDecimal costo,
        BigDecimal margenPorcentaje,
        AlicuotaIva alicuotaIva,
        Unidad unidad,
        BigDecimal stockMinimo,
        String ubicacion,
        boolean activo) {
}
