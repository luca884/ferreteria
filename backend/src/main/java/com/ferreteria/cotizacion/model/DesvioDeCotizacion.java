package com.ferreteria.cotizacion.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Cuanto se aleja un dolar nuevo del vigente de la ferreteria (auditoria H09). Si una fuente devuelve un valor absurdo
 * (por ejemplo 14,5 en vez de 1.450), recalcular con el abarataria 100 veces todos los articulos en USD. Por eso un
 * dolar que se aleja MAS de {@link #UMBRAL_PORCENTAJE} % del vigente no se aplica solo: el Dueno lo tiene que
 * confirmar. Exactamente el umbral se acepta.
 */
public final class DesvioDeCotizacion {

    /** Por encima de este porcentaje de diferencia contra el dolar vigente, el Dueno tiene que confirmar el nuevo. */
    public static final BigDecimal UMBRAL_PORCENTAJE = new BigDecimal("20");

    private static final BigDecimal CIEN = new BigDecimal("100");

    private DesvioDeCotizacion() {
    }

    /** Diferencia porcentual sin signo de {@code nuevo} contra {@code anterior}, con 2 decimales (anterior mayor a 0). */
    public static BigDecimal porcentaje(BigDecimal anterior, BigDecimal nuevo) {
        return nuevo.subtract(anterior).abs().multiply(CIEN).divide(anterior, 2, RoundingMode.HALF_UP);
    }

    /** True si el nuevo se aleja mas del umbral; false sin anterior (el primer dolar se acepta solo). Cuenta exacta. */
    public static boolean hayQueConfirmar(BigDecimal anterior, BigDecimal nuevo) {
        if (anterior == null || anterior.signum() <= 0 || nuevo == null) {
            return false;
        }
        return nuevo.subtract(anterior).abs().multiply(CIEN).compareTo(anterior.multiply(UMBRAL_PORCENTAJE)) > 0;
    }
}
