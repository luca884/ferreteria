package com.ferreteria.catalogo.service.lectura;

import com.ferreteria.catalogo.model.LecturaLista;

/**
 * Lee un archivo de lista a la representacion comun de filas. Los lectores no tienen estado. Ante un
 * archivo que no se puede leer lanzan {@code BadRequestException}, nunca otra excepcion.
 */
public interface LectorDeLista {

    /** @param hoja indice (desde 0) de la hoja a leer; un CSV tiene una sola hoja */
    LecturaLista leer(byte[] contenido, int hoja);
}
