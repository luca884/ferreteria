package com.ferreteria.clientes.service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Los pendientes de la cuenta de un cliente (CC-039): su saldo, lo que tiene «a cuenta» (crédito sin imputar) y los
 * comprobantes con pendiente mayor que cero en orden FIFO. Las tres cosas salen de la MISMA foto de la base, así que la
 * respuesta cumple I2 por sí sola: Σ pendientes − aCuenta = saldo, con los ajustes manuales (GDS-20): un Recargo es una partida
 * pendiente como un comprobante ({@code ajusteId} en vez de {@code comprobanteId}) y un Descuento suma al «a cuenta».
 */
public record PendientesCuenta(BigDecimal saldo, BigDecimal aCuenta, List<Pendiente> comprobantes) {

    /**
     * Una deuda: lo que se cargó a la cuenta, lo ya imputado y lo que queda. Exactamente uno de {@code comprobanteId} (una
     * venta) y {@code ajusteId} (un Recargo).
     */
    public record Pendiente(Long comprobanteId, Long ajusteId, String referencia, LocalDateTime fecha, BigDecimal importe,
            BigDecimal imputado, BigDecimal pendiente) {

        /** Un comprobante (la forma de antes de los Recargos). */
        public Pendiente(Long comprobanteId, String referencia, LocalDateTime fecha, BigDecimal importe, BigDecimal imputado,
                BigDecimal pendiente) {
            this(comprobanteId, null, referencia, fecha, importe, imputado, pendiente);
        }
    }
}
