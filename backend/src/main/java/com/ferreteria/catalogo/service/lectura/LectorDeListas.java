package com.ferreteria.catalogo.service.lectura;

import com.ferreteria.catalogo.model.LecturaLista;
import org.springframework.stereotype.Component;

/**
 * Fachada que usa el servicio de importacion: detecta el formato por los bytes y delega en el lector
 * que corresponde. El tope grueso de filas lo aplica cada lector mientras lee ({@link LimitesDeLista});
 * el exacto lo aplica el servicio. No implementa {@link LectorDeLista} para que no haya dos candidatos
 * al inyectar un lector.
 */
@Component
public class LectorDeListas {

    private final LectorExcel lectorExcel;
    private final LectorCsv lectorCsv;

    public LectorDeListas(LectorExcel lectorExcel, LectorCsv lectorCsv) {
        this.lectorExcel = lectorExcel;
        this.lectorCsv = lectorCsv;
    }

    /** Todas las hojas del archivo, en una sola lectura. */
    public LecturaLista leer(byte[] contenido) {
        return lectorPara(contenido).leer(contenido);
    }

    /** Solo las filas de la hoja pedida (base 0); se borra cuando el servicio lea el libro entero. */
    public LecturaLista leer(byte[] contenido, int hoja) {
        return lectorPara(contenido).leer(contenido, hoja);
    }

    private LectorDeLista lectorPara(byte[] contenido) {
        return switch (DetectorDeFormato.detectar(contenido)) {
            case XLSX, XLS -> lectorExcel;
            case CSV -> lectorCsv;
        };
    }
}
