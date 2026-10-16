package com.ferreteria.catalogo.model;

/**
 * Codigo de barras que trae la lista: se recorta y admite hasta 30 caracteres, como en el alta manual (el mensaje
 * es el mismo). Vacio, o sin columna, es sin dato.
 */
public final class CodigoBarrasDeLista {

    static final int LARGO_MAXIMO = 30;
    private static final String DEMASIADO_LARGO = "El código de barras no puede superar 30 caracteres";

    private CodigoBarrasDeLista() {
    }

    /** Valor, sin dato o error con mensaje. */
    public record ResultadoBarras(String codigo, boolean sinDato, String mensaje) {

        public boolean esValor() {
            return codigo != null;
        }

        public boolean esSinDato() {
            return sinDato;
        }

        public boolean esError() {
            return mensaje != null;
        }
    }

    /** @param celda la celda de la fila en la columna de barras, o null si la columna no esta mapeada */
    public static ResultadoBarras interpretar(Celda celda) {
        String texto = celda == null ? "" : celda.texto().strip();
        if (texto.isEmpty()) {
            return new ResultadoBarras(null, true, null);
        }
        if (texto.length() > LARGO_MAXIMO) {
            return new ResultadoBarras(null, false, DEMASIADO_LARGO);
        }
        return new ResultadoBarras(texto, false, null);
    }
}
