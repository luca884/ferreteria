package com.ferreteria.catalogo.model;

/**
 * Un error o una advertencia de la importacion. {@code hoja} es la hoja del archivo (base 0; null si no se sabe o
 * no corresponde), {@code fila} la fila del archivo (null en las advertencias generales, como el cambio de
 * encabezado) y {@code codigo} el codigo del proveedor de esa fila (null si la fila no tiene).
 */
public record AvisoDeFila(Integer hoja, Integer fila, String codigo, String mensaje) {

    /** Un aviso sin hoja: es el de las importaciones de una sola hoja anteriores al corte 2. */
    public AvisoDeFila(Integer fila, String codigo, String mensaje) {
        this(null, fila, codigo, mensaje);
    }
}
