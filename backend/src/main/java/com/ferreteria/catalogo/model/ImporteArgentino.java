package com.ferreteria.catalogo.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Importes en formato argentino (punto de miles y coma decimal) para los mensajes al usuario. Dominio puro,
 * sin estado. Mismo formato que {@link VariacionFuerte}, que no se toca para no mover sus tests.
 *
 * <p>Los espacios de adentro del importe (entre la moneda y el numero, y entre el numero y su unidad) son duros
 * (U+00A0), como los del front: en una celda angosta el aviso corta antes o despues del importe, nunca en el medio.
 */
public final class ImporteArgentino {

    /** Espacio duro (U+00A0): une las partes del importe para que no queden en dos renglones. */
    private static final String ESPACIO_DURO = "\u00a0";

    private ImporteArgentino() {
    }

    /** «$ 1.234,50» o «US$ 1.234,50», siempre con 2 decimales; un negativo lleva el signo delante: «-$ 5,00». */
    public static String formato(BigDecimal valor, Moneda moneda) {
        String simbolo = moneda == Moneda.USD ? "US$" : "$";
        String signo = valor.signum() < 0 ? "-" : "";
        return signo + simbolo + ESPACIO_DURO + formatear(valor.abs(), "#,##0.00");
    }

    /**
     * Como {@link #formato} y, si el articulo no va por unidad, por que unidad es el importe: «$ 769,50 / m»,
     * «US$ 10,00 / kg»; por unidad, sin sufijo. Los mismos textos que la grilla del front.
     */
    public static String formato(BigDecimal valor, Moneda moneda, Unidad unidad) {
        String abreviatura = unidad.abreviatura();
        String porUnidad = abreviatura.isEmpty() ? "" : ESPACIO_DURO + "/" + ESPACIO_DURO + abreviatura;
        return formato(valor, moneda) + porUnidad;
    }

    /** Como {@link #formato} pero sin decimales que no significan nada: «$ 100», «US$ 100,50», «-US$ 50». */
    public static String formatoCompacto(BigDecimal valor, Moneda moneda) {
        String simbolo = moneda == Moneda.USD ? "US$" : "$";
        String signo = valor.signum() < 0 ? "-" : "";
        return signo + simbolo + ESPACIO_DURO + montoCompacto(valor.abs());
    }

    /** El monto sin simbolo y sin decimales que no significan nada: 100 es «100» y 100,50 es «100,50». */
    public static String montoCompacto(BigDecimal valor) {
        BigDecimal aDosDecimales = valor.setScale(2, RoundingMode.HALF_UP);
        String patron = aDosDecimales.stripTrailingZeros().scale() <= 0 ? "#,##0" : "#,##0.00";
        return formatear(aDosDecimales, patron);
    }

    /** DecimalFormat no es thread-safe: uno por llamada. */
    private static String formatear(BigDecimal valor, String patron) {
        DecimalFormatSymbols simbolos = new DecimalFormatSymbols(Locale.ROOT);
        simbolos.setDecimalSeparator(',');
        simbolos.setGroupingSeparator('.');
        DecimalFormat formato = new DecimalFormat(patron, simbolos);
        formato.setRoundingMode(RoundingMode.HALF_UP);
        return formato.format(valor);
    }
}
