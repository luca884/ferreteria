package com.ferreteria.catalogo.service.lectura;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Hallazgo alto 1 de la revision del 05/11: un .xlsx chico con una hoja enorme agotaba la memoria del servidor de todas
 * las ferreterias, porque POI armaba el libro entero antes de los topes. Cada lectura corre en una JVM con 256 MB (el
 * servidor tiene 768 MB y los comparte con todo lo demas).
 */
class LectorExcelMemoriaTest {

    @TempDir
    Path carpeta;

    /** Una hoja de {@code filas} x {@code columnas} escrita en streaming (escribirla no necesita la memoria de leerla). */
    private Path xlsx(int filas, int columnas, boolean conTexto) throws IOException {
        Path archivo = carpeta.resolve("lista-" + filas + "x" + columnas + ".xlsx");
        try (SXSSFWorkbook libro = new SXSSFWorkbook(100); OutputStream salida = Files.newOutputStream(archivo)) {
            Sheet hoja = libro.createSheet("LISTA");
            for (int r = 0; r < filas; r++) {
                Row fila = hoja.createRow(r);
                for (int c = 0; c < columnas; c++) {
                    if (conTexto && c < 2) {
                        fila.createCell(c).setCellValue((c == 0 ? "COD-" : "Tornillo hexagonal galvanizado ") + r);
                    } else {
                        fila.createCell(c).setCellValue(1000.5 + r + c);
                    }
                }
            }
            libro.write(salida);
            libro.dispose();
        }
        return archivo;
    }

    @Test
    void unXlsxChicoConUnaHojaEnormeDiceQueTieneDemasiadasFilasSinAgotarLaMemoria() throws Exception {
        // ~20 MB de hoja descomprimida en un archivo de ~2 MB: la variante chica del archivo del hallazgo (3,8 MB, 46 MB).
        Path bomba = xlsx(100_000, 12, false);

        LecturaEnJvmChica.Resultado resultado = LecturaEnJvmChica.leer(bomba, "256m");

        assertThat(resultado.texto()).as("salida de la JVM chica (codigo %d)", resultado.salida())
            .contains("400 La lista tiene más de 20.000 filas. Dividila en archivos más chicos.");
        assertThat(resultado.salida()).isZero();
    }

    @Test
    void unaListaRealDeVeinteMilFilasSeLeeEnteraConPocaMemoria() throws Exception {
        Path lista = xlsx(20_000, 9, true);

        LecturaEnJvmChica.Resultado resultado = LecturaEnJvmChica.leer(lista, "256m");

        assertThat(resultado.texto()).as("salida de la JVM chica (codigo %d)", resultado.salida())
            .contains("FILAS 20000");
        assertThat(resultado.salida()).isZero();
    }
}
