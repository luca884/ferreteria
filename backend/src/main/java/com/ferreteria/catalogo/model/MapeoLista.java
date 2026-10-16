package com.ferreteria.catalogo.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Mapeo de columnas de la lista de un proveedor. Se guarda como JSON en proveedor.mapeo_lista, asi
 * que los nombres de las propiedades son parte del formato guardado (no renombrar ni quitar sin migrar).
 * hoja y columnas son base 0; filaDesde es la fila 1-based del archivo, como la muestra la grilla.
 *
 * <p>Los campos del corte 2 son todos opcionales (tipo envoltorio, null por defecto) y no se escriben cuando son
 * null: un mapeo viejo se lee igual y, al volver a guardarlo, no se inventa ninguno. Las claves desconocidas se
 * ignoran, asi un mapeo con campos de una version futura (o un rollback) no rompe la lectura del proveedor.
 * No existen campos para stock ni marca: la lista no los cambia nunca.
 *
 * @param hojas        hojas que se leen (indices base 0); null equivale a solo {@code hoja}
 * @param monedaFija   moneda de todas las filas, para las listas sin columna de moneda; excluyente con columnaMoneda
 * @param columnaSinIva  precio sin IVA, solo para calcular la alicuota; puede ser la misma columna del costo
 * @param columnaConIva  precio con IVA, solo para calcular la alicuota; va siempre junto a columnaSinIva
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MapeoLista(
        int hoja,
        int filaDesde,
        int columnaCodigo,
        Integer columnaDescripcion,
        int columnaCosto,
        Encabezados encabezados,
        List<Integer> hojas,
        Integer columnaMoneda,
        Moneda monedaFija,
        Integer columnaSinIva,
        Integer columnaConIva,
        Integer columnaIva,
        Integer columnaAjuste1,
        Integer columnaAjuste2,
        Integer columnaAjuste3,
        Integer columnaCodigoBarras,
        Integer columnaUnidad,
        Integer columnaRubro) {

    /**
     * Textos de encabezado leidos de la fila filaDesde - 1 al guardar; null si filaDesde es 1. Los de las columnas
     * del corte 2 son null cuando la columna no esta mapeada o la celda esta vacia.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Encabezados(
            String codigo,
            String descripcion,
            String costo,
            String moneda,
            String sinIva,
            String conIva,
            String iva,
            String ajuste1,
            String ajuste2,
            String ajuste3,
            String codigoBarras,
            String unidad,
            String rubro) {

        /** Los tres textos de antes del corte 2. */
        public Encabezados(String codigo, String descripcion, String costo) {
            this(codigo, descripcion, costo, null, null, null, null, null, null, null, null, null, null);
        }
    }

    /** El mapeo de antes del corte 2: una sola hoja, sin moneda, IVA ni ajustes por fila, ni barras, unidad o rubro. */
    public MapeoLista(int hoja, int filaDesde, int columnaCodigo, Integer columnaDescripcion, int columnaCosto,
                      Encabezados encabezados) {
        this(hoja, filaDesde, columnaCodigo, columnaDescripcion, columnaCosto, encabezados, null, null, null, null,
            null, null, null, null, null, null, null, null);
    }

    public MapeoLista {
        if (hojas != null) {
            hojas = List.copyOf(hojas);
        }
    }

    /** Las hojas que se leen: {@code hojas} si viene, y si no solo {@code hoja} (un mapeo viejo). */
    public List<Integer> hojasElegidas() {
        return hojas == null || hojas.isEmpty() ? List.of(hoja) : hojas;
    }

    /**
     * El mismo mapeo con los textos de encabezado leidos de {@code encabezado} (la fila anterior a
     * la primera de datos), para todas las columnas mapeadas. Sin esa fila, o con filaDesde = 1, no hay
     * encabezados (null). El resto del mapeo no cambia.
     */
    public MapeoLista conEncabezados(FilaLeida encabezado) {
        if (encabezado == null || filaDesde <= 1) {
            return con(null);
        }
        return con(new Encabezados(
            texto(encabezado, columnaCodigo),
            texto(encabezado, columnaDescripcion),
            texto(encabezado, columnaCosto),
            texto(encabezado, columnaMoneda),
            texto(encabezado, columnaSinIva),
            texto(encabezado, columnaConIva),
            texto(encabezado, columnaIva),
            texto(encabezado, columnaAjuste1),
            texto(encabezado, columnaAjuste2),
            texto(encabezado, columnaAjuste3),
            texto(encabezado, columnaCodigoBarras),
            texto(encabezado, columnaUnidad),
            texto(encabezado, columnaRubro)));
    }

    private MapeoLista con(Encabezados nuevos) {
        return new MapeoLista(hoja, filaDesde, columnaCodigo, columnaDescripcion, columnaCosto, nuevos, hojas,
            columnaMoneda, monedaFija, columnaSinIva, columnaConIva, columnaIva, columnaAjuste1, columnaAjuste2,
            columnaAjuste3, columnaCodigoBarras, columnaUnidad, columnaRubro);
    }

    /**
     * Una advertencia por cada columna cuyo texto de encabezado cambio respecto del mapeo guardado.
     * Solo compara columnas que el guardado usa en el mismo lugar y que tenian texto guardado;
     * sin mapeo guardado, o sin encabezados de alguno de los dos lados, no hay advertencias.
     */
    public List<String> advertenciasContra(MapeoLista guardado) {
        List<String> advertencias = new ArrayList<>();
        if (guardado == null || guardado.encabezados() == null || encabezados == null) {
            return advertencias;
        }
        Encabezados antes = guardado.encabezados();
        if (guardado.columnaCodigo() == columnaCodigo) {
            comparar("del código", antes.codigo(), encabezados.codigo(), advertencias);
        }
        if (guardado.columnaDescripcion() != null && Objects.equals(guardado.columnaDescripcion(), columnaDescripcion)) {
            comparar("de la descripción", antes.descripcion(), encabezados.descripcion(), advertencias);
        }
        if (guardado.columnaCosto() == columnaCosto) {
            comparar("del costo", antes.costo(), encabezados.costo(), advertencias);
        }
        compararNueva("de la moneda", guardado.columnaMoneda, columnaMoneda, antes.moneda(), encabezados.moneda(),
            advertencias);
        // El costo sin IVA que es la misma columna del costo ya se avisa como "del costo".
        if (!Objects.equals(columnaSinIva, columnaCosto)) {
            compararNueva("del costo sin IVA", guardado.columnaSinIva, columnaSinIva, antes.sinIva(),
                encabezados.sinIva(), advertencias);
        }
        compararNueva("del costo con IVA", guardado.columnaConIva, columnaConIva, antes.conIva(), encabezados.conIva(),
            advertencias);
        compararNueva("del % de IVA", guardado.columnaIva, columnaIva, antes.iva(), encabezados.iva(), advertencias);
        compararNueva("del descuento o recargo 1", guardado.columnaAjuste1, columnaAjuste1, antes.ajuste1(),
            encabezados.ajuste1(), advertencias);
        compararNueva("del descuento o recargo 2", guardado.columnaAjuste2, columnaAjuste2, antes.ajuste2(),
            encabezados.ajuste2(), advertencias);
        compararNueva("del descuento o recargo 3", guardado.columnaAjuste3, columnaAjuste3, antes.ajuste3(),
            encabezados.ajuste3(), advertencias);
        compararNueva("del código de barras", guardado.columnaCodigoBarras, columnaCodigoBarras, antes.codigoBarras(),
            encabezados.codigoBarras(), advertencias);
        compararNueva("de la unidad", guardado.columnaUnidad, columnaUnidad, antes.unidad(), encabezados.unidad(),
            advertencias);
        compararNueva("del rubro", guardado.columnaRubro, columnaRubro, antes.rubro(), encabezados.rubro(),
            advertencias);
        return advertencias;
    }

    /** Una columna del corte 2: se compara solo si el guardado la usaba en el mismo lugar que ahora. */
    private static void compararNueva(String columna, Integer columnaGuardada, Integer columnaActual, String antes,
                                      String ahora, List<String> advertencias) {
        if (columnaGuardada != null && Objects.equals(columnaGuardada, columnaActual)) {
            comparar(columna, antes, ahora, advertencias);
        }
    }

    private static void comparar(String columna, String antes, String ahora, List<String> advertencias) {
        if (antes == null) {
            return;
        }
        if (!Objects.equals(NormalizadorCodigo.clave(antes), NormalizadorCodigo.clave(ahora))) {
            advertencias.add("El encabezado de la columna " + columna + " cambió: antes decía «" + antes
                + "» y ahora dice «" + (ahora == null ? "" : ahora) + "». Revisá el mapeo.");
        }
    }

    private static String texto(FilaLeida fila, Integer columna) {
        if (columna == null) {
            return null;
        }
        String texto = fila.celda(columna).texto().strip();
        return texto.isEmpty() ? null : texto;
    }
}
