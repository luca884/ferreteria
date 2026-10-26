package com.ferreteria.catalogo.model;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Interpreta importes con formato argentino. Devuelve el valor exacto, sin redondear: el unico
 * redondeo es el del costo final ({@link CostoDeLista}). El signo de pesos se ignora, y el de dolares
 * (US$ o U$S) tambien cuando va adelante: la moneda sale de la columna de moneda o del mapeo.
 */
public final class ParserImporte {

    private static final Pattern IGNORADOS = Pattern.compile("[\\s\\u00A0\\u202F$]");
    private static final Pattern DOLARES_ADELANTE = Pattern.compile("^\\s*(US\\$|U\\$S)", Pattern.CASE_INSENSITIVE);
    /** El primer grupo no empieza con 0: "0.250" es decimal (0,25), no doscientos cincuenta. */
    private static final Pattern MILES_CON_PUNTO = Pattern.compile("^[+-]?[1-9]\\d{0,2}(\\.\\d{3})+$");
    private static final Pattern NUMERO = Pattern.compile("^[+-]?(\\d+(\\.\\d*)?|\\.\\d+)$");

    private ParserImporte() {
    }

    public static Optional<BigDecimal> parsear(Celda celda) {
        if (celda.numero() != null) {
            return Optional.of(celda.numero());
        }
        String sinDolares = DOLARES_ADELANTE.matcher(celda.texto()).replaceFirst("");
        String limpio = IGNORADOS.matcher(sinDolares).replaceAll("");
        if (limpio.isEmpty()) {
            return Optional.empty();
        }
        String normalizado = normalizar(limpio);
        return NUMERO.matcher(normalizado).matches() ? Optional.of(new BigDecimal(normalizado)) : Optional.empty();
    }

    /** Deja el texto con punto decimal y sin separadores de miles. */
    private static String normalizar(String texto) {
        int ultimaComa = texto.lastIndexOf(',');
        int ultimoPunto = texto.lastIndexOf('.');
        if (ultimaComa >= 0 && ultimoPunto >= 0) {
            // El ultimo separador es el decimal; el otro es de miles.
            return ultimaComa > ultimoPunto
                ? texto.replace(".", "").replace(',', '.')
                : texto.replace(",", "");
        }
        if (ultimaComa >= 0) {
            boolean variasComas = texto.indexOf(',') != ultimaComa;
            return variasComas ? texto.replace(",", "") : texto.replace(',', '.');
        }
        return MILES_CON_PUNTO.matcher(texto).matches() ? texto.replace(".", "") : texto;
    }
}
