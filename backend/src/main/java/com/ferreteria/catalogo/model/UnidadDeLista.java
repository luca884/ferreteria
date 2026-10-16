package com.ferreteria.catalogo.model;

/**
 * Unidad de venta que trae la lista: abreviaturas habituales de las cuatro unidades, sin distinguir mayusculas ni
 * tildes. Otro texto es un error; vacio, o sin columna, es sin dato (el alta usa UNIDAD).
 */
public final class UnidadDeLista {

    private UnidadDeLista() {
    }

    /** Valor, sin dato o error con mensaje. */
    public record ResultadoUnidad(Unidad unidad, boolean sinDato, String mensaje) {

        public boolean esValor() {
            return unidad != null;
        }

        public boolean esSinDato() {
            return sinDato;
        }

        public boolean esError() {
            return mensaje != null;
        }
    }

    /** @param celda la celda de la fila en la columna de unidad, o null si la columna no esta mapeada */
    public static ResultadoUnidad interpretar(Celda celda) {
        String clave = celda == null ? null : NormalizadorCodigo.clave(celda.texto());
        if (clave == null) {
            return new ResultadoUnidad(null, true, null);
        }
        return switch (clave) {
            case "U", "UN", "UNIDAD" -> new ResultadoUnidad(Unidad.UNIDAD, false, null);
            case "M", "MT", "METRO" -> new ResultadoUnidad(Unidad.METRO, false, null);
            case "KG", "KILO" -> new ResultadoUnidad(Unidad.KILO, false, null);
            case "L", "LT", "LITRO" -> new ResultadoUnidad(Unidad.LITRO, false, null);
            default -> new ResultadoUnidad(null, false, "Unidad no reconocida: «" + celda.texto().strip() + "»");
        };
    }
}
