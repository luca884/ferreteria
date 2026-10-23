package com.ferreteria.clientes.web;

import com.ferreteria.clientes.service.AjusteRegistrado;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * El ajuste registrado (GDS-20): su id y número, la referencia («Recargo N.º n» / «Descuento N.º n»), lo que se ajustó, el
 * saldo del cliente DESPUÉS del ajuste y las imputaciones del Descuento (vacías en un Recargo), cada una a un comprobante o a
 * un Recargo. Los importes salen con 2 decimales.
 */
public record AjusteCuentaResponse(Long ajusteId, Long numero, String referencia, String tipo, BigDecimal importe, String motivo,
        LocalDateTime fecha, BigDecimal saldo, List<ImputacionResponse> imputaciones) {

    /** Una imputación del Descuento: a un comprobante (con {@code comprobanteId}) o a un Recargo (con {@code ajusteId}). */
    public record ImputacionResponse(Long comprobanteId, Long ajusteId, String referencia, BigDecimal importe) {}

    public static AjusteCuentaResponse de(AjusteRegistrado registrado) {
        var ajuste = registrado.ajuste();
        return new AjusteCuentaResponse(ajuste.getId(), ajuste.getNumero(), ajuste.referencia(), ajuste.getTipo().name(),
                ajuste.getImporte().setScale(2), ajuste.getMotivo(), ajuste.getFecha(), registrado.saldo().setScale(2),
                registrado.imputaciones().stream()
                        .map(i -> new ImputacionResponse(i.comprobanteId(), i.ajusteId(), i.referencia(), i.importe().setScale(2)))
                        .toList());
    }
}
