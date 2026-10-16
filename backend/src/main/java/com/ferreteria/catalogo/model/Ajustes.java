package com.ferreteria.catalogo.model;

import java.math.BigDecimal;

/**
 * Hasta tres descuentos o recargos por articulo, en porcentaje: negativo es descuento y positivo es recargo.
 * Se encadenan despues de las bonificaciones del proveedor (+3 y +3 dan 1,03 x 1,03, no 6 %). Null cuenta como 0.
 * Quien interpreta la lista valida primero ({@link AjusteDeFila}); el rango del constructor es una guarda del dominio.
 */
public record Ajustes(BigDecimal a1, BigDecimal a2, BigDecimal a3) {

    /** Mismo rango que la actualizacion masiva y que el CHECK de la base: de -99,99 a 1000. */
    public static final BigDecimal MINIMO = new BigDecimal("-99.99");
    public static final BigDecimal MAXIMO = new BigDecimal("1000");

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    public static final Ajustes NINGUNO = new Ajustes(null, null, null);

    public Ajustes {
        validar(a1);
        validar(a2);
        validar(a3);
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

    private static void validar(BigDecimal ajuste) {
        if (ajuste != null && (ajuste.compareTo(MINIMO) < 0 || ajuste.compareTo(MAXIMO) > 0)) {
            throw new IllegalArgumentException("El descuento o recargo debe estar entre -99,99 y 1000");
        }
    }
}
