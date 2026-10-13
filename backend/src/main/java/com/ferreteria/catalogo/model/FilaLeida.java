package com.ferreteria.catalogo.model;

import java.util.List;

/** Una fila de la lista con su numero original (1-based: fila de Excel o registro del CSV). */
public record FilaLeida(int numero, List<Celda> celdas) {

    public FilaLeida {
        celdas = List.copyOf(celdas);
    }

    /** La celda en la columna {@code indice} (0-based); fuera de rango es {@link Celda#VACIA}. */
    public Celda celda(int indice) {
        return indice >= 0 && indice < celdas.size() ? celdas.get(indice) : Celda.VACIA;
    }

    public boolean vacia() {
        return celdas.stream().allMatch(Celda::vacia);
    }
}
