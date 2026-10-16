package com.ferreteria.catalogo.model;

/** La lista ya no da lo que mostro la vista previa para lo elegido: es un 409 y no se escribe nada. */
public class ListaCambioException extends RuntimeException {

    public ListaCambioException(String mensaje) {
        super(mensaje);
    }
}
