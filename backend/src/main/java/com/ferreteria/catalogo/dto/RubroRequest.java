package com.ferreteria.catalogo.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record RubroRequest(
        @NotBlank(message = "El nombre es obligatorio")
        @Size(max = 80, message = "El nombre no puede superar 80 caracteres")
        String nombre,

        @NotNull(message = "El margen es obligatorio")
        @DecimalMin(value = "0.00", message = "El margen no puede ser negativo")
        @DecimalMax(value = "1000.00", message = "El margen no puede superar 1000 %")
        @Digits(integer = 4, fraction = 2, message = "El margen admite hasta 2 decimales")
        BigDecimal margenPorcentaje) {
}
