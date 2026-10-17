package com.ferreteria.catalogo.model;

import java.math.BigDecimal;

/**
 * Fila "sin cambio": no cambia nada del articulo (ni costo, ni precio final, ni IVA, ni ajustes). Aplicar solo anota la
 * importacion y registra el precio de lista del proveedor. Una fila que cambia el IVA, los ajustes o el precio es una
 * correccion ({@link CambioPlaneado}), aunque el precio final no se mueva. {@code costo} es el costo calculado (igual al
 * del articulo).
 */
public record PrecioListaPlaneado(
        Long articuloId,
        BigDecimal precioLista,
        int hoja,
        int fila,
        String codigoProveedor,
        String descripcion,
        BigDecimal costo) {

    /** El "sin cambio" de antes del corte 2: sin lugar en el archivo. */
    public PrecioListaPlaneado(Long articuloId, BigDecimal precioLista) {
        this(articuloId, precioLista, 0, 0, null, null, null);
    }
}
