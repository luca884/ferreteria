package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.ReconocedorDeColumnas;

import java.util.List;

/**
 * El archivo entero para armar el mapeo: todas las hojas con TODAS sus filas crudas, y la propuesta de mapeo que
 * sale de leer los encabezados (una sugerencia: todos sus campos pueden ser null y solo depende del archivo).
 * {@code columnas} es el ancho de la grilla de la hoja y {@code filaEncabezado} la ultima fila de su encabezado
 * (null si no se reconoce ninguno).
 */
public record MuestraResponse(List<HojaMuestra> hojas, ReconocedorDeColumnas.Propuesta propuesta) {

    /** Una hoja del archivo con sus filas. */
    public record HojaMuestra(int indice, String nombre, int columnas, Integer filaEncabezado, List<FilaMuestra> filas) {}

    /** Una fila con su numero en el archivo y sus celdas como texto. */
    public record FilaMuestra(int numero, List<String> celdas) {}
}
