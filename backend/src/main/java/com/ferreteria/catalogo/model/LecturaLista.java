package com.ferreteria.catalogo.model;

import java.util.List;

/**
 * Lo que se lee de un archivo: los nombres de todas las hojas (por posicion) y las filas no vacias de las hojas
 * leidas, con su numero original y la hoja de cada una.
 */
public record LecturaLista(List<String> hojas, List<FilaLeida> filas) {

    public LecturaLista {
        hojas = List.copyOf(hojas);
        filas = List.copyOf(filas);
    }

    /** Las filas de una hoja, en el orden de lectura. */
    public List<FilaLeida> filasDeHoja(int hoja) {
        return filas.stream().filter(fila -> fila.hoja() == hoja).toList();
    }
}
