package com.ferreteria.catalogo.model;

import java.math.BigDecimal;

/**
 * Hasta tres bonificaciones en cascada del proveedor, en porcentaje. Null cuenta como 0. El DTO
 * valida primero; el rango 0..100 del constructor es una guarda del dominio.
 */
public record Bonificaciones(BigDecimal b1, BigDecimal b2, BigDecimal b3) {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    public static final Bonificaciones NINGUNA = new Bonificaciones(null, null, null);

    public Bonificaciones {
        validar(b1);
        validar(b2);
        validar(b3);
    }

    /**
     * Producto exacto de (100 - bi): el costo es precio x numerador / (100 x 100 x 100), asi la
     * cascada no agrega ningun paso de redondeo.
     */
    public BigDecimal numerador() {
        return resto(b1).multiply(resto(b2)).multiply(resto(b3));
    }

    private static BigDecimal resto(BigDecimal bonificacion) {
        return bonificacion == null ? CIEN : CIEN.subtract(bonificacion);
    }

    private static void validar(BigDecimal bonificacion) {
        if (bonificacion != null && (bonificacion.signum() < 0 || bonificacion.compareTo(CIEN) > 0)) {
            throw new IllegalArgumentException("La bonificación debe estar entre 0 y 100");
        }
    }
}
