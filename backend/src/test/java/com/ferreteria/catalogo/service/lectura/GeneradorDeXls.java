package com.ferreteria.catalogo.service.lectura;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.poi.hssf.usermodel.HSSFCellStyle;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;

/**
 * Escribe .xls enormes para las pruebas de memoria, en una JVM aparte con el heap que haga falta: armarlos con POI
 * usermodel necesita ~1 GB (un objeto por celda), aunque el archivo pese pocos MB.
 */
public final class GeneradorDeXls {

    private GeneradorDeXls() {
    }

    /**
     * {@code args}: archivo, filas, columnas y contenido: "blancas" (celdas en blanco con estilo), "numeros" o "cadenas"
     * (una cadena distinta de pocos caracteres en cada celda: la tabla de cadenas compartidas con mas entradas por byte).
     */
    public static void main(String[] args) throws Exception {
        int filas = Integer.parseInt(args[1]);
        int columnas = Integer.parseInt(args[2]);
        String contenido = args[3];
        try (HSSFWorkbook libro = new HSSFWorkbook(); OutputStream salida = Files.newOutputStream(Path.of(args[0]))) {
            HSSFCellStyle estilo = libro.createCellStyle();
            estilo.setFillForegroundColor(IndexedColors.YELLOW.getIndex());
            estilo.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            HSSFSheet hoja = libro.createSheet("LISTA");
            for (int r = 0; r < filas; r++) {
                HSSFRow fila = hoja.createRow(r);
                for (int c = 0; c < columnas; c++) {
                    switch (contenido) {
                        case "blancas" -> fila.createCell(c).setCellStyle(estilo);
                        case "cadenas" -> fila.createCell(c).setCellValue(Integer.toString((int) ((long) r * columnas + c), 36));
                        default -> fila.createCell(c).setCellValue(r + c + 0.5);
                    }
                }
            }
            libro.write(salida);
        }
    }

    /** Genera el .xls en una JVM con {@code heap} y devuelve el archivo. */
    static Path generar(Path carpeta, String nombre, int filas, int columnas, String contenido, String heap)
            throws Exception {
        Path archivo = carpeta.resolve(nombre);
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> comando = new ArrayList<>(List.of(java, "-Xmx" + heap, "-cp", System.getProperty("java.class.path"),
                GeneradorDeXls.class.getName(), archivo.toString(), String.valueOf(filas), String.valueOf(columnas),
                contenido));
        Process proceso = new ProcessBuilder(comando).redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!proceso.waitFor(5, TimeUnit.MINUTES) || proceso.exitValue() != 0) {
            proceso.destroyForcibly();
            throw new AssertionError("No se pudo generar el .xls de prueba");
        }
        return archivo;
    }
}
