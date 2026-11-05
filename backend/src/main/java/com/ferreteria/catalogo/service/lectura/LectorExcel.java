package com.ferreteria.catalogo.service.lectura;

import com.ferreteria.catalogo.model.LecturaLista;
import com.ferreteria.common.exception.BadRequestException;
import java.io.IOException;
import java.util.Optional;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.hssf.OldExcelFormatException;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.util.IOUtils;
import org.apache.poi.util.RecordFormatException;
import org.springframework.stereotype.Component;

/**
 * Lee .xlsx y .xls con el mismo resultado. El .xlsx con {@link LibroXlsx} y el .xls con {@link LibroXls}, que leen cada hoja
 * de corrido en lugar de armar el libro en memoria (hallazgo alto 1 de la revision del 05/11) y dan las mismas celdas que
 * POI usermodel. Las formulas usan el valor cacheado y los numeros se convierten con {@link org.apache.poi.ss.util.NumberToTextConverter}, nunca con
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
            return leerXls(contenido);
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
     * Lee un .xls sin armar el libro en memoria ({@link LibroXls}); es el unico lugar donde se abre uno. Un archivo cifrado
     * se rechaza sin descifrarlo: POI probaria la contraseña por defecto de Excel.
     */
    LecturaLista leerXls(byte[] contenido) throws IOException {
        return LibroXls.leer(contenido);
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
}
