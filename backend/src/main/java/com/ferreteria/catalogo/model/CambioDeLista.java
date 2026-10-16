package com.ferreteria.catalogo.model;

/**
 * Lo que dejo una fila de la lista aplicada a un articulo existente: el cambio de costo y de precio y, solo si la
 * moneda cambio, la moneda que tenia antes (para el historial). En un cambio de moneda el cambio se fuerza: existe
 * aunque el costo y el precio queden iguales, asi siempre deja historial.
 */
public record CambioDeLista(CambioPrecio cambio, Moneda monedaAnterior) {

    public boolean cambioLaMoneda() {
        return monedaAnterior != null;
    }
}
