package com.ferreteria.facturacion.model;

/**
 * El estado de un intento ({@code intento_fiscal.estado}, V32): un {@code FECAESolicitar}. ABIERTO, EN_VERIFICACION y
 * ALERTA están abiertos y traban la serie (a lo sumo uno por serie, por el UNIQUE funcional); AUTORIZADO, RECHAZADO y
 * NO_LLEGO están cerrados.
 */
public enum EstadoIntento {
    /** Guardado antes de llamar; la llamada está en vuelo o se cortó sin respuesta. */
    ABIERTO,
    /** Sin respuesta segura: se resuelve con FECompUltimoAutorizado y FECompConsultar. */
    EN_VERIFICACION,
    /** Necesita a una persona (CAE que no coincide o serie desincronizada). */
    ALERTA,
    AUTORIZADO,
    RECHAZADO,
    /** No llegó a ARCA (timeout de conexión), o la verificación dio N−1 y se reenvía en un intento nuevo. */
    NO_LLEGO
}
