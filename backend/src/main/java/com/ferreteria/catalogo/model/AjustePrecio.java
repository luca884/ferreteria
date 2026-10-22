package com.ferreteria.catalogo.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Un ajuste sobre el precio final del articulo (CU-14), positivo o negativo: un porcentaje del precio calculado o un
 * monto en pesos. No es lo mismo que {@link Ajustes}, que son descuentos o recargos sobre el costo de la lista.
 * Quien recibe el pedido valida primero; el constructor es una guarda del dominio. El valor queda a escala 2 para
 * que el JSON que vuelve de la base se compare por igualdad.
 */
public record AjustePrecio(String nombre, TipoAjustePrecio tipo, BigDecimal valor) {

    public static final int NOMBRE_MAXIMO = 40;
    /** Mismo rango que los ajustes sobre el costo: de -99,99 a 1000. */
    public static final BigDecimal PORCENTAJE_MINIMO = Ajustes.MINIMO;
    public static final BigDecimal PORCENTAJE_MAXIMO = Ajustes.MAXIMO;

    public AjustePrecio {
        nombre = nombre == null || nombre.isBlank() ? null : nombre.strip();
        if (nombre != null && nombre.length() > NOMBRE_MAXIMO) {
            throw new IllegalArgumentException("El nombre del ajuste tiene hasta 40 caracteres.");
        }
        if (tipo == null) {
            throw new IllegalArgumentException("El tipo del ajuste es obligatorio.");
        }
        if (valor == null) {
            throw new IllegalArgumentException("El valor del ajuste es obligatorio.");
        }
        valor = valor.setScale(2, RoundingMode.HALF_UP);
        if (tipo == TipoAjustePrecio.PORCENTAJE
                && (valor.compareTo(PORCENTAJE_MINIMO) < 0 || valor.compareTo(PORCENTAJE_MAXIMO) > 0)) {
            throw new IllegalArgumentException("El porcentaje del ajuste debe estar entre -99,99 y 1000.");
        }
    }
}
