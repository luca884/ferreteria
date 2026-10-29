package com.ferreteria.facturacion.arca;

import org.slf4j.Marker;
import org.slf4j.MarkerFactory;

/**
 * El marcador de los logs {@code ERROR} que Luca tiene que mirar (C4, errores del sistema): un pedido a ARCA que no debería
 * pasar nunca. El mensaje también empieza con {@value #TEXTO}, para encontrarlo en los logs de Railway aunque el patrón no
 * muestre el marcador.
 */
public final class AlertaArca {

    /** El texto del marcador, y el comienzo del mensaje. */
    public static final String TEXTO = "ALERTA ARCA";

    /** El marcador SLF4J. */
    public static final Marker MARCADOR = MarkerFactory.getMarker(TEXTO);

    private AlertaArca() {
    }
}
