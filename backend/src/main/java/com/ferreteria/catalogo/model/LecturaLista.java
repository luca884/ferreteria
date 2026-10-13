package com.ferreteria.catalogo.model;

import java.util.List;

/** Lo que se lee de un archivo: nombres de hojas y filas no vacias de la hoja pedida, con su numero original. */
public record LecturaLista(List<String> hojas, List<FilaLeida> filas) {

    public LecturaLista {
        hojas = List.copyOf(hojas);
        filas = List.copyOf(filas);
    }
}
