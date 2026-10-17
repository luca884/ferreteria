package com.ferreteria.catalogo.model;

/**
 * Se pidio editar los ajustes de un articulo que no tiene lista de proveedor (alta manual, sin proveedor o sin base
 * guardada). Excepcion de dominio: el servicio la traduce a 409 (lote 4). El texto es unico para todos los casos.
 */
public class ArticuloSinListaException extends IllegalStateException {

    public static final String MENSAJE = "Este artículo no tiene lista de proveedor. "
            + "Los ajustes se usan solo con artículos que vienen de una lista.";

    public ArticuloSinListaException() {
        super(MENSAJE);
    }
}
