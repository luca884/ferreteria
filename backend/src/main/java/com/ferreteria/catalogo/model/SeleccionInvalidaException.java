package com.ferreteria.catalogo.model;

/** La seleccion de filas no se puede aplicar (por ejemplo, una fila que no existe o con error): es un 400. */
public class SeleccionInvalidaException extends RuntimeException {

    public SeleccionInvalidaException(String mensaje) {
        super(mensaje);
    }
}
