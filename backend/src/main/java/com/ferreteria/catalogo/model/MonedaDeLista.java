package com.ferreteria.catalogo.model;

/**
 * Moneda de una fila de la lista. Con la columna mapeada, ARG y ARS son pesos y USD dolares, sin distinguir
 * mayusculas, tildes ni espacios; otro texto es un error y la celda vacia tambien (no se asume pesos, decision 29).
 * Con moneda fija vale esa en todas las filas. Sin ninguna de las dos no hay dato: el nuevo entra en pesos y el
 * existente conserva la suya.
 */
public final class MonedaDeLista {

    private static final String FALTA = "Falta la moneda";
    private static final String NO_RECONOCIDA = "Moneda no reconocida";

    private MonedaDeLista() {
    }

    /** Valor, sin dato o error con mensaje. */
    public record ResultadoMoneda(Moneda moneda, boolean sinDato, String mensaje) {

        static ResultadoMoneda valor(Moneda moneda) {
            return new ResultadoMoneda(moneda, false, null);
        }

        static ResultadoMoneda ausente() {
            return new ResultadoMoneda(null, true, null);
        }

        static ResultadoMoneda error(String mensaje) {
            return new ResultadoMoneda(null, false, mensaje);
        }

        public boolean esValor() {
            return moneda != null;
        }

        public boolean esSinDato() {
            return sinDato;
        }

        public boolean esError() {
            return mensaje != null;
        }
    }

    /**
     * @param celda          la celda de la fila en la columna de moneda; se ignora si la columna no esta mapeada
     * @param columnaMapeada si el mapeo tiene columna de moneda
     * @param monedaFija     la moneda fija del mapeo, o null
     */
    public static ResultadoMoneda interpretar(Celda celda, boolean columnaMapeada, Moneda monedaFija) {
        if (monedaFija != null) {
            return ResultadoMoneda.valor(monedaFija);
        }
        if (!columnaMapeada) {
            return ResultadoMoneda.ausente();
        }
        String clave = celda == null ? null : NormalizadorCodigo.clave(celda.texto());
        if (clave == null) {
            return ResultadoMoneda.error(FALTA);
        }
        return switch (clave) {
            case "ARG", "ARS" -> ResultadoMoneda.valor(Moneda.ARS);
            case "USD" -> ResultadoMoneda.valor(Moneda.USD);
            default -> ResultadoMoneda.error(NO_RECONOCIDA + ": «" + celda.texto().strip() + "»");
        };
    }
}
