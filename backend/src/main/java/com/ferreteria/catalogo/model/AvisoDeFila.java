package com.ferreteria.catalogo.model;

/**
 * Un error o una advertencia de la importacion. {@code fila} es la fila del archivo (null en las
 * advertencias generales, como el cambio de encabezado) y {@code codigo} el codigo del proveedor
 * de esa fila (null si la fila no tiene).
 */
public record AvisoDeFila(Integer fila, String codigo, String mensaje) {}
