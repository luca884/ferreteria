package com.ferreteria.catalogo.service.lectura;

import com.ferreteria.catalogo.model.LecturaLista;

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
}
