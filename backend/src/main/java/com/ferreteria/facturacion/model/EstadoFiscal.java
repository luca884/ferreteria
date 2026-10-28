package com.ferreteria.facturacion.model;

/**
 * El estado de un registro fiscal ({@code comprobante_fiscal.estado}, V32). Lo cambia solo el motor de numeración, con
 * UPDATE condicionales.
 */
public enum EstadoFiscal {
    /** Espera su turno en la serie (FIFO por id), sin número. */
    PENDIENTE,
    /** Con número y CbteFch, y un intento ABIERTO guardado antes de llamar. */
    EN_CURSO,
    /** Timeout de lectura o 5xx: no se sabe si ARCA lo autorizó. Traba la serie hasta verificarlo. */
    EN_VERIFICACION,
    /** Con CAE, vencimiento y resultado A. */
    AUTORIZADO,
    /** ARCA lo rechazó (resultado R): el número se libera. */
    RECHAZADO,
    /** La CbteFch no entraba al enviar: sin número, sin reintento y sin cambiar la fecha. */
    FUERA_DE_FECHA,
    /** Alerta: ARCA tiene ese número con otros datos. Conserva el número y traba la serie. */
    CAE_NO_COINCIDE,
    /** Alerta: ARCA va más de uno atrás (último &lt; N−1). Conserva el número y traba la serie. */
    SERIE_DESINCRONIZADA
}
