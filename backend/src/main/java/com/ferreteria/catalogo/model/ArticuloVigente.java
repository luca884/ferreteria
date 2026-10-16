package com.ferreteria.catalogo.model;

import java.math.BigDecimal;

/**
 * Foto de un articulo del proveedor al momento de planificar: el planificador no toca entidades.
 * {@code costo} esta en la {@code moneda} del articulo; {@code precioFinal} siempre en pesos.
 */
public record ArticuloVigente(
        Long id,
        String codigoInterno,
        String codigoProveedor,
        String descripcion,
        BigDecimal costo,
        BigDecimal precioFinal,
        BigDecimal margenPorcentaje,
        Long rubroId,
        AlicuotaIva alicuotaIva,
        boolean activo,
        Moneda moneda) {

    /** Articulo en pesos. */
    public ArticuloVigente(Long id, String codigoInterno, String codigoProveedor, String descripcion,
                           BigDecimal costo, BigDecimal precioFinal, BigDecimal margenPorcentaje, Long rubroId,
                           AlicuotaIva alicuotaIva, boolean activo) {
        this(id, codigoInterno, codigoProveedor, descripcion, costo, precioFinal, margenPorcentaje, rubroId,
            alicuotaIva, activo, Moneda.ARS);
    }

    public static ArticuloVigente de(Articulo articulo) {
        return new ArticuloVigente(
            articulo.getId(),
            articulo.getCodigoInterno(),
            articulo.getCodigoProveedor(),
            articulo.getDescripcion(),
            articulo.getCosto(),
            articulo.getPrecioFinal(),
            articulo.getMargenPorcentaje(),
            articulo.getRubroId(),
            articulo.getAlicuotaIva(),
            articulo.isActivo(),
            articulo.getMoneda());
    }
}
