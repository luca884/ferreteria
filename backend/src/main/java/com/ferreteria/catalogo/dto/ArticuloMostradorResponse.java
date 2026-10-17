package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.Unidad;

import java.math.BigDecimal;

/**
 * Lo que ve el mostrador de un articulo: sin costo, margen ni datos de gestion (el Vendedor no los ve). activo
 * marca a los dados de baja, que salen en la busqueda pero no se pueden agregar al ticket.
 */
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
        String ubicacion,
        boolean activo) {

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
                articulo.getUbicacion(),
                articulo.isActivo());
    }
}
