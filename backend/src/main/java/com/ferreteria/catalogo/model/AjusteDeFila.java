package com.ferreteria.catalogo.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Descuento o recargo de una fila (hasta tres columnas, por posicion). Negativo es descuento y positivo recargo.
 * El rango es el del masivo, de -99,99 a 1000, para que ningun ajuste deje el costo en cero o negativo. El valor
 * queda a 2 decimales, como la columna, asi lo calculado es lo guardado. Sin columna mapeada el ajuste del
 * articulo se conserva; con la columna mapeada y la celda vacia se borra (decision 27).
 */
public final class AjusteDeFila {

    private AjusteDeFila() {
    }

    /** Lo que hay que hacer con un ajuste del articulo. */
    public enum Estado { CONSERVAR, BORRAR, VALOR, ERROR }

    public record ResultadoAjuste(Estado estado, BigDecimal valor, String mensaje) {

        public boolean esConservar() {
            return estado == Estado.CONSERVAR;
        }

        public boolean esBorrar() {
            return estado == Estado.BORRAR;
        }

        public boolean esValor() {
            return estado == Estado.VALOR;
        }

        public boolean esError() {
            return estado == Estado.ERROR;
        }
    }

    /**
     * @param posicion 1, 2 o 3: el numero de la columna de ajuste, para el mensaje
     * @param celda    la celda de la fila en esa columna, o null si la columna no esta mapeada
     */
    public static ResultadoAjuste interpretar(int posicion, Celda celda) {
        if (celda == null) {
            return new ResultadoAjuste(Estado.CONSERVAR, null, null);
        }
        ParserPorcentaje.Resultado porcentaje = ParserPorcentaje.parsear(celda);
        if (porcentaje.esVacio()) {
            return new ResultadoAjuste(Estado.BORRAR, null, null);
        }
        if (porcentaje.esInvalido()) {
            return error("El descuento o recargo " + posicion + " no es un número.");
        }
        BigDecimal valor = porcentaje.valor().setScale(2, RoundingMode.HALF_UP);
        if (valor.compareTo(Ajustes.MINIMO) < 0 || valor.compareTo(Ajustes.MAXIMO) > 0) {
            return error("El descuento o recargo " + posicion + " tiene que estar entre -99,99 y 1000.");
        }
        return new ResultadoAjuste(Estado.VALOR, valor, null);
    }

    private static ResultadoAjuste error(String mensaje) {
        return new ResultadoAjuste(Estado.ERROR, null, mensaje);
    }
}
