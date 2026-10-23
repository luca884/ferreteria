package com.ferreteria.ventas.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ferreteria.ventas.service.CobranzaRechazadaException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Cuerpo de los rechazos 409 y 422 de una cobranza (CC-029, CC-030, CC-035, CC-038): los campos de {@code ApiError} (el front lee
 * {@code message}, como en las ventas) más el {@code motivo} y, solo en IMPUTACION_EXCEDE_DEUDA (409), la deuda ACTUAL de los
 * comprobantes afectados para que la pantalla se refresque. En RECIBO_YA_ANULADO (409) lleva quién ({@code anuladoPor}) y cuándo
 * ({@code fechaAnulacion}) se anuló. La clave del mensaje sigue siendo {@code message} (la que lee el front). Lo que no aplica a un
 * motivo no se serializa.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CobranzaRechazadaResponse(
        int status, String message, Instant timestamp, String motivo, List<DeudaResponse> comprobantes, String anuladoPor,
        LocalDateTime fechaAnulacion) {

    /** La deuda que le queda a un comprobante o, con {@code ajusteId} (GDS-20), a un Recargo, con 2 decimales. */
    public record DeudaResponse(Long comprobanteId, Long ajusteId, String referencia, BigDecimal pendiente) {

        /** Un comprobante (la forma de antes de los Recargos). */
        public DeudaResponse(Long comprobanteId, String referencia, BigDecimal pendiente) {
            this(comprobanteId, null, referencia, pendiente);
        }
    }

    public static CobranzaRechazadaResponse de(CobranzaRechazadaException e) {
        List<DeudaResponse> deudas = e.deudas().isEmpty() ? null
                : e.deudas().stream()
                        .map(d -> new DeudaResponse(d.comprobanteId(), d.ajusteId(), d.referencia(), d.pendiente().setScale(2)))
                        .toList();
        return new CobranzaRechazadaResponse(e.motivo().estadoHttp(), e.getMessage(), Instant.now(), e.motivo().name(), deudas,
                e.anuladoPor(), e.fechaAnulacion());
    }
}
