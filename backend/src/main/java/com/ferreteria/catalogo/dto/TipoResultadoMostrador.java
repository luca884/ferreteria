package com.ferreteria.catalogo.dto;

/** Que encontro la busqueda del mostrador (CU-06). */
public enum TipoResultadoMostrador {
    /** Un unico articulo activo con ese codigo exacto: la pantalla muestra su ficha. */
    EXACTO,
    /** Varios articulos (o un solo resultado parcial, con los dados de baja al final): la pantalla muestra la lista. */
    LISTA,
    SIN_RESULTADOS
}
