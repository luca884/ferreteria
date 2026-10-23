package com.ferreteria.common.csv;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * CSV que Excel en castellano abre directo, sin asistente: UTF-8 con BOM (si no, Excel lo lee como windows-1252),
 * separador ; (la coma es el separador decimal) y fin de linea CRLF.
 *
 * <p>Una celda {@link BigDecimal} es un numero: sale como viene, con coma decimal y sin separador de miles. Cualquier
 * otra es texto: si empieza con = + - @ tab o retorno lleva un apostrofo adelante, para que Excel no la ejecute como
 * formula (inyeccion CSV), y si tiene ; comillas o saltos de linea va entre comillas dobles. Un {@link #codigo} sale
 * como texto de Excel. null es la celda vacia.
 */
public final class EscritorCsv {

    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final String SEPARADOR = ";";
    private static final String FIN_DE_LINEA = "\r\n";

    private EscritorCsv() {
    }

    /** Un codigo como texto de Excel ({@code ="0012"}): no le saca los ceros ni lo pasa a numero. */
    private record Codigo(String valor) {}

    /**
     * La celda de un codigo, que sale como {@code ="..."}: Excel la muestra tal cual (sin sacarle los ceros a 0012 ni
     * pasar un codigo de barras a 7,79E+12) y, al guardar, escribe el texto. Es una formula que solo da ese texto, asi
     * que no lleva el apostrofo. null es la celda vacia. La importacion de CSV la vuelve a leer como su texto.
     */
    public static Object codigo(String codigo) {
        return codigo == null ? null : new Codigo(codigo);
    }

    public static byte[] escribir(List<String> encabezado, List<? extends List<?>> filas) {
        StringBuilder csv = new StringBuilder();
        linea(csv, encabezado);
        for (List<?> fila : filas) {
            linea(csv, fila);
        }
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        salida.writeBytes(BOM);
        salida.writeBytes(csv.toString().getBytes(StandardCharsets.UTF_8));
        return salida.toByteArray();
    }

    private static void linea(StringBuilder csv, List<?> celdas) {
        for (int i = 0; i < celdas.size(); i++) {
            if (i > 0) {
                csv.append(SEPARADOR);
            }
            csv.append(celda(celdas.get(i)));
        }
        csv.append(FIN_DE_LINEA);
    }

    private static String celda(Object valor) {
        if (valor == null) {
            return "";
        }
        if (valor instanceof BigDecimal numero) {
            return numero.toPlainString().replace('.', ',');
        }
        if (valor instanceof Codigo codigo) {
            String formula = "=\"" + codigo.valor().replace("\"", "\"\"") + "\"";
            // Las comillas de la formula no fuerzan el entrecomillado del CSV; el separador o un salto si.
            return tieneSeparadorOSalto(formula) ? entrecomillado(formula) : formula;
        }
        return texto(valor.toString());
    }

    private static String texto(String texto) {
        String seguro = pareceFormula(texto) ? "'" + texto : texto;
        return tieneSeparadorOSalto(seguro) || seguro.contains("\"") ? entrecomillado(seguro) : seguro;
    }

    private static boolean tieneSeparadorOSalto(String texto) {
        return texto.contains(SEPARADOR) || texto.contains("\n") || texto.contains("\r");
    }

    private static String entrecomillado(String texto) {
        return "\"" + texto.replace("\"", "\"\"") + "\"";
    }

    private static boolean pareceFormula(String texto) {
        return !texto.isEmpty() && "=+-@\t\r".indexOf(texto.charAt(0)) >= 0;
    }
}
