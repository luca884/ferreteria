package com.ferreteria.clientes.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Los pendientes de la cuenta de un cliente (CC-039): su saldo, lo que tiene «a cuenta» (crédito sin imputar) y los
 * comprobantes con pendiente mayor que cero en orden FIFO. Las tres cosas salen de la MISMA foto de la base, así que la
 * respuesta cumple I2 por sí sola: Σ pendientes − aCuenta = saldo (sin ajustes manuales).
 */
public record PendientesCuenta(BigDecimal saldo, BigDecimal aCuenta, List<Pendiente> comprobantes) {

    /** Un comprobante con deuda: lo que se cargó a la cuenta, lo ya imputado y lo que queda. */
    public record Pendiente(Long comprobanteId, String referencia, LocalDateTime fecha, BigDecimal importe,
            BigDecimal imputado, BigDecimal pendiente) {}
}
