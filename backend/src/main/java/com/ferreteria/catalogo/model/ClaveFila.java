package com.ferreteria.catalogo.model;

/**
 * Una fila de datos del archivo por su lugar: la hoja (base 0) y la fila (1-based, como la muestra la grilla). La
 * seleccion de la vista previa viaja con estas claves y no con el id de un articulo: no puede apuntar a otra ferreteria.
 */
public record ClaveFila(int hoja, int fila) {}
