package com.ferreteria.auth.service;

public class CredencialesInvalidasException extends RuntimeException {

    public CredencialesInvalidasException() {
        super("Email o contrasena incorrectos");
    }
}
