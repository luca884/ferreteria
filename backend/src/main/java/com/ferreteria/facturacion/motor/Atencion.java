package com.ferreteria.facturacion.motor;

/** Cómo terminó una atención de una serie ({@link MotorFiscal#atender}). */
public enum Atencion {
    /** No quedan pendientes: se atendió todo lo que había. */
    TERMINADA,
    /** Otra instancia tiene tomada la serie: no se hizo nada. */
    OCUPADA,
    /** Una llamada no llegó o no se sabe si se procesó: la atención se corta y sigue en la próxima. */
    SIN_RESPUESTA,
    /** La serie tiene una alerta (CAE_NO_COINCIDE o SERIE_DESINCRONIZADA): no avanza hasta que la resuelva una persona. */
    TRABADA,
    /** La serie se perdió a mitad de camino (venció la toma y la tomó otra instancia): lo de esta no se guardó. */
    SERIE_PERDIDA,
    /**
     * Se llegó al límite de tiempo de la atención antes de empezar otro registro (el tope de la pasada del job, rama 7): lo
     * que quede pendiente sigue en la próxima. La venta atiende sin límite y nunca la recibe.
     */
    SIN_TIEMPO
}
