package com.ferreteria.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Arma archivos de lista de proveedor para los tests. En Excel, un {@code String} es una celda de texto,
 * un {@code Number} una celda numerica, un {@link ConFormato} una celda numerica con formato, un {@link Formula}
 * una formula (se evalua para dejar el valor cacheado) y {@code null} una celda sin crear.
 */
public final class ListasDePrueba {

    /** Formula de Excel sin el signo igual, por ejemplo {@code new Formula("B2*1.1")}. */
    public record Formula(String expresion) {
    }

    /**
     * Una celda numerica con formato de numero de Excel, por ejemplo {@code new ConFormato(0.21, "0%")} (se ve 21 %).
     * Sirve para probar que el lector avisa cuando el formato es de porcentaje.
     */
    public record ConFormato(Number valor, String formato) {
    }

    private ListasDePrueba() {
    }

    public static byte[] xlsx(List<List<Object>> filas) {
        return xlsxHojas(Map.of("Hoja1", filas));
    }

    /** Un libro con varias hojas, en el orden de iteracion del mapa (usar LinkedHashMap). */
    public static byte[] xlsxHojas(Map<String, List<List<Object>>> hojas) {
        try (Workbook libro = new XSSFWorkbook()) {
            return escribir(libro, hojas);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static byte[] xls(List<List<Object>> filas) {
        return xlsHojas(Map.of("Hoja1", filas));
    }

    public static byte[] xlsHojas(Map<String, List<List<Object>>> hojas) {
        try (Workbook libro = new HSSFWorkbook()) {
            return escribir(libro, hojas);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Un xlsx cifrado con contrasena (formato agile, como el de Excel): POI lo ve como OLE2 con EncryptedPackage. */
    public static byte[] xlsxProtegido(List<List<Object>> filas, String contrasena) {
        try (POIFSFileSystem fs = new POIFSFileSystem()) {
            EncryptionInfo info = new EncryptionInfo(EncryptionMode.agile);
            Encryptor encryptor = info.getEncryptor();
            encryptor.confirmPassword(contrasena);
            try (OutputStream cifrado = encryptor.getDataStream(fs)) {
                cifrado.write(xlsx(filas));
            }
            ByteArrayOutputStream salida = new ByteArrayOutputStream();
            fs.writeFilesystem(salida);
            return salida.toByteArray();
        } catch (IOException | GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Un zip valido que no es un libro de Excel (un .docx minimo). */
    public static byte[] docx() {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(salida)) {
            zip.putNextEntry(new ZipEntry("[Content_Types].xml"));
            zip.write("<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>"
                .getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("word/document.xml"));
            zip.write("<w:document/>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return salida.toByteArray();
    }

    public static byte[] csv(List<List<String>> filas, char separador, Charset charset, boolean bom) {
        StringBuilder sb = new StringBuilder();
        for (List<String> fila : filas) {
            for (int i = 0; i < fila.size(); i++) {
                if (i > 0) {
                    sb.append(separador);
                }
                sb.append(campo(fila.get(i), separador));
            }
            sb.append('\n');
        }
        byte[] cuerpo = sb.toString().getBytes(charset);
        if (!bom) {
            return cuerpo;
        }
        byte[] conBom = new byte[cuerpo.length + 3];
        conBom[0] = (byte) 0xEF;
        conBom[1] = (byte) 0xBB;
        conBom[2] = (byte) 0xBF;
        System.arraycopy(cuerpo, 0, conBom, 3, cuerpo.length);
        return conBom;
    }

    /** Renglones por pagina de {@link #pdf}: con mas filas, la lista sigue en otra pagina. */
    public static final int RENGLONES_POR_PAGINA_PDF = 25;

    /** A4 apaisada: entran siete columnas, como en LISTA 336. */
    private static final PDRectangle HOJA_PDF = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());

    /**
     * Un PDF con texto, como una lista impresa: cada fila es un renglon y cada celda arranca en su columna, a 110 pt una
     * de otra, en Helvetica de 10 pt, en hojas A4 apaisadas. Una celda vacia o null no se escribe.
     */
    public static byte[] pdf(List<List<String>> filas) {
        return pdfProtegido(filas, null);
    }

    /** El mismo PDF de {@link #pdf}, cifrado con contrasena de apertura (si no es null). */
    public static byte[] pdfProtegido(List<List<String>> filas, String contrasena) {
        try (PDDocument documento = new PDDocument()) {
            PDType1Font fuente = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            for (int desde = 0; desde == 0 || desde < filas.size(); desde += RENGLONES_POR_PAGINA_PDF) {
                PDPage pagina = new PDPage(HOJA_PDF);
                documento.addPage(pagina);
                try (PDPageContentStream contenido = new PDPageContentStream(documento, pagina)) {
                    for (int i = desde; i < Math.min(filas.size(), desde + RENGLONES_POR_PAGINA_PDF); i++) {
                        float y = HOJA_PDF.getHeight() - 40 - (i - desde) * 20;
                        List<String> fila = filas.get(i);
                        for (int c = 0; c < fila.size(); c++) {
                            if (fila.get(c) != null && !fila.get(c).isEmpty()) {
                                contenido.beginText();
                                contenido.setFont(fuente, 10);
                                contenido.newLineAtOffset(40 + 110 * c, y);
                                contenido.showText(fila.get(c));
                                contenido.endText();
                            }
                        }
                    }
                }
            }
            if (contrasena != null) {
                StandardProtectionPolicy politica =
                    new StandardProtectionPolicy("dueno-" + contrasena, contrasena, new AccessPermission());
                politica.setEncryptionKeyLength(128);
                documento.protect(politica);
            }
            return guardar(documento);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Un PDF de una pagina sin texto, como uno escaneado. */
    public static byte[] pdfSinTexto() {
        try (PDDocument documento = new PDDocument()) {
            documento.addPage(new PDPage(PDRectangle.A4));
            return guardar(documento);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Un PDF de una pagina llena de letras: {@code renglones} renglones de {@code letras} letras seguidas (una palabra
     * por renglon), en Helvetica de 1 pt. Para leerla, PDFBox guarda en memoria cada letra de la pagina.
     */
    public static byte[] pdfConPaginaLlena(int renglones, int letras) {
        try (PDDocument documento = new PDDocument()) {
            PDPage pagina = new PDPage(HOJA_PDF);
            documento.addPage(pagina);
            String renglon = "ABCDEFGHIJ".repeat(letras / 10);
            try (PDPageContentStream contenido = new PDPageContentStream(documento, pagina)) {
                contenido.beginText();
                contenido.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 1);
                contenido.newLineAtOffset(10, HOJA_PDF.getHeight() - 10);
                for (int i = 0; i < renglones; i++) {
                    contenido.showText(renglon);
                    contenido.newLineAtOffset(0, -1);
                }
                contenido.endText();
            }
            return guardar(documento);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] guardar(PDDocument documento) throws IOException {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        documento.save(salida);
        return salida.toByteArray();
    }

    private static String campo(String valor, char separador) {
        if (valor == null) {
            return "";
        }
        boolean entrecomillar = valor.indexOf(separador) >= 0 || valor.indexOf('"') >= 0
            || valor.indexOf('\n') >= 0 || valor.indexOf('\r') >= 0;
        return entrecomillar ? "\"" + valor.replace("\"", "\"\"") + "\"" : valor;
    }

    private static byte[] escribir(Workbook libro, Map<String, List<List<Object>>> hojas) throws IOException {
        Map<String, CellStyle> estilos = new HashMap<>();
        hojas.forEach((nombre, filas) -> {
            Sheet hoja = libro.createSheet(nombre);
            for (int r = 0; r < filas.size(); r++) {
                Row fila = hoja.createRow(r);
                List<Object> valores = filas.get(r);
                for (int c = 0; c < valores.size(); c++) {
                    escribirCelda(libro, estilos, fila, c, valores.get(c));
                }
            }
        });
        libro.getCreationHelper().createFormulaEvaluator().evaluateAll();
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        libro.write(salida);
        return salida.toByteArray();
    }

    private static void escribirCelda(Workbook libro, Map<String, CellStyle> estilos, Row fila, int columna,
                                      Object valor) {
        if (valor == null) {
            return;
        }
        Cell celda = fila.createCell(columna);
        if (valor instanceof ConFormato conFormato) {
            celda.setCellValue(conFormato.valor().doubleValue());
            // Un estilo por formato y libro: Excel admite pocos miles de estilos.
            celda.setCellStyle(estilos.computeIfAbsent(conFormato.formato(), formato -> {
                CellStyle estilo = libro.createCellStyle();
                estilo.setDataFormat(libro.createDataFormat().getFormat(formato));
                return estilo;
            }));
        } else if (valor instanceof Formula formula) {
            celda.setCellFormula(formula.expresion());
        } else if (valor instanceof Number numero) {
            celda.setCellValue(numero.doubleValue());
        } else {
            celda.setCellValue(valor.toString());
        }
    }
}
