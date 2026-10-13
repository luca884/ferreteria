package com.ferreteria.catalogo.service.lectura;

import com.ferreteria.catalogo.model.Celda;
import com.ferreteria.catalogo.model.FilaLeida;
import com.ferreteria.catalogo.model.LecturaLista;
import com.ferreteria.common.exception.BadRequestException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.hssf.OldExcelFormatException;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.apache.poi.util.IOUtils;
import org.springframework.stereotype.Component;

/**
 * Lee .xlsx y .xls con el mismo codigo (POI usermodel). Las formulas usan el valor cacheado y los numeros
 * se convierten con {@link NumberToTextConverter}, nunca con {@code new BigDecimal(double)}.
 */
@Component
public class LectorExcel implements LectorDeLista {

    private static final String ARCHIVO_DANADO = "No se pudo leer el archivo. Verificá que no esté dañado.";

    static {
        // Limites globales de POI, mas bajos que los por defecto: un archivo que los pasa da IOException
        // (que se informa como archivo danado) en lugar de agotar la memoria del servidor compartido.
        ZipSecureFile.setMaxEntrySize(LimitesDeLista.MAXIMO_ENTRADA_ZIP_BYTES);
        IOUtils.setByteArrayMaxOverride(LimitesDeLista.MAXIMO_ARREGLO_POI_BYTES);
    }

    @Override
    public LecturaLista leer(byte[] contenido, int hoja) {
        try (Workbook libro = WorkbookFactory.create(new ByteArrayInputStream(contenido))) {
            return leerLibro(libro, hoja);
        } catch (BadRequestException e) {
            throw e;
        } catch (EncryptedDocumentException e) {
            throw new BadRequestException("El archivo está protegido con contraseña.");
        } catch (OldExcelFormatException e) {
            throw new BadRequestException("Es un Excel muy viejo. Guardalo como .xlsx e intentá de nuevo.");
        } catch (IOException | RuntimeException e) {
            throw new BadRequestException(ARCHIVO_DANADO);
        }
    }

    private LecturaLista leerLibro(Workbook libro, int indiceHoja) {
        List<String> nombres = new ArrayList<>();
        for (int i = 0; i < libro.getNumberOfSheets(); i++) {
            nombres.add(libro.getSheetName(i));
        }
        if (indiceHoja < 0 || indiceHoja >= nombres.size()) {
            throw new BadRequestException("La hoja elegida no existe en el archivo.");
        }
        Sheet hoja = libro.getSheetAt(indiceHoja);
        List<FilaLeida> filas = new ArrayList<>();
        for (int r = hoja.getFirstRowNum(); r <= hoja.getLastRowNum(); r++) {
            Row row = hoja.getRow(r);
            if (row == null) {
                continue;
            }
            FilaLeida fila = new FilaLeida(r + 1, celdas(row));
            if (!fila.vacia()) {
                // Tope grueso: no se sigue armando la lista una vez pasado.
                if (filas.size() >= LimitesDeLista.MAXIMO_FILAS_LEIDAS) {
                    throw LimitesDeLista.demasiadasFilas();
                }
                filas.add(fila);
            }
        }
        return new LecturaLista(nombres, filas);
    }

    private List<Celda> celdas(Row row) {
        int total = Math.max(row.getLastCellNum(), 0);
        List<Celda> celdas = new ArrayList<>(total);
        for (int c = 0; c < total; c++) {
            celdas.add(celda(row.getCell(c)));
        }
        return celdas;
    }

    private Celda celda(Cell cell) {
        if (cell == null) {
            return Celda.VACIA;
        }
        CellType tipo = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (tipo) {
            case NUMERIC -> numerica(cell.getNumericCellValue());
            case STRING -> new Celda(cell.getStringCellValue().strip(), null);
            case BOOLEAN -> new Celda(cell.getBooleanCellValue() ? "TRUE" : "FALSE", null);
            case ERROR -> new Celda(FormulaError.forInt(cell.getErrorCellValue()).getString(), null);
            default -> Celda.VACIA;
        };
    }

    private Celda numerica(double valor) {
        String texto = NumberToTextConverter.toText(valor);
        try {
            return new Celda(texto, new BigDecimal(texto));
        } catch (NumberFormatException e) {
            return new Celda(texto, null);
        }
    }
}
