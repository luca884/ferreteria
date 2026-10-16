package com.ferreteria.catalogo.model;

import java.math.BigDecimal;

/**
 * Articulo nuevo que la lista permite crear. {@code descripcion} ya viene recortada a 200 y
 * {@code costo} es el costo calculado (sin IVA, con bonificaciones y ajustes, en la {@code moneda} del articulo).
 * {@code precioLista} es el valor crudo del archivo. {@code rubroId} es el de la columna de rubro, si la fila lo
 * nombra y existe; null deja el rubro elegido al importar. {@code precioNuevo} es el precio final en pesos con el
 * contexto de ese rubro (o sin rubro), solo para mostrar.
 */
public record AltaPlaneada(
        int fila,
        String codigo,
        String descripcion,
        BigDecimal precioLista,
        BigDecimal costo,
        int hoja,
        Moneda moneda,
        AlicuotaIva alicuotaIva,
        Ajustes ajustes,
        Unidad unidad,
        String codigoBarras,
        Long rubroId,
        BigDecimal precioNuevo) {

    public AltaPlaneada {
        moneda = moneda == null ? Moneda.ARS : moneda;
        alicuotaIva = alicuotaIva == null ? AlicuotaIva.IVA_21 : alicuotaIva;
        ajustes = ajustes == null ? Ajustes.NINGUNO : ajustes;
        unidad = unidad == null ? Unidad.UNIDAD : unidad;
    }

    /** El alta de antes del corte 2: hoja 0, pesos, IVA_21, sin ajustes, UNIDAD, sin barras ni rubro. */
    public AltaPlaneada(int fila, String codigo, String descripcion, BigDecimal precioLista, BigDecimal costo) {
        this(fila, codigo, descripcion, precioLista, costo, 0, null, null, null, null, null, null, null);
    }
}
