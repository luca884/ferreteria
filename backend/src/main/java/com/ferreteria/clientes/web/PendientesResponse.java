package com.ferreteria.clientes.web;

import com.ferreteria.clientes.service.PendientesCuenta;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Los pendientes de cobro de un cliente (CC-039): su saldo, lo que tiene «a cuenta» y los comprobantes con deuda en orden FIFO.
 * Las tres cosas salen de la misma foto, así que Σ pendiente − aCuenta = saldo. Todos los importes salen con 2 decimales, sea
 * cual sea la escala con que el servicio los calculó.
 */
public record PendientesResponse(BigDecimal saldo, BigDecimal aCuenta, List<Comprobante> comprobantes) {

    /** Un comprobante con deuda: lo cargado a la cuenta, lo ya imputado y lo que queda por cobrar. */
    public record Comprobante(Long comprobanteId, String referencia, LocalDateTime fecha, BigDecimal importe,
            BigDecimal imputado, BigDecimal pendiente) {}

    public static PendientesResponse de(PendientesCuenta cuenta) {
        return new PendientesResponse(dos(cuenta.saldo()), dos(cuenta.aCuenta()), cuenta.comprobantes().stream()
                .map(p -> new Comprobante(p.comprobanteId(), p.referencia(), p.fecha(), dos(p.importe()), dos(p.imputado()),
                        dos(p.pendiente())))
                .toList());
    }

    private static BigDecimal dos(BigDecimal importe) {
        return importe.setScale(2);
    }
}
