package com.ferreteria.catalogo.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/**
 * Importes en formato argentino (punto de miles y coma decimal) para los mensajes al usuario. Dominio puro,
 * sin estado. Mismo formato que {@link VariacionFuerte}, que no se toca para no mover sus tests.
 */
public final class ImporteArgentino {

    private ImporteArgentino() {
    }

    /** «$ 1.234,50» o «US$ 1.234,50», siempre con 2 decimales; un negativo lleva el signo delante: «-$ 5,00». */
    public static String formato(BigDecimal valor, Moneda moneda) {
        String simbolo = moneda == Moneda.USD ? "US$" : "$";
        String signo = valor.signum() < 0 ? "-" : "";
        return signo + simbolo + " " + formatear(valor.abs(), "#,##0.00");
    }

    /** Como {@link #formato} pero sin decimales que no significan nada: «$ 100», «US$ 100,50», «-US$ 50». */
    public static String formatoCompacto(BigDecimal valor, Moneda moneda) {
        String simbolo = moneda == Moneda.USD ? "US$" : "$";
        String signo = valor.signum() < 0 ? "-" : "";
        return signo + simbolo + " " + montoCompacto(valor.abs());
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
