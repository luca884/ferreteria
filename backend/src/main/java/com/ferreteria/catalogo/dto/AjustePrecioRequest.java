package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.AjustePrecio;
import com.ferreteria.catalogo.model.TipoAjustePrecio;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * Un ajuste sobre el precio final (CU-14): nombre opcional, tipo (% o $) y valor, positivo o negativo. El rango del
 * porcentaje depende del tipo y lo revisa {@link ArticuloRequest#toPedidoDePrecio()}.
 */
public record AjustePrecioRequest(
        @Size(max = AjustePrecio.NOMBRE_MAXIMO, message = "El nombre del ajuste tiene hasta 40 caracteres.")
        String nombre,

        @NotNull(message = "Elegí si el ajuste es en % o en $.")
        TipoAjustePrecio tipo,

        @NotNull(message = "El valor del ajuste es obligatorio.")
        @Digits(integer = 13, fraction = 2, message = "El valor del ajuste admite hasta 2 decimales.")
        BigDecimal valor) {

    static final String PORCENTAJE_FUERA_DE_RANGO = "El porcentaje del ajuste tiene que estar entre -99,99 y 1.000.";

    boolean porcentajeFueraDeRango() {
        return tipo == TipoAjustePrecio.PORCENTAJE
                && (valor.compareTo(AjustePrecio.PORCENTAJE_MINIMO) < 0
                || valor.compareTo(AjustePrecio.PORCENTAJE_MAXIMO) > 0);
    }

    AjustePrecio toAjuste() {
        return new AjustePrecio(nombre, tipo, valor);
    }
}
