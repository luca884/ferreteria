package com.ferreteria.clientes.web;

import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionInvalidaException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * El 409 de un ajuste cuya imputación excede la deuda actual de alguna partida (GDS-20): la misma forma que el de la cobranza
 * ({@code CobranzaRechazadaResponse}: {@code status}, {@code message}, {@code timestamp}, {@code motivo} y las deudas
 * {@code comprobantes}), con {@code ajusteId} además de {@code comprobanteId} en cada deuda (uno de los dos es null) para que
 * la pantalla se refresque.
 */
public record ImputacionExcedidaResponse(int status, String message, Instant timestamp, String motivo,
        List<DeudaResponse> comprobantes) {

    /** La deuda que le queda a un comprobante o a un Recargo, con 2 decimales. */
    public record DeudaResponse(Long comprobanteId, Long ajusteId, String referencia, BigDecimal pendiente) {}

    public static ImputacionExcedidaResponse de(ImputacionInvalidaException e, String mensaje) {
        return new ImputacionExcedidaResponse(409, mensaje, Instant.now(), "IMPUTACION_EXCEDE_DEUDA",
                e.deudas().stream()
                        .map(d -> new DeudaResponse(d.comprobanteId(), d.ajusteId(), d.referencia(), d.pendiente().setScale(2)))
                        .toList());
    }
}
