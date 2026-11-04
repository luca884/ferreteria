package com.ferreteria.cotizacion.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Montos y porcentajes para los avisos al Dueno: punto de miles y coma decimal ("$ 1.234,56"). */
public final class FormatoPesos {

    private FormatoPesos() {
    }

    /** "$ 1.234,56". */
    public static String de(BigDecimal valor) {
        return "$ " + numero(valor, "#,##0.00");
    }

    /** El numero con el patron de DecimalFormat dado. DecimalFormat no es thread-safe: uno por llamada. */
    public static String numero(BigDecimal valor, String patron) {
        DecimalFormatSymbols simbolos = new DecimalFormatSymbols(Locale.ROOT);
        simbolos.setDecimalSeparator(',');
        simbolos.setGroupingSeparator('.');
        DecimalFormat formato = new DecimalFormat(patron, simbolos);
        formato.setRoundingMode(RoundingMode.HALF_UP);
        return formato.format(valor);
    }
}
