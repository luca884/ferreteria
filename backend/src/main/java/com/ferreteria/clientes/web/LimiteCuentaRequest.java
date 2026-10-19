package com.ferreteria.clientes.web;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;

/**
 * El límite de cuenta corriente de un cliente (CC-018). {@code null} deshabilita la cuenta; 0 la habilita sin crédito; no hay
 * "sin tope". Cabe en DECIMAL(15,2): hasta 13 enteros y 2 decimales.
 */
public record LimiteCuentaRequest(
        @DecimalMin(value = "0.00", message = "El límite no puede ser negativo.")
        @DecimalMax(value = LimiteCuentaRequest.TOPE_TEXTO, message = "El límite es demasiado grande.")
        @Digits(integer = 13, fraction = 2, message = "El límite admite hasta 2 decimales.")
        BigDecimal limite) {

    static final String TOPE_TEXTO = "9999999999999.99";
}
