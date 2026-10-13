package com.ferreteria.common.exception;

/** Pedido que no se puede atender por culpa de lo que mando el cliente; el mensaje es para el usuario. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
