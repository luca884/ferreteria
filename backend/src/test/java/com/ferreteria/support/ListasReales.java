package com.ferreteria.support;

import com.ferreteria.catalogo.model.Celda;
import com.ferreteria.catalogo.model.FilaLeida;
import com.ferreteria.catalogo.model.LecturaLista;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.util.NumberToTextConverter;

/**
 * Fixtures chicos que copian la estructura medida en las listas reales (obs #5378), para que los tests no lean
 * Lista/ (que no esta en git).
 *
 * <p>LISTA 336: un .xlsx de varias hojas con el encabezado de una fila (Codigo, Articulo, Sinonimo, Stock, Mon.,
 * Lista (unidad), Bulto, Sin IVA, Con IVA). El costo es F (por unidad); H y I estan en la base del bulto; I / H da
 * 21 % o 10,5 %. Los titulos traen texto solo en "Mon.".
 *
 * <p>M-S: un .xls de una hoja con el encabezado de dos filas (13 y 14), el primer titulo "Familia: NNN - Nombre" en
 * la A de la fila 14, datos desde la 15 (costos con 4 decimales) y el resto de los titulos precedidos de una fila
 * vacia.
 */
public final class ListasReales {

    /** Articulos de {@link #msChicaFilas()}: 3 + 2 + 2. */
    public static final int MS_CHICA_ARTICULOS = 7;

    private static final List<Object> ENCABEZADO_336 = fila("Código", "Artículo", "Sinónimo", "Stock", "Mon.",
        "Lista (unidad)", "Bulto", "Sin IVA", "Con IVA");

    private ListasReales() {
    }

    // ---- LISTA 336 ----

    /** Tres hojas (TORNILLOS, TUERCAS, ARANDELAS); la primera trae un caso de cada tipo de fila. */
    public static Map<String, List<List<Object>>> lista336ChicaHojas() {
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put("TORNILLOS", List.of(
            ENCABEZADO_336,
            fila336("TOR-001", "Tornillo hexagonal 1/4", "ARG", 1000, 1, 1000, 1210),
            fila336("TOR-002", "Tornillo importado", "USD", 10, 1, 10, 12.1),
            fila336("TOR-003", "Tornillo al diez y medio", "ARG", 1000, 1, 1000, 1105),
            fila336("TOR-004", "Tornillo por bulto", "ARG", 150, 10, 1500, 1815),
            fila(null, null, null, null, "ALEBA CARGADORES"),
            fila336("TOR-005", "Tornillo sin precio", "ARG", 0, 1, 0, 0)));
        hojas.put("TUERCAS", List.of(
            ENCABEZADO_336,
            fila336("TUE-001", "Tuerca hexagonal 1/4", "ARG", 500, 1, 500, 605),
            fila336("TUE-002", "Tuerca importada", "USD", 20, 1, 20, 24.2)));
        hojas.put("ARANDELAS", List.of(
            ENCABEZADO_336,
            fila336("ARA-001", "Arandela plana 1/4", "ARG", 100, 1, 100, 121)));
        return hojas;
    }

    public static byte[] lista336Chica() {
        return ListasDePrueba.xlsxHojas(lista336ChicaHojas());
    }

    /** {@code hojas} hojas con el encabezado y {@code filasPorHoja} filas ARG cada una (codigos H1-001, H1-002...). */
    public static Map<String, List<List<Object>>> lista336HojasFilas(int hojas, int filasPorHoja) {
        Map<String, List<List<Object>>> libro = new LinkedHashMap<>();
        for (int h = 1; h <= hojas; h++) {
            List<List<Object>> filas = new ArrayList<>();
            filas.add(ENCABEZADO_336);
            for (int i = 1; i <= filasPorHoja; i++) {
                int importe = 100 * i;
                filas.add(fila336(String.format("H%d-%03d", h, i), "Articulo " + h + "-" + i, "ARG", importe, 1,
                    importe, importe * 1.21));
            }
            libro.put("HOJA " + h, filas);
        }
        return libro;
    }

    public static byte[] lista336Hojas(int hojas, int filasPorHoja) {
        return ListasDePrueba.xlsxHojas(lista336HojasFilas(hojas, filasPorHoja));
    }

    private static List<Object> fila336(String codigo, String articulo, String moneda, Number lista, Number bulto,
                                        Number sinIva, Number conIva) {
        return fila(codigo, articulo, null, 10, moneda, lista, bulto, sinIva, conIva);
    }

    // ---- M-S ----

