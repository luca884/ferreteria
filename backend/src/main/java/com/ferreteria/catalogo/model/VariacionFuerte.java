package com.ferreteria.catalogo.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;

/**
 * Advertencia por variacion fuerte de un valor (costo o precio). La comparten la importacion de
 * listas (CU-04), la actualizacion masiva (CU-05) y las compras (CU-13). Dominio puro, sin estado.
 */
public final class VariacionFuerte {

    /** Una variacion mayor a este porcentaje (hacia arriba o abajo) genera una advertencia. */
    public static final BigDecimal UMBRAL_PORCENTAJE = new BigDecimal("50");

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    /** Que valor cambio; da el principio del mensaje. */
    public enum Sujeto {
        COSTO("El costo"),
        PRECIO("El precio"),
        /** El costo con sus recargos (CU-13): lo usa la compra cuando ella o el articulo tienen recargos. */
        COSTO_REAL("El costo real");

        private final String texto;

        Sujeto(String texto) {
            this.texto = texto;
        }
    }

    private VariacionFuerte() {
    }

    /**
     * Mensaje de advertencia si el valor varia mas que el umbral: |nuevo - anterior| x 100 > umbral x
     * anterior, comparado en forma exacta (el limite justo no advierte). Con valor anterior 0 o
     * negativo no se puede medir la variacion.
     */
    public static Optional<String> mensaje(Sujeto sujeto, BigDecimal anterior, BigDecimal nuevo) {
        return mensaje(sujeto, anterior, nuevo, VariacionFuerte::dinero);
    }

    /**
     * Igual que la de arriba, con los importes como los muestra la grilla: con su moneda («$ 769,50»,
     * «US$ 10,00») y, si el articulo no va por unidad, por que unidad son («$ 769,50 / m»). Quien la
     * llama elige la moneda: el costo va en la del articulo y el precio, siempre en pesos.
     */
    public static Optional<String> mensaje(Sujeto sujeto, BigDecimal anterior, BigDecimal nuevo, Moneda moneda,
                                           Unidad unidad) {
        String porUnidad = unidad.abreviatura().isEmpty() ? "" : " / " + unidad.abreviatura();
        return mensaje(sujeto, anterior, nuevo, valor -> ImporteArgentino.formato(valor, moneda) + porUnidad);
    }

    private static Optional<String> mensaje(Sujeto sujeto, BigDecimal anterior, BigDecimal nuevo,
                                            Function<BigDecimal, String> importe) {
        if (anterior.signum() <= 0) {
            return Optional.empty();
        }
        BigDecimal diferenciaPorCien = nuevo.subtract(anterior).abs().multiply(CIEN);
        if (diferenciaPorCien.compareTo(UMBRAL_PORCENTAJE.multiply(anterior)) <= 0) {
            return Optional.empty();
        }
        boolean sube = nuevo.compareTo(anterior) > 0;
        String porcentaje = formatoArgentino(diferenciaPorCien.divide(anterior, 2, RoundingMode.HALF_UP), "#,##0.##");
        return Optional.of(sujeto.texto + " " + (sube ? "sube" : "baja") + " un " + porcentaje + " % (de "
            + importe.apply(anterior) + " a " + importe.apply(nuevo) + "). Revisá que sea correcto.");
    }

    private static String dinero(BigDecimal valor) {
        return formatoArgentino(valor, "#,##0.00");
    }

    /** Punto de miles y coma decimal ("1.234,56"). DecimalFormat no es thread-safe: uno por llamada. */
    private static String formatoArgentino(BigDecimal valor, String patron) {
        DecimalFormatSymbols simbolos = new DecimalFormatSymbols(Locale.ROOT);
        simbolos.setDecimalSeparator(',');
        simbolos.setGroupingSeparator('.');
        DecimalFormat formato = new DecimalFormat(patron, simbolos);
        formato.setRoundingMode(RoundingMode.HALF_UP);
        return formato.format(valor);
    }
}
