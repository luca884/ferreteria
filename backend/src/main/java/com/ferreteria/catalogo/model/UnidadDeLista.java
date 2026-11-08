package com.ferreteria.catalogo.model;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Unidad de venta que trae la lista: abreviaturas habituales de las cuatro unidades (con punto final o en plural:
 * «Kg.», «KGS», «Kilos»), sin distinguir mayusculas ni tildes. Otro texto es un error; vacio, o sin columna, es sin
 * dato (el alta toma la unidad de la descripcion, o UNIDAD).
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
        Unidad unidad = deAbreviatura(PUNTOS_FINALES.matcher(clave).replaceAll(""));
        return unidad != null
            ? new ResultadoUnidad(unidad, false, null)
            : new ResultadoUnidad(null, false, "Unidad no reconocida: «" + celda.texto().strip() + "»");
    }

    /**
     * La unidad de venta que dice la descripcion, para la fila que no la trae en la columna: «x kg», «por kilo»,
     * «x mt», «x metro», «x lt», «por litro». Solo cuenta «x» o «por» seguidos de la unidad, sin numero en el medio:
     * «x100m», «20m» o «4L» son la medida de una pieza (un rollo, una lata), no la unidad de venta. Las letras
     * sueltas «m» y «l» no cuentan.
     *
     * @return la unidad, o null si la descripcion no la dice
     */
    public static Unidad deDescripcion(String descripcion) {
        String clave = NormalizadorCodigo.clave(descripcion);
        if (clave == null) {
            return null;
        }
        Matcher coincidencia = UNIDAD_EN_DESCRIPCION.matcher(clave);
        return coincidencia.find() ? deAbreviatura(coincidencia.group(1)) : null;
    }

    private static final Pattern PUNTOS_FINALES = Pattern.compile("\\.+$");

    private static final Pattern UNIDAD_EN_DESCRIPCION = Pattern.compile(
        "(?:^|[\\s(])(?:X|POR)\\s*(KGS?|KILOS?|KILOGRAMOS?|MTS?|METROS?|LTS?|LITROS?)\\.?(?=$|[\\s).,;:/-])");

    private static Unidad deAbreviatura(String clave) {
        return switch (clave) {
            case "U", "UN", "UND", "UNID", "UNIDAD", "UNIDADES" -> Unidad.UNIDAD;
            case "M", "MT", "MTS", "METRO", "METROS" -> Unidad.METRO;
            case "KG", "KGS", "KILO", "KILOS", "KILOGRAMO", "KILOGRAMOS" -> Unidad.KILO;
            case "L", "LT", "LTS", "LITRO", "LITROS" -> Unidad.LITRO;
            default -> null;
        };
    }
}