    /** Tres familias: la 001 con 3 articulos (titulo en la fila 14) y la 002 y la 003 con 2, cada titulo tras una fila vacia. */
    public static List<List<Object>> msChicaFilas() {
        List<List<Object>> filas = encabezadoMs("Familia: 001 - Pasadores");
        filas.add(articuloMs("P-001", "Pasador 1/8", 1532.4361));
        filas.add(articuloMs("P-002", "Pasador 3/16", 2210.0575));
        filas.add(articuloMs("P-003", "Pasador 1/4", 3015.9));
        filas.add(fila());
        filas.add(fila("Familia: 002 - Bulones"));
        filas.add(articuloMs("B-001", "Bulon 1/4 x 2", 410.1234));
        filas.add(articuloMs("B-002", "Bulon 3/8 x 3", 725.5));
        filas.add(fila());
        filas.add(fila("Familia: 003 - Chavetas"));
        filas.add(articuloMs("C-001", "Chaveta 2 mm", 88.8888));
        filas.add(articuloMs("C-002", "Chaveta 3 mm", 120.0049));
        return filas;
    }

    public static byte[] msChica() {
        return ListasDePrueba.xlsHojas(unaHoja("M-S ", msChicaFilas()));
    }

    /**
     * {@code familias} familias de {@code articulosPorFamilia} articulos. La primera lleva el titulo en la fila 14;
     * las otras {@code familias - 1}, un titulo precedido de una fila vacia.
     */
    public static List<List<Object>> msFilas(int familias, int articulosPorFamilia) {
        List<List<Object>> filas = encabezadoMs(tituloMs(1));
        for (int f = 1; f <= familias; f++) {
            if (f > 1) {
                filas.add(fila());
                filas.add(fila(tituloMs(f)));
            }
            for (int i = 1; i <= articulosPorFamilia; i++) {
                filas.add(articuloMs(String.format("F%03d-%03d", f, i), "Articulo " + f + "-" + i, 100.5 + i + f / 10000.0));
            }
        }
        return filas;
    }

    public static byte[] ms(int familias, int articulosPorFamilia) {
        return ListasDePrueba.xlsHojas(unaHoja("M-S ", msFilas(familias, articulosPorFamilia)));
    }

    private static String tituloMs(int familia) {
        return String.format("Familia: %03d - Familia %d", familia, familia);
    }

    /** Filas 1 a 14: membrete, encabezado en las filas 13 y 14 y el primer titulo en la A de la 14. */
    private static List<List<Object>> encabezadoMs(String primerTitulo) {
        List<List<Object>> filas = new ArrayList<>();
        filas.add(fila());
        filas.add(fila("M-S Distribuidora Mayorista"));
        filas.add(fila());
        filas.add(fila("Lista de precios vigente al 02/10/2026"));
        for (int i = 5; i <= 12; i++) {
            filas.add(fila());
        }
        filas.add(fila("Código", "Descripción", "Precio X", "Cantidad", null, "Precio"));
        filas.add(fila(primerTitulo, null, "Unidad.", "x Caja", null, "Final"));
        return filas;
    }

    private static List<Object> articuloMs(String codigo, String descripcion, double precio) {
        return fila(codigo, descripcion, precio, 10, null, precio);
    }

    // ---- lectura sin POI ----

    /**
     * Las filas como las deja el lector: numeradas desde 1, sin las filas vacias, con la hoja de cada una y los
     * numeros como celdas numericas. Sirve a los tests de dominio, que no necesitan abrir un archivo.
     */
    public static LecturaLista leida(Map<String, List<List<Object>>> hojas) {
        List<String> nombres = new ArrayList<>(hojas.keySet());
        List<FilaLeida> filas = new ArrayList<>();
        for (int h = 0; h < nombres.size(); h++) {
            List<List<Object>> filasDeLaHoja = hojas.get(nombres.get(h));
            for (int r = 0; r < filasDeLaHoja.size(); r++) {
                List<Celda> celdas = new ArrayList<>();
                for (Object valor : filasDeLaHoja.get(r)) {
                    celdas.add(celda(valor));
                }
                FilaLeida fila = new FilaLeida(h, r + 1, celdas);
                if (!fila.vacia()) {
                    filas.add(fila);
                }
            }
        }
        return new LecturaLista(nombres, filas);
    }

    private static Celda celda(Object valor) {
        if (valor == null) {
            return Celda.VACIA;
        }
        if (valor instanceof Number numero) {
            String texto = NumberToTextConverter.toText(numero.doubleValue());
            return new Celda(texto, new BigDecimal(texto));
        }
        return new Celda(valor.toString().strip(), null);
    }

    private static Map<String, List<List<Object>>> unaHoja(String nombre, List<List<Object>> filas) {
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put(nombre, filas);
        return hojas;
    }

    private static List<Object> fila(Object... valores) {
        return Arrays.asList(valores);
    }
}
