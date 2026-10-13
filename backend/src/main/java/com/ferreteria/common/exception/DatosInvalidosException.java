package com.ferreteria.common.exception;

import java.util.Map;

/**
 * Datos del pedido que no pasan la validacion y que se validan en el servicio (no en el controlador),
 * por ejemplo para que un recurso ajeno responda 404 antes que un 400. Se responde igual que la
 * validacion del cuerpo: 400 con un mensaje por campo.
 */
public class DatosInvalidosException extends RuntimeException {

    private final Map<String, String> errors;

    public DatosInvalidosException(Map<String, String> errors) {
        super("Datos invalidos");
        this.errors = Map.copyOf(errors);
    }

    /** Campo a mensaje para el usuario. */
    public Map<String, String> errors() {
        return errors;
    }
}
