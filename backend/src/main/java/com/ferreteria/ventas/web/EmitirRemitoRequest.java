package com.ferreteria.ventas.web;

import com.ferreteria.ventas.model.LimitesVenta;
import com.ferreteria.ventas.service.ConfirmarVenta;
import com.ferreteria.ventas.service.EmitirRemito;
import com.ferreteria.ventas.web.ConfirmarVentaRequest.RenglonRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Lo que manda el mostrador al emitir un remito (RM-004). Como la venta pero sin forma de pago: tiene el nombre del
 * cliente y las observaciones. No tiene ferreteria ni usuario: salen del JWT. Aca solo se valida la forma; las reglas
 * que dependen de los articulos las hace el servicio, con el orden de precedencia de la venta.
 */
public record EmitirRemitoRequest(
        @NotNull(message = "Falta la clave del remito.")
        @Pattern(regexp = "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                message = "La clave del remito no es válida.")
        String clave,

        @NotEmpty(message = "El remito no tiene artículos.")
        @Size(max = 500, message = "El remito admite hasta 500 renglones.")
        List<@NotNull(message = "Hay un renglón vacío en el remito.") @Valid RenglonRequest> renglones,

        @NotNull(message = "Falta el total.")
        @DecimalMin(value = "0.00", message = "El total no puede ser negativo.")
        @DecimalMax(value = LimitesVenta.IMPORTE_MAXIMO_TEXTO,
                message = "El total es demasiado grande: el máximo es $ 9.999.999.999.999,99.")
        BigDecimal totalMostrado,

        /** Descuento global en %, aplicado a cada renglon. Ausente es 0. El rango lo valida el servicio. */
        @Digits(integer = 5, fraction = 2, message = "El descuento admite hasta 2 decimales.")
        BigDecimal descuentoPorcentaje,

        @Size(max = 80, message = "El nombre del cliente admite hasta 80 caracteres.")
        String clienteNombre,

        @Size(max = 200, message = "Las observaciones admiten hasta 200 caracteres.")
        String observaciones) {

    public EmitirRemito aPedido() {
        return new EmitirRemito(clave, renglones.stream()
                .map(r -> new ConfirmarVenta.Renglon(r.articuloId(), r.cantidad(), r.precioUnitario(),
                        r.descuentoPorcentaje()))
                .toList(), totalMostrado, descuentoPorcentaje, clienteNombre, observaciones);
    }
}
