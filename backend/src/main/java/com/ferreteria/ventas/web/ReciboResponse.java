package com.ferreteria.ventas.web;

import com.ferreteria.ventas.model.EstadoRecibo;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.model.Recibo;
import com.ferreteria.ventas.service.CobroRegistrado;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * El recibo de una cobranza (CC-036, CC-038): lo que se imprime y lo que devuelve tanto la cobranza nueva (201) como la repetida
 * (200) y la consulta del recibo. {@code aCuenta} es lo que quedó sin imputar (total − Σ imputaciones). No lleva el saldo ni «lo
 * que resta»: no se guardan, y la repetida tiene que decir lo mismo que la original. Por eso todos los importes salen con 2
 * decimales: la original conserva la escala del pedido (300) y la repetida la de la base (300.00), y el JSON no puede distinguirlas.
 * CC-069: lleva el {@code estado} y, si está ANULADO, cuándo, quién (nombre) y por qué ({@code null} los tres si está EMITIDO). Las
 * imputaciones son las ORIGINALES (también las que ya no son vigentes) y {@code aCuenta} describe el recibo como se emitió: así se
 * reimprime un recibo anulado.
 */
public record ReciboResponse(
        Long id, Long numero, LocalDateTime fecha, Long clienteId, String clienteNombre, MedioPago medio, BigDecimal total,
        BigDecimal aCuenta, String cobradoPor, List<ImputacionResponse> imputaciones, EstadoRecibo estado,
        LocalDateTime fechaAnulacion, String anuladoPor, String motivoAnulacion) {

    /** A qué comprobante se imputó y cuánto; la referencia es la copiada al cobrar («Venta N.º n»). */
    public record ImputacionResponse(Long comprobanteId, String referencia, BigDecimal importe) {}

    public static ReciboResponse de(CobroRegistrado cobro) {
        Recibo recibo = cobro.recibo();
        return new ReciboResponse(recibo.getId(), recibo.getNumero(), recibo.getFecha(), recibo.getClienteId(),
                recibo.getClienteNombre(), recibo.getMedio(), dos(recibo.getTotal()), dos(cobro.aCuenta()), cobro.cobradoPor(),
                cobro.imputaciones().stream()
                        .map(i -> new ImputacionResponse(i.comprobanteId(), i.referencia(), dos(i.importe())))
                        .toList(),
                recibo.getEstado(), recibo.getFechaAnulacion(), recibo.isAnulado() ? cobro.anuladoPor() : null,
                recibo.getMotivoAnulacion());
    }

    private static BigDecimal dos(BigDecimal importe) {
        return importe.setScale(2);
    }
}
