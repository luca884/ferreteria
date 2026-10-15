package com.ferreteria.ventas.web;

import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.service.ConfirmarVenta;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.List;

/**
 * Lo que manda el mostrador al cobrar (R-001, R-002). No tiene ferreteria ni usuario: salen del JWT, y si el
 * cliente los manda se ignoran. Aca solo se valida la forma; las reglas que dependen de los articulos (404,
 * baja, cantidades por unidad, precios) las hace el servicio, con su orden de precedencia.
 */
public record ConfirmarVentaRequest(
        @NotNull(message = "Falta la clave de la venta.")
        @Pattern(regexp = "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
                message = "La clave de la venta no es válida.")
        String clave,

        @NotEmpty(message = "El ticket no tiene artículos.")
        @Size(max = 500, message = "El ticket admite hasta 500 renglones.")
        List<@NotNull(message = "Hay un renglón vacío en el ticket.") @Valid RenglonRequest> renglones,

        @NotNull(message = "Falta el total.")
        @DecimalMin(value = "0.00", message = "El total no puede ser negativo.")
        BigDecimal totalMostrado,

        @NotNull(message = "Elegí un medio de pago: EFECTIVO, DEBITO, CREDITO o TRANSFERENCIA.")
        MedioPago medioPago) {

    /** Un renglon del ticket: articulo, cantidad y el precio unitario (con IVA) que vio el cajero. */
    public record RenglonRequest(
            @NotNull(message = "Falta el artículo.")
            Long articuloId,

            @NotNull(message = "Falta la cantidad.")
            BigDecimal cantidad,

            @NotNull(message = "Falta el precio que se mostró.")
            @DecimalMin(value = "0.00", message = "El precio no puede ser negativo.")
            BigDecimal precioUnitario) {}

    public ConfirmarVenta aPedido() {
        return new ConfirmarVenta(clave, renglones.stream()
                .map(r -> new ConfirmarVenta.Renglon(r.articuloId(), r.cantidad(), r.precioUnitario()))
                .toList(), totalMostrado, medioPago);
    }
}
