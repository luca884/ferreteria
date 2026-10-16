package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.ClaveFila;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Una fila de la vista previa que el Dueno eligio aplicar, por su lugar en el archivo: la hoja (base 0) y la fila
 * (1-based, como la muestra la grilla). Nunca por el id de un articulo, asi no puede apuntar a otra ferreteria.
 */
public record FilaElegida(
        @NotNull(message = "Falta la hoja de una fila elegida.")
        @Min(value = 0, message = "La hoja de una fila elegida no es válida.")
        Integer hoja,

        @NotNull(message = "Falta el número de una fila elegida.")
        @Min(value = 1, message = "El número de una fila elegida no es válido.")
        Integer fila) {

    public ClaveFila toClave() {
        return new ClaveFila(hoja, fila);
    }
}
