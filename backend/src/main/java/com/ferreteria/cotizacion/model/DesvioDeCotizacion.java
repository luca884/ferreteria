package com.ferreteria.cotizacion.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Cuanto se aleja un dolar nuevo del vigente de la ferreteria (auditoria H09). Si una fuente devuelve un valor absurdo
 * (por ejemplo 14,5 en vez de 1.450), recalcular con el abarataria 100 veces todos los articulos en USD. Por eso un
 * dolar que se aleja MAS del umbral de la ferreteria del vigente no se aplica solo: el Dueno lo tiene que confirmar.
 * Exactamente el umbral se acepta. El umbral lo elige el Dueno, entre {@link #UMBRAL_MINIMO} y {@link #UMBRAL_MAXIMO}.
 */
public final class DesvioDeCotizacion {

    /** Umbral de una ferreteria que no eligio otro: por encima de este porcentaje, el Dueno tiene que confirmar. */
    public static final BigDecimal UMBRAL_POR_DEFECTO = new BigDecimal("5.00");
    public static final BigDecimal UMBRAL_MINIMO = BigDecimal.ONE;
    public static final BigDecimal UMBRAL_MAXIMO = new BigDecimal("50");

    private static final BigDecimal CIEN = new BigDecimal("100");

    private DesvioDeCotizacion() {
    }

    /** Diferencia porcentual sin signo de {@code nuevo} contra {@code anterior}, con 2 decimales (anterior mayor a 0). */
    public static BigDecimal porcentaje(BigDecimal anterior, BigDecimal nuevo) {
        return nuevo.subtract(anterior).abs().multiply(CIEN).divide(anterior, 2, RoundingMode.HALF_UP);
    }

    /**
     * True si el nuevo se aleja mas de {@code umbral} % del anterior; false sin anterior (el primer dolar se acepta
     * solo). Cuenta exacta.
     */
    public static boolean hayQueConfirmar(BigDecimal anterior, BigDecimal nuevo, BigDecimal umbral) {
        if (anterior == null || anterior.signum() <= 0 || nuevo == null) {
            return false;
        }
        return nuevo.subtract(anterior).abs().multiply(CIEN).compareTo(anterior.multiply(umbral)) > 0;
    }

    /** True si {@code umbral} esta entre el minimo y el maximo, los dos incluidos, con hasta 2 decimales. */
    public static boolean umbralValido(BigDecimal umbral) {
        return umbral != null
                && umbral.compareTo(UMBRAL_MINIMO) >= 0
                && umbral.compareTo(UMBRAL_MAXIMO) <= 0
                && umbral.stripTrailingZeros().scale() <= 2;
    }
}
