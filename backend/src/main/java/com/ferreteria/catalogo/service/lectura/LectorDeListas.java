package com.ferreteria.catalogo.service.lectura;

import com.ferreteria.catalogo.model.LecturaLista;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Fachada que usa el servicio de importacion: detecta el formato por los bytes y delega en el lector
 * que corresponde. El tope grueso de filas lo aplica cada lector mientras lee ({@link LimitesDeLista});
 * el exacto lo aplica el servicio. No implementa {@link LectorDeLista} para que no haya dos candidatos
 * al inyectar un lector.
 *
 * <p>Un CSV o un Excel se leen de a uno en todo el servidor ({@link TurnoDeLectura}): se arman enteros en la memoria
 * del servidor compartido. El PDF tiene su propio turno, en {@link LectorPdf}, porque se lee en una JVM aparte.
 */
@Component
public class LectorDeListas {

    private static final Duration ESPERA = Duration.ofSeconds(30);

    private final LectorExcel lectorExcel;
    private final LectorCsv lectorCsv;
    private final LectorPdf lectorPdf;
    private final TurnoDeLectura turno;

    @Autowired
    public LectorDeListas(LectorExcel lectorExcel, LectorCsv lectorCsv, LectorPdf lectorPdf) {
        this(lectorExcel, lectorCsv, lectorPdf, new TurnoDeLectura(ESPERA));
    }

    /** Con otra espera por turno, para probarlo. */
    LectorDeListas(LectorExcel lectorExcel, LectorCsv lectorCsv, LectorPdf lectorPdf, TurnoDeLectura turno) {
        this.lectorExcel = lectorExcel;
        this.lectorCsv = lectorCsv;
        this.lectorPdf = lectorPdf;
        this.turno = turno;
    }

    /** Todas las hojas del archivo, en una sola lectura. */
    public LecturaLista leer(byte[] contenido) {
        return switch (DetectorDeFormato.detectar(contenido)) {
            case XLSX, XLS -> turno.conTurno(() -> lectorExcel.leer(contenido));
            case CSV -> turno.conTurno(() -> lectorCsv.leer(contenido));
            case PDF -> lectorPdf.leer(contenido);
        };
    }
}
