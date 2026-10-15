package com.ferreteria.ventas.model;

import java.math.BigDecimal;

/**
 * Topes que impone el esquema (V4): cantidad en DECIMAL(15,3), importes en DECIMAL(15,2). Un valor mayor
 * termina en un error de la base; se corta antes con un 400 que lo explica.
 */
public final class LimitesVenta {

    /** Para las anotaciones de Bean Validation (tienen que ser constantes de texto). */
    public static final String CANTIDAD_MAXIMA_TEXTO = "999999999999.999";
    public static final String IMPORTE_MAXIMO_TEXTO = "9999999999999.99";

    public static final BigDecimal CANTIDAD_MAXIMA = new BigDecimal(CANTIDAD_MAXIMA_TEXTO);
    public static final BigDecimal IMPORTE_MAXIMO = new BigDecimal(IMPORTE_MAXIMO_TEXTO);

    private LimitesVenta() {}
}
