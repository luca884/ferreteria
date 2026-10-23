package com.ferreteria.ventas.web;

import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.Imputar;
import com.ferreteria.ventas.model.LimitesVenta;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.service.CobranzaPedido;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Lo que manda la pantalla de cobranza (CC-029). No tiene ferretería ni usuario: salen del JWT. Aquí solo se valida la forma, con
 * los mismos campos y mensajes que {@code RegistroCobranza} (un 400 es un 400 venga de donde venga); las reglas que dependen de
 * la base (cliente, caja, comprobantes, deuda) las decide el servicio. {@code imputaciones} ausente o vacía es válido: todo el
 * total queda «a cuenta». El medio viaja como texto para que uno inválido (DEBITO, CUENTA_CORRIENTE...) diga «Elegí efectivo o
 * transferencia» y no la lista de los medios de una venta.
 */
public record CobranzaRequest(
        @NotNull(message = "Falta la clave del cobro.")
        @Pattern(regexp = "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                message = "La clave del cobro no es válida.")
        String clave,

        @NotNull(message = "Elegí el cliente a cobrar.")
        Long clienteId,

        @NotNull(message = MENSAJE_MEDIO)
        @Pattern(regexp = "EFECTIVO|TRANSFERENCIA", message = MENSAJE_MEDIO)
        String medio,

        @NotNull(message = "Falta el total.")
        @DecimalMin(value = "0.01", message = "El total tiene que ser mayor que cero.")
        @DecimalMax(value = LimitesVenta.IMPORTE_MAXIMO_TEXTO, message = "El total supera el máximo permitido.")
        @Digits(integer = 13, fraction = 2, message = "El total admite hasta 2 decimales.")
        BigDecimal total,

        @Size(max = 200, message = "Un cobro admite hasta 200 comprobantes.")
        List<@NotNull(message = "Hay un renglón vacío en la imputación.") @Valid ImputacionRequest> imputaciones) {

    static final String MENSAJE_MEDIO = "Elegí efectivo o transferencia";

    /**
     * Cuánto del cobro se aplica a una deuda: a un comprobante ({@code comprobanteId}) o, desde GDS-20, a un Recargo
     * ({@code ajusteId}); exactamente uno de los dos ({@link UnaSolaDeuda}, con las mismas claves y mensajes que
     * {@code RegistroCobranza.validarForma}: «Elegí el comprobante.» en {@code imputaciones[i].comprobanteId} si no viene
     * ninguno, junto con los demás errores del renglón).
     */
    @UnaSolaDeuda
    public record ImputacionRequest(
            Long comprobanteId,

            Long ajusteId,

            @NotNull(message = "Falta el importe.")
            @DecimalMin(value = "0.01", message = "El importe tiene que ser mayor que cero.")
            @Digits(integer = 13, fraction = 2, message = "El importe admite hasta 2 decimales.")
            BigDecimal importe) {

        /** A un comprobante (la forma de antes de los Recargos). */
        public ImputacionRequest(Long comprobanteId, BigDecimal importe) {
            this(comprobanteId, null, importe);
        }
    }

    public CobranzaPedido aPedido() {
        return new CobranzaPedido(clave, clienteId, MedioPago.valueOf(medio), total,
                imputaciones == null ? List.of()
                        : imputaciones.stream().map(i -> new Imputar(i.comprobanteId(), i.ajusteId(), i.importe())).toList());
    }
}
