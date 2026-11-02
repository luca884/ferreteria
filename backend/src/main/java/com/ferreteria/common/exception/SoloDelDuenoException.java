package com.ferreteria.common.exception;

/**
 * 403 de una regla de negocio que solo el Dueño puede saltar, con un mensaje que le dice al usuario qué pasó. A diferencia
 * de {@code AccessDeniedException}, cuyo mensaje se reemplaza por uno genérico, este llega tal cual a la pantalla.
 */
public class SoloDelDuenoException extends RuntimeException {

    public SoloDelDuenoException(String message) {
        super(message);
    }
}
