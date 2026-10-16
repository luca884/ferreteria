package com.ferreteria.catalogo.model;

import java.math.BigDecimal;

/**
 * Fila "sin cambio": el costo y el precio final quedan igual y se registra el precio de lista del proveedor.
 * {@code costo} es el costo calculado (igual al del articulo). {@code alicuotaNueva} y {@code ajustesNuevos} son lo
 * que la fila deja guardado cuando la alicuota o los ajustes cambian sin mover el precio; null si no cambian.
 */
public record PrecioListaPlaneado(
        Long articuloId,
        BigDecimal precioLista,
        int hoja,
        int fila,
        String codigoProveedor,
        String descripcion,
        BigDecimal costo,
        AlicuotaIva alicuotaNueva,
        Ajustes ajustesNuevos) {

    /** El "sin cambio" de antes del corte 2: sin lugar en el archivo ni cambios de IVA o ajustes. */
    public PrecioListaPlaneado(Long articuloId, BigDecimal precioLista) {
        this(articuloId, precioLista, 0, 0, null, null, null, null, null);
    }
}
