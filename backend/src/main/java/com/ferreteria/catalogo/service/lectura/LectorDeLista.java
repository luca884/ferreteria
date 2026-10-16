package com.ferreteria.catalogo.service.lectura;

import com.ferreteria.catalogo.model.LecturaLista;
import com.ferreteria.common.exception.BadRequestException;

/**
 * Lee un archivo de lista a la representacion comun de filas. Los lectores no tienen estado. Ante un
 * archivo que no se puede leer lanzan {@code BadRequestException}, nunca otra excepcion.
 */
public interface LectorDeLista {

    /**
     * Lee el archivo ENTERO: abre el libro una sola vez y devuelve las filas no vacias de todas las hojas, cada una con
     * su hoja y su numero original. Un CSV tiene una sola hoja, la 0.
     */
    LecturaLista leer(byte[] contenido);

    /**
     * Solo las filas de la hoja {@code hoja} (base 0), con los nombres de todas. Compatibilidad con el codigo de CU-04
     * que lee de a una hoja; se borra cuando el servicio pase a leer el libro entero.
     */
    default LecturaLista leer(byte[] contenido, int hoja) {
        LecturaLista libro = leer(contenido);
        if (hoja < 0 || hoja >= libro.hojas().size()) {
            throw new BadRequestException("La hoja elegida no existe en el archivo.");
        }
        return new LecturaLista(libro.hojas(), libro.filasDeHoja(hoja));
    }
}
