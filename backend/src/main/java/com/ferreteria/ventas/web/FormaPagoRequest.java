package com.ferreteria.ventas.web;

import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.service.DatosFormaPago;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Alta y edicion de una forma de pago (RC-008). El recargo, activa y orden son opcionales: al crear valen 0, activa y el
 * siguiente al ultimo; al editar se conserva lo que no viene.
 */
public record FormaPagoRequest(
        @NotBlank(message = "El nombre es obligatorio.")
        @Size(max = 60, message = "El nombre no puede superar 60 caracteres.")
        String nombre,

        @NotNull(message = "Elegí el medio de pago.")
        MedioPago medio,

        @DecimalMin(value = "0.00", message = "El recargo tiene que estar entre 0 y 200 %.")
        @DecimalMax(value = "200.00", message = "El recargo tiene que estar entre 0 y 200 %.")
        @Digits(integer = 3, fraction = 2, message = "El recargo admite hasta 2 decimales.")
        BigDecimal recargoPorcentaje,

        Boolean activa,

        @Min(value = 0, message = "El orden no puede ser negativo.")
        Integer orden) {

    public DatosFormaPago aDatos() {
        return new DatosFormaPago(nombre, medio, recargoPorcentaje, activa, orden);
    }
}
