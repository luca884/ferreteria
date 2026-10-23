package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.Articulo;
import com.ferreteria.catalogo.model.Unidad;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Lo que ve el mostrador de un articulo: sin costo, margen ni datos de gestion (el Vendedor no los ve). activo
 * marca a los dados de baja, que salen en la busqueda pero no se pueden agregar al ticket. costoAl es desde cuando esta
 * al dia el costo (GDS-05), como la «F. Modif.» de la busqueda de la venta de GDS: una fecha, no el costo.
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
        boolean activo,
        LocalDate costoAl) {

    /** El precio final es el guardado: no se recalcula. */
    public static ArticuloMostradorResponse from(Articulo articulo, LocalDate costoAl) {
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
                articulo.isActivo(),
                costoAl);
    }
}
