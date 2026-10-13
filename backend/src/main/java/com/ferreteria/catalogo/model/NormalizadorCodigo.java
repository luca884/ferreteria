package com.ferreteria.catalogo.model;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Clave de coincidencia de codigos de proveedor: trim, mayusculas y sin acentos, para coincidir con
 * la colacion utf8mb4_0900_ai_ci. Solo sirve para comparar; el valor guardado es el original.
 */
public final class NormalizadorCodigo {

    private static final Pattern MARCAS = Pattern.compile("\\p{M}+");

    private NormalizadorCodigo() {
    }

    /** La clave del codigo, o null si esta en blanco. */
    public static String clave(String codigo) {
        if (codigo == null) {
            return null;
        }
        String recortado = codigo.replace(' ', ' ').replace(' ', ' ').strip();
        if (recortado.isEmpty()) {
            return null;
        }
        String sinAcentos = MARCAS.matcher(Normalizer.normalize(recortado, Normalizer.Form.NFD)).replaceAll("");
        return sinAcentos.toUpperCase(Locale.ROOT);
    }
}
