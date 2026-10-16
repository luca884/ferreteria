package com.ferreteria.catalogo.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Hasta tres descuentos o recargos por articulo, en porcentaje: negativo es descuento y positivo es recargo.
 * Se encadenan despues de las bonificaciones del proveedor (+3 y +3 dan 1,03 x 1,03, no 6 %). Null cuenta como 0.
 * Quien interpreta la lista valida primero ({@link AjusteDeFila}); el rango del constructor es una guarda del dominio.
 * Los valores quedan a escala 2, como la columna DECIMAL(6,2): asi -5 y -5,00 son el mismo {@code Ajustes} y los
 * que lee la base se comparan por igualdad.
 */
public record Ajustes(BigDecimal a1, BigDecimal a2, BigDecimal a3) {

    /** Mismo rango que la actualizacion masiva y que el CHECK de la base: de -99,99 a 1000. */
    public static final BigDecimal MINIMO = new BigDecimal("-99.99");
    public static final BigDecimal MAXIMO = new BigDecimal("1000");

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    public static final Ajustes NINGUNO = new Ajustes(null, null, null);

    public Ajustes {
        a1 = normalizar(a1);
        a2 = normalizar(a2);
        a3 = normalizar(a3);
    }

    /**
     * Producto exacto de (100 + ai): el costo es precio x numerador / 100^3, asi la cadena no agrega ningun paso
     * de redondeo. Sin ajustes vale 100 x 100 x 100.
     */
    public BigDecimal numerador() {
        return mas(a1).multiply(mas(a2)).multiply(mas(a3));
    }

    private static BigDecimal mas(BigDecimal ajuste) {
        return ajuste == null ? CIEN : CIEN.add(ajuste);
    }

    private static BigDecimal normalizar(BigDecimal ajuste) {
        if (ajuste == null) {
            return null;
        }
        BigDecimal aDosDecimales = ajuste.setScale(2, RoundingMode.HALF_UP);
        if (aDosDecimales.compareTo(MINIMO) < 0 || aDosDecimales.compareTo(MAXIMO) > 0) {
            throw new IllegalArgumentException("El descuento o recargo debe estar entre -99,99 y 1000");
        }
        return aDosDecimales;
    }
}
