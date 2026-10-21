package com.ferreteria.auth.service;

public class CredencialesInvalidasException extends RuntimeException {

    public CredencialesInvalidasException() {
        super("Email o contraseña incorrectos");
    }

    /** Para las variantes que explican algo más (ver {@link FerreteriaSuspendidaException}). */
    protected CredencialesInvalidasException(String mensaje) {
        super(mensaje);
    }
}
