package com.ferreteria.catalogo.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Mapeo de columnas de la lista de un proveedor. Se guarda como JSON en proveedor.mapeo_lista, asi
 * que los nombres de las propiedades son parte del formato guardado (no renombrar sin migrar).
 * hoja y columnas son base 0; filaDesde es la fila 1-based del archivo, como la muestra la grilla.
 */
public record MapeoLista(
        int hoja,
        int filaDesde,
        int columnaCodigo,
        Integer columnaDescripcion,
        int columnaCosto,
        Encabezados encabezados) {

    /** Textos de encabezado leidos de la fila filaDesde - 1 al guardar; null si filaDesde es 1. */
    public record Encabezados(String codigo, String descripcion, String costo) {}

    /**
     * El mismo mapeo con los textos de encabezado leidos de {@code encabezado} (la fila anterior a
     * la primera de datos). Sin esa fila, o con filaDesde = 1, no hay encabezados (null).
     */
    public MapeoLista conEncabezados(FilaLeida encabezado) {
        if (encabezado == null || filaDesde <= 1) {
            return new MapeoLista(hoja, filaDesde, columnaCodigo, columnaDescripcion, columnaCosto, null);
        }
        String codigo = texto(encabezado, columnaCodigo);
        String descripcion = columnaDescripcion == null ? null : texto(encabezado, columnaDescripcion);
        String costo = texto(encabezado, columnaCosto);
        return new MapeoLista(hoja, filaDesde, columnaCodigo, columnaDescripcion, columnaCosto,
            new Encabezados(codigo, descripcion, costo));
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
        return advertencias;
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

    private static String texto(FilaLeida fila, int columna) {
        String texto = fila.celda(columna).texto().strip();
        return texto.isEmpty() ? null : texto;
    }
}
