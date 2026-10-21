package com.ferreteria.common.exception;

/**
 * 503. Una fachada que reintenta por deadlock o timeout de lock agotó sus intentos: es una falla TRANSITORIA de contención, no un
 * error del servidor. La respuesta le dice al cliente que reintente (con {@code Retry-After}); si la operación lleva una clave de
 * idempotencia, reintentarla es seguro (devuelve lo ya registrado). La causa es el deadlock o el timeout original, para el log.
 * Solo la lanzan las fachadas ({@code VentaService}, {@code CobranzaService}, {@code AnulacionService}, {@code RemitoService},
 * {@code PresupuestoService}) al agotar los intentos: cualquier otro error sigue
 * siendo un 500.
 */
public class SistemaOcupadoException extends RuntimeException {

    public static final String MENSAJE = "El sistema está ocupado, reintentá.";

    /** Segundos que se sugieren esperar antes de reintentar (cabecera {@code Retry-After}). */
    public static final int REINTENTAR_EN_SEGUNDOS = 2;

    public SistemaOcupadoException(Throwable causa) {
        super(MENSAJE, causa);
    }
}
