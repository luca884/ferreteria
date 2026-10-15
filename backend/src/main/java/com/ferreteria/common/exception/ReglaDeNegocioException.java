package com.ferreteria.common.exception;

/** 422. La operacion es valida en forma pero una regla de negocio la impide (p. ej. ajustar un articulo de baja). */
public class ReglaDeNegocioException extends RuntimeException {

    public ReglaDeNegocioException(String message) {
        super(message);
    }
}
