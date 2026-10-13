package com.ferreteria.catalogo.model;

import java.math.BigDecimal;

/** Foto de un articulo del proveedor al momento de planificar: el planificador no toca entidades. */
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
        boolean activo) {

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
            articulo.isActivo());
    }
}
