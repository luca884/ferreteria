package com.ferreteria.ventas.web;

import com.ferreteria.ventas.service.ConfirmarVenta;
import com.ferreteria.ventas.model.LimitesVenta;
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
        @DecimalMax(value = LimitesVenta.IMPORTE_MAXIMO_TEXTO,
                message = "El total es demasiado grande: el máximo es $ 9.999.999.999.999,99.")
        BigDecimal totalMostrado,

        /** Una forma de pago unica, por todo el total. O, en vez de esta, la lista {@code pagos}. El servicio exige una de las dos. */
        Long formaPagoId,

        /** Descuento global en % (GDS-12), aplicado a cada renglon. Ausente es 0. El rango lo valida el servicio. */
        @Digits(integer = 5, fraction = 2, message = "El descuento admite hasta 2 decimales.")
        BigDecimal descuentoPorcentaje,

        /** Pago mixto (GDS-07): un pago por forma; las partes (sin recargo) tienen que sumar el total sin recargo. */
        @Size(max = 20, message = "Hay hasta 20 formas de pago en una venta.")
        List<@NotNull(message = "Hay un pago vacío.") @Valid PagoRequest> pagos,

        /** El presupuesto del que sale la venta (PP-006), si sale de uno. Lo valida el servicio. */
        Long presupuestoId,

        /** El remito del que sale la venta (RM-006), si sale de uno. Lo valida el servicio. */
        Long remitoId) {

    /** Una parte del pago mixto: la forma y lo que cubre del total sin recargo. El rango fino lo valida el servicio. */
    public record PagoRequest(
            Long formaPagoId,

            @DecimalMax(value = LimitesVenta.IMPORTE_MAXIMO_TEXTO,
                    message = "El monto es demasiado grande: el máximo es $ 9.999.999.999.999,99.")
            @Digits(integer = 13, fraction = 2, message = "El monto admite hasta 2 decimales.")
            BigDecimal monto) {}

    /** Un renglon del ticket: articulo, cantidad y el precio unitario (con IVA) que vio el cajero. */
    public record RenglonRequest(
            @NotNull(message = "Falta el artículo.")
            Long articuloId,

            @NotNull(message = "Falta la cantidad.")
            @DecimalMax(value = LimitesVenta.CANTIDAD_MAXIMA_TEXTO,
                    message = "La cantidad es demasiado grande: el máximo es 999.999.999.999,999.")
            BigDecimal cantidad,

            @NotNull(message = "Falta el precio que se mostró.")
            @DecimalMin(value = "0.00", message = "El precio no puede ser negativo.")
            @DecimalMax(value = LimitesVenta.IMPORTE_MAXIMO_TEXTO,
                    message = "El precio es demasiado grande: el máximo es $ 9.999.999.999.999,99.")
            BigDecimal precioUnitario,

            /** Descuento del renglon en % (GDS-12). Ausente es 0. */
            @Digits(integer = 5, fraction = 2, message = "El descuento admite hasta 2 decimales.")
            BigDecimal descuentoPorcentaje) {}

    public ConfirmarVenta aPedido() {
        return new ConfirmarVenta(clave, renglones.stream()
                .map(r -> new ConfirmarVenta.Renglon(r.articuloId(), r.cantidad(), r.precioUnitario(),
                        r.descuentoPorcentaje()))
                .toList(), totalMostrado, formaPagoId, descuentoPorcentaje,
                pagos == null ? List.of()
                        : pagos.stream().map(p -> new ConfirmarVenta.PagoPedido(p.formaPagoId(), p.monto())).toList(),
                presupuestoId, remitoId);
    }
}
