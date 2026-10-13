package com.ferreteria.catalogo.dto;

/** Que encontro la busqueda del mostrador (CU-06). */
public enum TipoResultadoMostrador {
    /** Un unico articulo activo con ese codigo exacto: la pantalla muestra su ficha. */
    EXACTO,
    /** Varios articulos (o un solo resultado parcial): la pantalla muestra la lista. */
    LISTA,
    /** El codigo exacto es de un articulo dado de baja y no hay ninguno activo con ese codigo. */
    DADO_DE_BAJA,
    SIN_RESULTADOS
}
