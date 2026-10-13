package com.ferreteria.catalogo.dto;

import java.util.List;

/**
 * Todas las filas crudas de una hoja, para que el Dueño vea la grilla completa y arme el mapeo. {@code hojas}
 * son los nombres de todas las hojas, {@code hoja} el indice de la mostrada y {@code columnas} el ancho de la grilla.
 */
public record MuestraResponse(List<String> hojas, int hoja, int columnas, List<FilaMuestra> filas) {

    /** Una fila con su numero en el archivo y sus celdas como texto. */
    public record FilaMuestra(int numero, List<String> celdas) {}
}
