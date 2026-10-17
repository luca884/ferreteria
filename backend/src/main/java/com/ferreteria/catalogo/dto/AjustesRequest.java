package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.Ajustes;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;

import java.math.BigDecimal;

/**
 * Los tres descuentos o recargos de un articulo, en porcentaje: negativo descuenta y positivo recarga; null es "sin
 * ajuste". No lleva costo: el costo sale de la lista (decision 43). {@code confirmaPisarCosto} autoriza a pisar un
 * costo cargado a mano o por una actualizacion masiva (R-214). Las reglas van en el grupo
 * {@link ValidacionAjustes}, que valida el servicio despues del 404.
 */
public record AjustesRequest(
        @DecimalMin(value = "-99.99", message = RANGO, groups = ValidacionAjustes.class)
        @DecimalMax(value = "1000", message = RANGO, groups = ValidacionAjustes.class)
        @Digits(integer = 4, fraction = 2, message = DECIMALES, groups = ValidacionAjustes.class)
        BigDecimal ajuste1,

        @DecimalMin(value = "-99.99", message = RANGO, groups = ValidacionAjustes.class)
        @DecimalMax(value = "1000", message = RANGO, groups = ValidacionAjustes.class)
        @Digits(integer = 4, fraction = 2, message = DECIMALES, groups = ValidacionAjustes.class)
        BigDecimal ajuste2,

        @DecimalMin(value = "-99.99", message = RANGO, groups = ValidacionAjustes.class)
        @DecimalMax(value = "1000", message = RANGO, groups = ValidacionAjustes.class)
        @Digits(integer = 4, fraction = 2, message = DECIMALES, groups = ValidacionAjustes.class)
        BigDecimal ajuste3,

        boolean confirmaPisarCosto,

        /**
         * Version del articulo que el cliente leyo (bloqueo optimista). Es OBLIGATORIA desde el lote 6 (decision 48):
         * sin ella es un 400 y, si no coincide con la vigente, un 409.
         */
        Long version) {

    /** Pedido sin version: el servicio lo rechaza con 400; existe para los tests que no llegan al servicio. */
    public AjustesRequest(BigDecimal ajuste1, BigDecimal ajuste2, BigDecimal ajuste3, boolean confirmaPisarCosto) {
        this(ajuste1, ajuste2, ajuste3, confirmaPisarCosto, null);
    }

    static final String RANGO = "Cada descuento o recargo tiene que estar entre -99,99 y 1.000.";
    static final String DECIMALES = "Los descuentos y recargos admiten hasta 2 decimales.";

    /** Solo despues de validar: {@link Ajustes} redondea a 2 decimales y esconderia un 3,555. */
    public Ajustes toAjustes() {
        return new Ajustes(ajuste1, ajuste2, ajuste3);
    }
}
