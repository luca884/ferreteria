package com.ferreteria.catalogo.model;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Interpreta porcentajes de la lista (IVA, descuentos y recargos): con signo, con coma o punto decimal y con el
 * simbolo %. Delega en {@link ParserImporte}, que no saca el %. Una celda numerica con formato de porcentaje de
 * Excel se toma como se ve: 0,21 con formato % es 21. Devuelve el valor exacto, sin punto flotante.
 */
public final class ParserPorcentaje {

    private static final char MENOS_TIPOGRAFICO = '−';
    private static final char RAYA_CORTA = '–';
    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    private ParserPorcentaje() {
    }

    /** El resultado de leer una celda: un valor, vacia o invalida. Vacia e invalida no se confunden. */
    public record Resultado(Estado estado, BigDecimal valor) {

        public enum Estado { VALOR, VACIO, INVALIDO }

        public boolean esValor() {
            return estado == Estado.VALOR;
        }

        public boolean esVacio() {
            return estado == Estado.VACIO;
        }

        public boolean esInvalido() {
            return estado == Estado.INVALIDO;
        }
    }

    public static Resultado parsear(Celda celda) {
        if (celda == null || celda.vacia()) {
            return new Resultado(Resultado.Estado.VACIO, null);
        }
        if (celda.numero() != null) {
            BigDecimal valor = celda.porcentaje() ? celda.numero().multiply(CIEN) : celda.numero();
            return new Resultado(Resultado.Estado.VALOR, valor);
        }
        String limpio = celda.texto().replace('%', ' ')
            .replace(MENOS_TIPOGRAFICO, '-').replace(RAYA_CORTA, '-');
        Optional<BigDecimal> valor = ParserImporte.parsear(new Celda(limpio, null));
        return valor.map(v -> new Resultado(Resultado.Estado.VALOR, v))
            .orElseGet(() -> new Resultado(Resultado.Estado.INVALIDO, null));
    }
}
