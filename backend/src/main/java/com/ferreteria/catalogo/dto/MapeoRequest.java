package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.MapeoLista;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Mapeo de columnas de la lista, por indice. hoja y columnas son base 0; filaDesde es la fila 1-based
 * del archivo, como la muestra la grilla. La descripcion es opcional.
 */
public record MapeoRequest(
        @NotNull(message = "Elegí la hoja de la lista.")
        @Min(value = 0, message = "La hoja elegida no es válida.")
        Integer hoja,

        @NotNull(message = "Indicá la primera fila de datos.")
        @Min(value = 1, message = "La primera fila de datos tiene que ser 1 o más.")
        Integer filaDesde,

        @NotNull(message = "Elegí la columna del código.")
        @Min(value = 0, message = "La columna del código no es válida.")
        @Max(value = 255, message = "La columna del código no es válida.")
        Integer columnaCodigo,

        @Min(value = 0, message = "La columna de la descripción no es válida.")
        @Max(value = 255, message = "La columna de la descripción no es válida.")
        Integer columnaDescripcion,

        @NotNull(message = "Elegí la columna del costo.")
        @Min(value = 0, message = "La columna del costo no es válida.")
        @Max(value = 255, message = "La columna del costo no es válida.")
        Integer columnaCosto) {

    /** Solo compara las columnas que vinieron: las que faltan ya tienen su propio error. */
    @AssertTrue(message = "Elegí columnas distintas para código, descripción y costo.")
    public boolean isColumnasDistintas() {
        if (columnaCodigo != null && columnaCodigo.equals(columnaCosto)) {
            return false;
        }
        if (columnaDescripcion == null) {
            return true;
        }
        return !columnaDescripcion.equals(columnaCodigo) && !columnaDescripcion.equals(columnaCosto);
    }

    /** Sin encabezados: los lee el servicio del archivo al aplicar. */
    public MapeoLista toMapeo() {
        return new MapeoLista(hoja, filaDesde, columnaCodigo, columnaDescripcion, columnaCosto, null);
    }
}
