package com.ferreteria.common.exception;

/** 403 al intentar desactivar una cuenta demo o cambiarle el rol. */
public class EstadoCuentaDemoProtegidoException extends RuntimeException {

    public EstadoCuentaDemoProtegidoException() {
        super("Es una cuenta de demostración: no se puede desactivar ni cambiar su rol.");
    }
}
