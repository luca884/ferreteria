package com.ferreteria.catalogo.model;

import com.ferreteria.common.exception.DatosInvalidosException;

import java.util.Map;

/**
 * Los ajustes sobre el precio final dejarian el precio en 0 o menos, o por encima del tope de DECIMAL(15,2) con un
 * precio calculado que entra (CU-14). Una edicion a mano del Dueño la responde como cualquier dato invalido (400); un
 * recalculo automatico la atrapa en {@link Articulo} para saltear el articulo y avisar (S7). Es un
 * {@link DatosInvalidosException}: nada que la atrape cambia el 400 de hoy.
 */
final class AjustesFueraDeRangoException extends DatosInvalidosException {

    /** Como sigue el aviso del recalculo automatico: «con sus ajustes quedaria ...». */
    private final String queQuedaria;

    AjustesFueraDeRangoException(String mensaje, String queQuedaria) {
        super(Map.of("ajustesPrecio", mensaje));
        this.queQuedaria = queQuedaria;
    }

    String queQuedaria() {
        return queQuedaria;
    }
}
