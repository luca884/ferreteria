package com.ferreteria.catalogo.service.lectura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.catalogo.model.LecturaLista;
import com.ferreteria.common.exception.BadRequestException;
import com.ferreteria.support.ListasDePrueba;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/**
 * Los topes de {@link LibroXlsx} sobre lo que de verdad trae el archivo (hallazgo alto 1 de la revision del 05/11): cada
 * archivo es un .xlsx valido al que se le agranda una parte, comprimida, asi que pesa poco.
 */
class LibroXlsxTopesTest {

    private static final String DEMASIADO_GRANDE = "La lista es demasiado grande para leerla. Dividila en archivos más chicos.";

    private final LectorExcel lector = new LectorExcel();

    @FunctionalInterface
    private interface Escritor {
        void escribir(OutputStream salida) throws IOException;
    }

    /** El .xlsx con las partes de {@code reemplazos} escritas de nuevo (o agregadas). */
    private static byte[] conPartes(byte[] xlsx, Map<String, Escritor> reemplazos) {
        try {
            ByteArrayOutputStream salida = new ByteArrayOutputStream();
            Map<String, Escritor> pendientes = new LinkedHashMap<>(reemplazos);
            try (ZipInputStream entrada = new ZipInputStream(new ByteArrayInputStream(xlsx));
                 ZipOutputStream zip = new ZipOutputStream(salida)) {
                ZipEntry entry;
                while ((entry = entrada.getNextEntry()) != null) {
                    byte[] datos = entrada.readAllBytes();
                    zip.putNextEntry(new ZipEntry(entry.getName()));
                    Escritor reemplazo = pendientes.remove(entry.getName());
                    if (reemplazo == null) {
                        zip.write(datos);
                    } else {
                        reemplazo.escribir(zip);
                    }
                    zip.closeEntry();
                }
                for (Map.Entry<String, Escritor> nueva : pendientes.entrySet()) {
                    zip.putNextEntry(new ZipEntry(nueva.getKey()));
                    nueva.getValue().escribir(zip);
                    zip.closeEntry();
                }
            }
            return salida.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void repetir(OutputStream salida, String texto, long veces) throws IOException {
        byte[] bloque = texto.repeat(8192 / Math.max(1, texto.length()) + 1).getBytes(StandardCharsets.UTF_8);
        long porBloque = bloque.length / texto.length();
        long faltan = veces;
        while (faltan >= porBloque) {
            salida.write(bloque);
            faltan -= porBloque;
        }
        for (long i = 0; i < faltan; i++) {
            salida.write(texto.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static byte[] base() {
        return ListasDePrueba.xlsx(List.of(Arrays.asList("A-1", "Tornillo", 10)));
    }

    /** Una hoja con este contenido dentro de {@code <sheetData>}. */
    private static Escritor hoja(Escritor datos) {
        return salida -> {
            salida.write(("<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet "
                    + "xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>")
                    .getBytes(StandardCharsets.UTF_8));
            datos.escribir(salida);
            salida.write("</sheetData></worksheet>".getBytes(StandardCharsets.UTF_8));
        };
    }

    @Test
    void unasCadenasCompartidasDeMasDeTreintaYDosMegasDicenDemasiadoGrande() {
        byte[] xlsx = conPartes(base(), Map.of("xl/sharedStrings.xml", salida -> {
            salida.write("<?xml version=\"1.0\"?><sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                    .getBytes(StandardCharsets.UTF_8));
            repetir(salida, "<si><t>" + "a".repeat(1000) + "</t></si>", 34_000);
            salida.write("</sst>".getBytes(StandardCharsets.UTF_8));
        }));

        assertThatThrownBy(() -> lector.leer(xlsx)).isInstanceOf(BadRequestException.class).hasMessage(DEMASIADO_GRANDE);
    }

    @Test
    void unValorDeCeldaDeMasDeUnMegaDiceDemasiadoGrandeSinArmarlo() {
        byte[] xlsx = conPartes(base(), Map.of("xl/worksheets/sheet1.xml", hoja(salida -> {
            salida.write("<row r=\"1\"><c r=\"A1\"><v>".getBytes(StandardCharsets.UTF_8));
            repetir(salida, "1", 2L * 1024 * 1024);
            salida.write("</v></c></row>".getBytes(StandardCharsets.UTF_8));
        })));

        assertThatThrownBy(() -> lector.leer(xlsx)).isInstanceOf(BadRequestException.class).hasMessage(DEMASIADO_GRANDE);
    }

    @Test
    void unComentarioLargoConMenoresAdentroTambienTieneTope() {
        // El parser junta el comentario entero: un < adentro no lo corta.
        byte[] xlsx = conPartes(base(), Map.of("xl/worksheets/sheet1.xml", hoja(salida -> {
            salida.write("<!--".getBytes(StandardCharsets.UTF_8));
            repetir(salida, "aaaaaaa<", 2L * 1024 * 1024 / 8);
            salida.write("-->".getBytes(StandardCharsets.UTF_8));
        })));

        assertThatThrownBy(() -> lector.leer(xlsx)).isInstanceOf(BadRequestException.class).hasMessage(DEMASIADO_GRANDE);
    }

    @Test
    void unComentarioChicoNoMolesta() {
        byte[] xlsx = conPartes(base(), Map.of("xl/worksheets/sheet1.xml", hoja(salida -> salida.write(
                "<!-- hecho a mano <con menores> --><row r=\"2\"><c r=\"A2\" t=\"inlineStr\"><is><t>X-9</t></is></c></row>"
                        .getBytes(StandardCharsets.UTF_8)))));

        LecturaLista lectura = lector.leer(xlsx);

        assertThat(lectura.filas()).singleElement().satisfies(f -> {
            assertThat(f.numero()).isEqualTo(2);
            assertThat(f.celda(0).texto()).isEqualTo("X-9");
        });
    }

    @Test
    void dosHojasQueEntreLasDosPasanElTopeDelLibroDicenDemasiadoGrande() {
        // Cada una entra en el tope por parte; juntas pasan el del libro entero (256 MB).
        long filas = LimitesDeLista.MAXIMO_ENTRADA_ZIP_BYTES / 2 / "<row/>".length() + 1_000_000;
        Escritor enorme = hoja(salida -> repetir(salida, "<row/>", filas));
        byte[] dos = ListasDePrueba.xlsxHojas(new LinkedHashMap<>(Map.of(
                "Uno", List.of(Arrays.asList("a")), "Dos", List.of(Arrays.asList("b")))));
        byte[] xlsx = conPartes(dos, Map.of("xl/worksheets/sheet1.xml", enorme, "xl/worksheets/sheet2.xml", enorme));

        assertThatThrownBy(() -> lector.leer(xlsx)).isInstanceOf(BadRequestException.class).hasMessage(DEMASIADO_GRANDE);
    }

    @Test
    void unXlsCifradoConLaContrasenaPorDefectoDeExcelNoSeDescifra() throws IOException {
        // Con el .xls cifrado, la cabecera de la tabla de cadenas viene cifrada (su tope no la ve) y POI la descifraba sola.
        byte[] cifrado;
        org.apache.poi.hssf.record.crypto.Biff8EncryptionKey.setCurrentUserPassword("VelvetSweatshop");
        try (var libro = new org.apache.poi.hssf.usermodel.HSSFWorkbook(); var salida = new ByteArrayOutputStream()) {
            libro.createSheet("Lista").createRow(0).createCell(0).setCellValue("A-1");
            libro.write(salida);
            cifrado = salida.toByteArray();
        } finally {
            org.apache.poi.hssf.record.crypto.Biff8EncryptionKey.setCurrentUserPassword(null);
        }

        assertThatThrownBy(() -> lector.leer(cifrado))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("El archivo está protegido con contraseña.");
    }

    @Test
    void unXlsxCifradoConLaContrasenaPorDefectoDeExcelNoSeDescifra() {
        // POI probaba "VelvetSweatshop" y leia el .xlsx de adentro entero en memoria, sin los topes.
        byte[] cifrado = ListasDePrueba.xlsxProtegido(List.of(Arrays.asList("a", 1)), "VelvetSweatshop");

        assertThatThrownBy(() -> lector.leer(cifrado))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("El archivo está protegido con contraseña.");
    }
}
