package com.ferreteria.catalogo.dto;

import com.ferreteria.ferreteria.model.ConfiguracionPrecios;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

/** Margen por defecto y multiplo de redondeo que el Dueño guarda para su ferreteria. 0 de multiplo es sin redondeo. */
public record ConfiguracionPreciosRequest(
        @NotNull(message = "El margen es obligatorio.")
        @DecimalMin(value = "0.00", message = "El margen no puede ser negativo.")
        @DecimalMax(value = "1000.00", message = "El margen no puede superar 1000 %.")
        @Digits(integer = 4, fraction = 2, message = "El margen admite hasta 2 decimales.")
        BigDecimal margenDefectoPorcentaje,

        @NotNull(message = "El redondeo es obligatorio.")
        @DecimalMin(value = "0.00", message = "El redondeo no puede ser negativo.")
        @DecimalMax(value = "1000.00", message = "El redondeo no puede superar $ 1000.")
        @Digits(integer = 4, fraction = 2, message = "El redondeo admite hasta 2 decimales.")
        BigDecimal redondeoMultiplo) {

    public ConfiguracionPrecios aConfiguracion() {
        return new ConfiguracionPrecios(margenDefectoPorcentaje, redondeoMultiplo);
    }
}
