package com.ferreteria.ventas.web;

import com.ferreteria.ventas.model.LimitesVenta;
import com.ferreteria.ventas.web.ConfirmarVentaRequest.RenglonRequest;
import com.ferreteria.ventas.service.ConfirmarVenta;
import com.ferreteria.ventas.service.EmitirPresupuesto;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Lo que manda el mostrador al guardar un presupuesto (PP-003). Como la venta pero sin forma de pago: tiene los dias de
 * validez, el nombre del cliente y las observaciones. No tiene ferreteria ni usuario: salen del JWT. Aca solo se valida
 * la forma; las reglas que dependen de los articulos las hace el servicio, con el orden de precedencia de la venta.
 */
public record EmitirPresupuestoRequest(
        @NotNull(message = "Falta la clave del presupuesto.")
        @Pattern(regexp = "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                message = "La clave del presupuesto no es válida.")
        String clave,

        @NotEmpty(message = "El presupuesto no tiene artículos.")
        @Size(max = 500, message = "El presupuesto admite hasta 500 renglones.")
        List<@NotNull(message = "Hay un renglón vacío en el presupuesto.") @Valid RenglonRequest> renglones,

        @NotNull(message = "Falta el total.")
        @DecimalMin(value = "0.00", message = "El total no puede ser negativo.")
        @DecimalMax(value = LimitesVenta.IMPORTE_MAXIMO_TEXTO,
                message = "El total es demasiado grande: el máximo es $ 9.999.999.999.999,99.")
        BigDecimal totalMostrado,

        /** Descuento global en %, aplicado a cada renglon. Ausente es 0. El rango lo valida el servicio. */
        @Digits(integer = 5, fraction = 2, message = "El descuento admite hasta 2 decimales.")
        BigDecimal descuentoPorcentaje,

        @Min(value = 1, message = "La validez va de 1 a 90 días.")
        @Max(value = EmitirPresupuesto.DIAS_VALIDEZ_MAXIMOS, message = "La validez va de 1 a 90 días.")
        Integer diasValidez,

        /** El cliente elegido en el mostrador (CL-004). Si viene, su razon social reemplaza al nombre de abajo. */
        Long clienteId,

        @Size(max = 80, message = "El nombre del cliente admite hasta 80 caracteres.")
        String clienteNombre,

        @Size(max = 200, message = "Las observaciones admiten hasta 200 caracteres.")
        String observaciones) {

    public EmitirPresupuesto aPedido() {
        return new EmitirPresupuesto(clave, renglones.stream()
                .map(r -> new ConfirmarVenta.Renglon(r.articuloId(), r.cantidad(), r.precioUnitario(),
                        r.descuentoPorcentaje()))
                .toList(), totalMostrado, descuentoPorcentaje, diasValidez, clienteId, clienteNombre, observaciones);
    }
}
