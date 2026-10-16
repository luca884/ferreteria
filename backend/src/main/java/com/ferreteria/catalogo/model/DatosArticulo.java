package com.ferreteria.catalogo.model;

import java.math.BigDecimal;

/**
 * Campos editables de un articulo, ya normalizados por el servicio (sin espacios sobrantes y con
 * los textos en blanco convertidos en null). No incluye stock actual ni precio final.
 *
 * @param moneda moneda del costo; el precio final siempre se calcula y guarda en pesos
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
        boolean activo,
        Moneda moneda) {

    public DatosArticulo {
        moneda = moneda == null ? Moneda.ARS : moneda;
    }

    /** Articulo en pesos: es lo que usan la importacion de listas y todo lo que no elige moneda. */
    public DatosArticulo(
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
        this(codigoInterno, codigoBarras, codigoProveedor, descripcion, rubroId, proveedorId, costo,
                margenPorcentaje, alicuotaIva, unidad, stockMinimo, ubicacion, activo, Moneda.ARS);
    }
}
