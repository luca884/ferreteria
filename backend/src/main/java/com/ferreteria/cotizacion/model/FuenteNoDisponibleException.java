package com.ferreteria.cotizacion.model;

/** Una fuente de cotizaciones no respondio bien: el orquestador pasa a la siguiente. */
public class FuenteNoDisponibleException extends RuntimeException {

    public FuenteNoDisponibleException(String mensaje) {
        super(mensaje);
    }

    public FuenteNoDisponibleException(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
