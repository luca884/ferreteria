package com.ferreteria.facturacion.motor;

/**
 * Un paso del motor encontró la serie, el registro o el intento distinto de como lo dejó (un UPDATE condicional tocó 0
 * filas): la toma venció y otra instancia siguió. La transacción del paso se revierte y la atención termina sin guardar
 * nada más ({@link Atencion#SERIE_PERDIDA}).
 */
class PasoDescartadoException extends RuntimeException {

    PasoDescartadoException(String mensaje) {
        super(mensaje);
    }
}
