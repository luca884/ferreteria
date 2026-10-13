package com.ferreteria.support;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Arma archivos de lista de proveedor para los tests. En Excel, un {@code String} es una celda de texto,
 * un {@code Number} una celda numerica, un {@link Formula} una formula (se evalua para dejar el valor
 * cacheado) y {@code null} una celda sin crear.
 */
public final class ListasDePrueba {

    /** Formula de Excel sin el signo igual, por ejemplo {@code new Formula("B2*1.1")}. */
    public record Formula(String expresion) {
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

    private static String campo(String valor, char separador) {
        if (valor == null) {
            return "";
        }
        boolean entrecomillar = valor.indexOf(separador) >= 0 || valor.indexOf('"') >= 0
            || valor.indexOf('\n') >= 0 || valor.indexOf('\r') >= 0;
        return entrecomillar ? "\"" + valor.replace("\"", "\"\"") + "\"" : valor;
    }

    private static byte[] escribir(Workbook libro, Map<String, List<List<Object>>> hojas) throws IOException {
        hojas.forEach((nombre, filas) -> {
            Sheet hoja = libro.createSheet(nombre);
            for (int r = 0; r < filas.size(); r++) {
                Row fila = hoja.createRow(r);
                List<Object> valores = filas.get(r);
                for (int c = 0; c < valores.size(); c++) {
                    escribirCelda(fila, c, valores.get(c));
                }
            }
        });
        libro.getCreationHelper().createFormulaEvaluator().evaluateAll();
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        libro.write(salida);
        return salida.toByteArray();
    }

    private static void escribirCelda(Row fila, int columna, Object valor) {
        if (valor == null) {
            return;
        }
        Cell celda = fila.createCell(columna);
        if (valor instanceof Formula formula) {
            celda.setCellFormula(formula.expresion());
        } else if (valor instanceof Number numero) {
            celda.setCellValue(numero.doubleValue());
        } else {
            celda.setCellValue(valor.toString());
        }
    }
}
