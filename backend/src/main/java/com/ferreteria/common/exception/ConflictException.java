package com.ferreteria.common.exception;

import java.util.Map;

/** 409. Opcionalmente con un mensaje por campo, para que la pantalla marque cada dato repetido. */
public class ConflictException extends RuntimeException {

    private final Map<String, String> errors;

    public ConflictException(String message) {
        this(message, Map.of());
    }

    public ConflictException(String message, Map<String, String> errors) {
        super(message);
        this.errors = Map.copyOf(errors);
    }

    /** Campo a mensaje para el usuario; vacio si el conflicto no es de un campo puntual. */
    public Map<String, String> errors() {
        return errors;
    }
}
