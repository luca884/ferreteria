package com.ferreteria.clientes.web;

import com.fasterxml.jackson.annotation.JsonSetter;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import java.math.BigDecimal;

/**
 * El límite de cuenta corriente de un cliente (CC-018). {@code "limite": null} deshabilita la cuenta; 0 la habilita sin
 * crédito; no hay "sin tope". Cabe en DECIMAL(15,2): hasta 13 enteros y 2 decimales.
 *
 * <p>La clave es obligatoria: un cuerpo sin {@code limite} (por ejemplo {@code {}}) no es lo mismo que {@code null} y no debe
 * deshabilitar la cuenta por error. Un record no distingue ausente de null, por eso es una clase que anota si Jackson llegó
 * a asignar el campo ({@link #presente()}); el controlador rechaza el cuerpo sin la clave con un error del campo
 * {@code limite}.
 */
public class LimiteCuentaRequest {

    static final String TOPE_TEXTO = "9999999999999.99";

    @DecimalMin(value = "0.00", message = "El límite no puede ser negativo.")
    @DecimalMax(value = TOPE_TEXTO, message = "El límite es demasiado grande.")
    @Digits(integer = 13, fraction = 2, message = "El límite admite hasta 2 decimales.")
    private BigDecimal limite;

    private boolean presente;

    @JsonSetter("limite")
    public void setLimite(BigDecimal limite) {
        this.limite = limite;
        this.presente = true;
    }

    public BigDecimal limite() {
        return limite;
    }

    /** {@code true} si el cuerpo trajo la clave {@code limite}, aunque su valor sea null. */
    public boolean presente() {
        return presente;
    }
}
