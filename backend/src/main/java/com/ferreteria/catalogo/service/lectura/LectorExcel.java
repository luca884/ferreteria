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
import org.apache.poi.util.RecordFormatException;
import org.springframework.stereotype.Component;

/**
 * Lee .xlsx y .xls con el mismo codigo (POI usermodel). Las formulas usan el valor cacheado y los numeros
 * se convierten con {@link NumberToTextConverter}, nunca con {@code new BigDecimal(double)}. Una celda numerica
 * con formato de porcentaje lo avisa ({@code Celda.porcentaje}).
 */
@Component
public class LectorExcel implements LectorDeLista {

    private static final String ARCHIVO_DANADO = "No se pudo leer el archivo. Verificá que no esté dañado.";

    static {
        // Limites globales de POI, mas bajos que los por defecto: un archivo que los pasa da un error (que se informa
        // como lista demasiado grande, ver superaUnTope) en lugar de agotar la memoria del servidor compartido.
        ZipSecureFile.setMaxEntrySize(LimitesDeLista.MAXIMO_ENTRADA_ZIP_BYTES);
        IOUtils.setByteArrayMaxOverride(LimitesDeLista.MAXIMO_ARREGLO_POI_BYTES);
    }

    /**
     * Todas las hojas con una sola apertura del libro: las filas llevan su hoja (base 0). El tope grueso cuenta las
     * filas no vacias de TODO el libro.
     */
    @Override
    public LecturaLista leer(byte[] contenido) {
        try (Workbook libro = abrir(contenido)) {
            return leerLibro(libro);
        } catch (BadRequestException e) {
            throw e;
        } catch (EncryptedDocumentException e) {
            throw new BadRequestException("El archivo está protegido con contraseña.");
        } catch (OldExcelFormatException e) {
            throw new BadRequestException("Es un Excel muy viejo. Guardalo como .xlsx e intentá de nuevo.");
        } catch (IOException | RuntimeException e) {
            throw superaUnTope(e) ? LimitesDeLista.demasiadoGrande() : new BadRequestException(ARCHIVO_DANADO);
        }
    }

    /**
     * Un archivo valido pero enorme choca contra los topes de POI: la relacion de compresion o el tamano inflado
     * ("Zip bomb detected", IOException a veces envuelta) o el arreglo maximo (RecordFormatException "Tried to
     * allocate"). No es un archivo danado: decirlo manda al usuario a buscar un problema que no tiene.
     */
    private static boolean superaUnTope(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String mensaje = t.getMessage() == null ? "" : t.getMessage();
            if (mensaje.contains("Zip bomb detected")
                    || (t instanceof RecordFormatException && mensaje.startsWith("Tried to allocate"))) {
                return true;
            }
        }
        return false;
    }

    /** Abre el libro; es el unico lugar donde se abre, asi un test puede contar las aperturas. */
    Workbook abrir(byte[] contenido) throws IOException {
        return WorkbookFactory.create(new ByteArrayInputStream(contenido));
    }

    private LecturaLista leerLibro(Workbook libro) {
        List<String> nombres = new ArrayList<>();
        for (int i = 0; i < libro.getNumberOfSheets(); i++) {
            nombres.add(libro.getSheetName(i));
        }
        List<FilaLeida> filas = new ArrayList<>();
        for (int indiceHoja = 0; indiceHoja < nombres.size(); indiceHoja++) {
            Sheet hoja = libro.getSheetAt(indiceHoja);
            for (int r = Math.max(hoja.getFirstRowNum(), 0); r <= hoja.getLastRowNum(); r++) {
                Row row = hoja.getRow(r);
                if (row == null) {
                    continue;
                }
                FilaLeida fila = new FilaLeida(indiceHoja, r + 1, celdas(row));
                if (!fila.vacia()) {
                    // Tope grueso: no se sigue armando la lista una vez pasado.
                    if (filas.size() >= LimitesDeLista.MAXIMO_FILAS_LEIDAS) {
                        throw LimitesDeLista.demasiadasFilas();
                    }
                    filas.add(fila);
                }
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
            case NUMERIC -> numerica(cell.getNumericCellValue(), conFormatoDePorcentaje(cell));
            case STRING -> new Celda(cell.getStringCellValue().strip(), null);
            case BOOLEAN -> new Celda(cell.getBooleanCellValue() ? "TRUE" : "FALSE", null);
            case ERROR -> new Celda(FormulaError.forInt(cell.getErrorCellValue()).getString(), null);
            default -> Celda.VACIA;
        };
    }

    private Celda numerica(double valor, boolean porcentaje) {
        String texto = NumberToTextConverter.toText(valor);
        try {
            return new Celda(texto, new BigDecimal(texto), porcentaje);
        } catch (NumberFormatException e) {
            return new Celda(texto, null);
        }
    }

    /** El formato de la celda tiene un %: 21 % se guarda como 0,21 y en pantalla se ve 21. */
    private static boolean conFormatoDePorcentaje(Cell cell) {
        String formato = cell.getCellStyle() == null ? null : cell.getCellStyle().getDataFormatString();
        return formato != null && formato.contains("%");
    }
}
