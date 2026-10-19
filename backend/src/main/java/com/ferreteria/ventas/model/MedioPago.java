package com.ferreteria.ventas.model;

/**
 * Medios de pago. Lista fija: cualquier otro valor se rechaza. CUENTA_CORRIENTE (CC-022) es lo que se vende "a cuenta":
 * suma deuda al cliente en vez de plata a la caja; entra en las ventas pero no en el efectivo esperado. Los nombres caben
 * en forma_pago.medio y pago.medio (VARCHAR(20)).
 */
public enum MedioPago {
    EFECTIVO,
    DEBITO,
    CREDITO,
    TRANSFERENCIA,
    CUENTA_CORRIENTE
}
