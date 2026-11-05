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
import java.util.Optional;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.hssf.OldExcelFormatException;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.poifs.crypt.Decryptor;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
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
 * Lee .xlsx y .xls con el mismo resultado. El .xls con POI usermodel; el .xlsx con {@link LibroXlsx}, que lee cada hoja
 * de corrido en lugar de armar el libro en memoria (hallazgo alto 1 de la revision del 05/11) y da las mismas celdas. Las
 * formulas usan el valor cacheado y los numeros se convierten con {@link NumberToTextConverter}, nunca con
 * {@code new BigDecimal(double)}. Una celda numerica con formato de porcentaje lo avisa ({@code Celda.porcentaje}).
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
        try {
            if (FileMagic.valueOf(contenido) == FileMagic.OOXML) {
                return leerXlsx(contenido);
            }
            try (Workbook libro = abrir(contenido)) {
                return leerLibro(libro);
            }
        } catch (EncryptedDocumentException e) {
            throw new BadRequestException("El archivo está protegido con contraseña.");
        } catch (OldExcelFormatException e) {
            throw new BadRequestException("Es un Excel muy viejo. Guardalo como .xlsx e intentá de nuevo.");
        } catch (IOException | RuntimeException e) {
            Optional<BadRequestException> tope = topePasado(e);
            if (tope.isPresent()) {
                throw tope.get();
            }
            throw superaUnTope(e) ? LimitesDeLista.demasiadoGrande() : new BadRequestException(ARCHIVO_DANADO);
        }
    }

    /**
     * Un tope de la lectura (filas, columnas, tamano) sale tal cual aunque venga envuelto (el parser SAX o un map lo
     * envuelven).
     */
    private static Optional<BadRequestException> topePasado(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof BadRequestException tope) {
                return Optional.of(tope);
            }
        }
        return Optional.empty();
    }

    /** Lee un .xlsx sin armar el libro en memoria ({@link LibroXlsx}); es el unico lugar donde se abre uno. */
    LecturaLista leerXlsx(byte[] contenido) throws IOException {
        return LibroXlsx.leer(contenido);
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

    /**
     * Abre un .xls (o lo que no es un .xlsx); es el unico lugar donde se abre, asi un test puede contar las aperturas. Un
     * archivo cifrado se rechaza sin descifrarlo: POI probaria la contraseña por defecto de Excel y armaria el .xlsx de
     * adentro entero en memoria, sin los topes de {@link LibroXlsx}.
     */
    Workbook abrir(byte[] contenido) throws IOException {
        if (FileMagic.valueOf(contenido) == FileMagic.OLE2) {
            POIFSFileSystem sistema = new POIFSFileSystem(new ByteArrayInputStream(contenido));
            if (sistema.getRoot().hasEntryCaseInsensitive(Decryptor.DEFAULT_POIFS_ENTRY)) {
                sistema.close();
                throw new EncryptedDocumentException("Archivo cifrado");
            }
            return WorkbookFactory.create(sistema);
        }
        return WorkbookFactory.create(new ByteArrayInputStream(contenido));
    }

    private LecturaLista leerLibro(Workbook libro) {
        List<String> nombres = new ArrayList<>();
        for (int i = 0; i < libro.getNumberOfSheets(); i++) {
            nombres.add(libro.getSheetName(i));
        }
        List<FilaLeida> filas = new ArrayList<>();
        LimitesDeLista.TopeDeCeldas tope = new LimitesDeLista.TopeDeCeldas();
        for (int indiceHoja = 0; indiceHoja < nombres.size(); indiceHoja++) {
            Sheet hoja = libro.getSheetAt(indiceHoja);
            for (int r = Math.max(hoja.getFirstRowNum(), 0); r <= hoja.getLastRowNum(); r++) {
                Row row = hoja.getRow(r);
                if (row == null) {
                    continue;
                }
                FilaLeida fila = new FilaLeida(indiceHoja, r + 1, celdas(row, tope));
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

    /**
     * Las celdas hasta la ultima con datos. Recorre solo las celdas que existen (no hasta {@code getLastCellNum}):
     * una celda vacia con formato en la columna XFD no arma 16.384 celdas por fila. Un dato mas alla de la
     * columna 200 rechaza la lista antes de seguir.
     */
    private List<Celda> celdas(Row row, LimitesDeLista.TopeDeCeldas tope) {
        List<Celda> celdas = new ArrayList<>();
        for (Cell cell : row) {
            Celda celda = celda(cell);
            if (celda.vacia()) {
                continue;
            }
            int columna = cell.getColumnIndex();
            if (columna >= LimitesDeLista.MAXIMO_COLUMNAS) {
                throw LimitesDeLista.demasiadasColumnas();
            }
            while (celdas.size() < columna) {
                celdas.add(Celda.VACIA);
            }
            celdas.add(celda);
        }
        tope.sumarFila(celdas.size());
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
