package com.ferreteria.catalogo.model;

import java.math.BigInteger;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * El codigo interno que se sugiere en el alta (mejoras-ferreteros-bloque-1, S3): el ultimo cargado a mano mas uno, con
 * el mismo prefijo y los mismos ceros ({@code 002} a {@code 003}, {@code TOR-009} a {@code TOR-010}); cuando se terminan
 * los digitos suma uno ({@code 999} a {@code 1000}). Saltea los ocupados. Si el codigo no termina en numero no hay
 * sugerencia.
 */
public final class CodigoSiguiente {

    /** El largo de la columna codigo_interno. */
    public static final int LARGO_MAXIMO = 30;

    /** Tope de codigos ocupados seguidos que se saltean antes de rendirse (nunca deberia alcanzarse). */
    static final int INTENTOS = 1000;

    private static final Pattern NUMERO_FINAL = Pattern.compile("^(.*?)(\\d+)$");

    private CodigoSiguiente() {
    }

    public static Optional<String> despuesDe(String ultimo, Predicate<String> ocupado) {
        if (ultimo == null) {
            return Optional.empty();
        }
        Matcher partes = NUMERO_FINAL.matcher(ultimo.strip());
        if (!partes.matches()) {
            return Optional.empty();
        }
        String prefijo = partes.group(1);
        String digitos = partes.group(2);
        BigInteger numero = new BigInteger(digitos);
        for (int intento = 0; intento < INTENTOS; intento++) {
            numero = numero.add(BigInteger.ONE);
            String candidato = prefijo + rellenar(numero, digitos.length());
            if (candidato.length() > LARGO_MAXIMO) {
                return Optional.empty();
            }
            if (!ocupado.test(candidato)) {
                return Optional.of(candidato);
            }
        }
        return Optional.empty();
    }

    /** El numero con ceros a la izquierda hasta {@code ancho}; si tiene mas digitos, tal cual. */
    private static String rellenar(BigInteger numero, int ancho) {
        String texto = numero.toString();
        return texto.length() >= ancho ? texto : "0".repeat(ancho - texto.length()) + texto;
    }
}
