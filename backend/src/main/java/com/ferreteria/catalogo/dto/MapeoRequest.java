package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.MapeoLista;
import com.ferreteria.catalogo.model.Moneda;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Mapeo de columnas de la lista, por indice. hoja y columnas son base 0; filaDesde es la fila 1-based
 * del archivo, como la muestra la grilla. La descripcion es opcional.
 *
 * <p>Los campos del corte 2 son todos opcionales, asi que el cuerpo viejo sigue siendo valido. Los campos que no
 * existen (stock, marca...) no se leen: el ObjectMapper de Spring ignora las propiedades desconocidas, asi que
 * ninguna columna puede mapearse a otra cosa que los campos de la lista cerrada.
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
        Integer columnaCosto,

        /** Hojas que se leen; null equivale a solo {@code hoja}. */
        List<Integer> hojas,

        @Min(value = 0, message = "La columna de la moneda no es válida.")
        @Max(value = 255, message = "La columna de la moneda no es válida.")
        Integer columnaMoneda,

        Moneda monedaFija,

        @Min(value = 0, message = "La columna del precio sin IVA no es válida.")
        @Max(value = 255, message = "La columna del precio sin IVA no es válida.")
        Integer columnaSinIva,

        @Min(value = 0, message = "La columna del precio con IVA no es válida.")
        @Max(value = 255, message = "La columna del precio con IVA no es válida.")
        Integer columnaConIva,

        @Min(value = 0, message = "La columna del % de IVA no es válida.")
        @Max(value = 255, message = "La columna del % de IVA no es válida.")
        Integer columnaIva,

        @Min(value = 0, message = "La columna del descuento o recargo 1 no es válida.")
        @Max(value = 255, message = "La columna del descuento o recargo 1 no es válida.")
        Integer columnaAjuste1,

        @Min(value = 0, message = "La columna del descuento o recargo 2 no es válida.")
        @Max(value = 255, message = "La columna del descuento o recargo 2 no es válida.")
        Integer columnaAjuste2,

        @Min(value = 0, message = "La columna del descuento o recargo 3 no es válida.")
        @Max(value = 255, message = "La columna del descuento o recargo 3 no es válida.")
        Integer columnaAjuste3,

        @Min(value = 0, message = "La columna del código de barras no es válida.")
        @Max(value = 255, message = "La columna del código de barras no es válida.")
        Integer columnaCodigoBarras,

        @Min(value = 0, message = "La columna de la unidad no es válida.")
        @Max(value = 255, message = "La columna de la unidad no es válida.")
        Integer columnaUnidad,

        @Min(value = 0, message = "La columna del rubro no es válida.")
        @Max(value = 255, message = "La columna del rubro no es válida.")
        Integer columnaRubro) {

    /** El cuerpo de antes del corte 2: una sola hoja y solo codigo, descripcion y costo. */
    public MapeoRequest(Integer hoja, Integer filaDesde, Integer columnaCodigo, Integer columnaDescripcion,
                        Integer columnaCosto) {
        this(hoja, filaDesde, columnaCodigo, columnaDescripcion, columnaCosto, null, null, null, null, null, null,
            null, null, null, null, null, null);
    }

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

    /**
     * Las columnas del corte 2 no se repiten entre si ni con las de codigo, descripcion y costo. Dos excepciones: la
     * del precio sin IVA puede ser la del costo, y un choque entre codigo, descripcion y costo ya lo informa
     * {@link #isColumnasDistintas()}.
     */
    @AssertTrue(message = "Elegí columnas distintas para cada campo de la lista.")
    public boolean isCamposDistintos() {
        Integer[] viejos = {columnaCodigo, columnaDescripcion, columnaCosto};
        Integer[] nuevos = {columnaMoneda, columnaSinIva, columnaConIva, columnaIva, columnaAjuste1, columnaAjuste2,
            columnaAjuste3, columnaCodigoBarras, columnaUnidad, columnaRubro};
        Set<Integer> usadas = new HashSet<>();
        for (Integer columna : viejos) {
            if (columna != null) {
                usadas.add(columna);
            }
        }
        Set<Integer> deLosNuevos = new HashSet<>();
        for (int i = 0; i < nuevos.length; i++) {
            Integer columna = nuevos[i];
            if (columna == null) {
                continue;
            }
            boolean esSinIva = i == 1;
            if (esSinIva && columna.equals(columnaCosto)) {
                continue;
            }
            if (usadas.contains(columna) || !deLosNuevos.add(columna)) {
                return false;
            }
        }
        return true;
    }

    @AssertTrue(message = "Elegí una columna de moneda o una moneda fija, no las dos.")
    public boolean isMonedaUnica() {
        return columnaMoneda == null || monedaFija == null;
    }

    @AssertTrue(message = "Para calcular el IVA elegí las dos columnas: el precio sin IVA y el precio con IVA.")
    public boolean isParDeIvaCompleto() {
        return (columnaSinIva == null) == (columnaConIva == null);
    }

    @AssertTrue(message = "Las hojas elegidas no son válidas: no pueden repetirse ni ser negativas.")
    public boolean isHojasValidas() {
        if (hojas == null) {
            return true;
        }
        Set<Integer> vistas = new HashSet<>();
        for (Integer hojaElegida : hojas) {
            if (hojaElegida == null || hojaElegida < 0 || !vistas.add(hojaElegida)) {
                return false;
            }
        }
        return true;
    }

    /** Sin encabezados: los lee el servicio del archivo al aplicar. */
    public MapeoLista toMapeo() {
        return new MapeoLista(hoja, filaDesde, columnaCodigo, columnaDescripcion, columnaCosto, null, hojas,
            columnaMoneda, monedaFija, columnaSinIva, columnaConIva, columnaIva, columnaAjuste1, columnaAjuste2,
            columnaAjuste3, columnaCodigoBarras, columnaUnidad, columnaRubro);
    }
}
