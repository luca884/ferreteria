package com.ferreteria.catalogo.service.lectura;

import com.ferreteria.common.exception.BadRequestException;

/**
 * Topes de lo que se acepta de una lista (R-025 y R-101). El tope exacto (20.000 filas de datos, las de las
 * hojas elegidas desde su primera fila de datos) lo aplica el servicio, que conoce el mapeo. Los lectores
 * aplican uno grueso, {@link #MAXIMO_FILAS_LEIDAS}, que cuenta el LIBRO entero (todas sus hojas), para cortar la
 * lectura antes de armar toda la lista en memoria: tolera los titulos y encabezados de un libro de muchas hojas.
 */
public final class LimitesDeLista {

    public static final int MAXIMO_FILAS_DE_DATOS = 20_000;

    /**
     * Filas no vacias que un lector arma como mucho en TODO el libro: los datos mas un margen para los titulos y
     * encabezados de las hojas (LISTA 336 tiene 70).
     */
    public static final int MAXIMO_FILAS_LEIDAS = 25_000;

    /**
     * Lo mas que POI infla de una sola parte de un .xlsx (hoja, cadenas compartidas...). Una lista de
     * 25 MB comprimida cabe con holgura (XML comprime ~10 veces); el valor por defecto de POI es 4 GB.
     */
    public static final long MAXIMO_ENTRADA_ZIP_BYTES = 256L * 1024 * 1024;

    /** Lo mas grande que POI reserva para un arreglo de bytes (por defecto 100 MB). */
    public static final int MAXIMO_ARREGLO_POI_BYTES = 50_000_000;

    /**
     * Tope de seguridad de columnas por fila en la muestra. La muestra devuelve todas las celdas (el
     * Dueno ve la hoja completa), pero una hoja patologica con miles de columnas podria inflar la
     * respuesta; una lista real de proveedor tiene muy pocas (200 llegan hasta la columna GR).
     */
    public static final int MAXIMO_COLUMNAS_DE_MUESTRA = 200;

    private static final String MENSAJE = "La lista tiene más de 20.000 filas. Dividila en archivos más chicos.";

    private static final String MENSAJE_GRANDE =
            "La lista es demasiado grande para leerla. Dividila en archivos más chicos.";

    private LimitesDeLista() {
    }

    /** Un archivo valido que pasa los topes de memoria de POI (MAXIMO_ENTRADA_ZIP_BYTES, MAXIMO_ARREGLO_POI_BYTES). */
    public static BadRequestException demasiadoGrande() {
        return new BadRequestException(MENSAJE_GRANDE);
    }

    public static BadRequestException demasiadasFilas() {
        return new BadRequestException(MENSAJE);
    }
}
