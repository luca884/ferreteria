package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.Unidad;

import java.math.BigDecimal;

/** Lo que ve el mostrador de un articulo: sin costo, margen ni datos de gestion (el Vendedor no los ve). */
public record ArticuloMostradorResponse(
        Long id,
        String codigoInterno,
        String codigoBarras,
        String codigoProveedor,
        String descripcion,
        BigDecimal precioFinal,
        Unidad unidad,
        BigDecimal stockActual,
        BigDecimal stockMinimo,
        String ubicacion) {

    /** El precio final es el guardado: no se recalcula. */
    public static ArticuloMostradorResponse from(Articulo articulo) {
        return new ArticuloMostradorResponse(
                articulo.getId(),
                articulo.getCodigoInterno(),
                articulo.getCodigoBarras(),
                articulo.getCodigoProveedor(),
                articulo.getDescripcion(),
                articulo.getPrecioFinal(),
                articulo.getUnidad(),
                articulo.getStockActual(),
                articulo.getStockMinimo(),
                articulo.getUbicacion());
    }
}
