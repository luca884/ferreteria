package com.ferreteria.ferreteria.web;

import com.ferreteria.ferreteria.model.ConfiguracionVentas;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** El tope de venta a Consumidor Final sin identificar que guarda el Dueño (GDS-32). Entra en DECIMAL(15,2). */
public record ConfiguracionVentasRequest(
        @NotNull(message = "El tope es obligatorio.")
        @DecimalMin(value = "0.00", inclusive = false, message = "El tope tiene que ser mayor que $ 0.")
        @Digits(integer = 13, fraction = 2, message = "El tope admite hasta 2 decimales.")
        BigDecimal topeConsumidorFinal) {

    public ConfiguracionVentas aConfiguracion() {
        return new ConfiguracionVentas(topeConsumidorFinal);
    }
}
