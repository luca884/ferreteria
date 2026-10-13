package com.ferreteria.catalogo.model;

import java.math.BigDecimal;

/**
 * Articulo nuevo que la lista permite crear. {@code descripcion} ya viene recortada a 200 y
 * {@code costo} es el costo calculado (sin IVA, con bonificaciones). {@code precioLista} es el valor
 * crudo del archivo.
 */
public record AltaPlaneada(int fila, String codigo, String descripcion, BigDecimal precioLista, BigDecimal costo) {}
