package com.ferreteria.cotizacion.model;

import java.util.List;

/**
 * Puerto de una fuente externa de cotizaciones (dolarapi.com, Bluelytics...). El orquestador prueba las fuentes en
 * orden; cada una se prueba por separado.
 */
public interface FuenteDeCotizaciones {

    /** Como se guarda en la tabla: por ejemplo DOLARAPI o BLUELYTICS. */
    String nombre();

    /**
     * Lee los tipos que esta fuente conoce. Si la fuente no responde bien (error de red, 5xx, timeout, cuerpo vacio o
     * JSON invalido) lanza {@link FuenteNoDisponibleException} para que el orquestador pase al respaldo.
     */
    List<CotizacionLeida> leer();
}
