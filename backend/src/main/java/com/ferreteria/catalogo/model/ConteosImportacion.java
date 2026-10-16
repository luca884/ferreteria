package com.ferreteria.catalogo.model;

/**
 * Los contadores de una importacion: suben, bajan, nuevos, sin cambio, no estan y con error (CU-04), mas los del
 * corte 2: los que cambian de moneda, los que se corrigen (mismo costo pero otro IVA u otros ajustes) y las filas
 * de titulo salteadas.
 */
public record ConteosImportacion(
        int suben, int bajan, int nuevos, int sinCambio, int noEstan, int conError,
        int cambianMoneda, int corrigen, int salteadas) {

    /** Los seis contadores de antes del corte 2; los nuevos quedan en 0. */
    public ConteosImportacion(int suben, int bajan, int nuevos, int sinCambio, int noEstan, int conError) {
        this(suben, bajan, nuevos, sinCambio, noEstan, conError, 0, 0, 0);
    }
}
