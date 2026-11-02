package com.ferreteria.clientes.model;

import java.util.Set;

/**
 * Valida y normaliza el documento de un cliente (CL-002). Se guarda solo con dígitos: lo que se tipea puede traer puntos,
 * guiones o espacios. El DNI tiene 7 u 8 dígitos. El CUIT y el CUIL tienen 11: un prefijo de persona o de empresa y un
 * dígito verificador módulo 11 con los pesos de ARCA. Cuando la cuenta da 10 no existe dígito válido (ARCA reasigna el
 * prefijo), así que ninguno se acepta.
 */
public final class DocumentoCliente {

    private static final int[] PESOS = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};
    private static final Set<String> PREFIJOS = Set.of("20", "23", "24", "27", "30", "33", "34");

    private DocumentoCliente() {}

    /** Los dígitos del documento, o {@link DocumentoInvalidoException} con el motivo para el usuario. */
    public static String normalizar(TipoDocumento tipo, String texto) {
        String digitos = texto == null ? "" : texto.replaceAll("[\\s.\\-]", "");
        return switch (tipo) {
            case DNI -> dni(digitos);
            case CUIT, CUIL -> cuit(tipo.name(), digitos);
        };
    }

    private static String dni(String digitos) {
        if (!digitos.matches("\\d{7,8}") || digitos.matches("0+")) {
            throw new DocumentoInvalidoException("El DNI tiene que tener 7 u 8 dígitos.");
        }
        return digitos;
    }

    private static String cuit(String nombre, String digitos) {
        if (!digitos.matches("\\d{11}")) {
            throw new DocumentoInvalidoException("El " + nombre + " tiene que tener 11 dígitos.");
        }
        if (!PREFIJOS.contains(digitos.substring(0, 2))) {
            throw new DocumentoInvalidoException("El " + nombre + " no es válido: revisá los dos primeros dígitos.");
        }
        if (digitoVerificador(digitos) != digitos.charAt(10) - '0') {
            throw new DocumentoInvalidoException("El " + nombre + " no es válido: revisá los números, el último no coincide.");
        }
        return digitos;
    }

    /** El verificador esperado de los primeros 10 dígitos, o -1 si la cuenta da 10 (no hay verificador válido). */
    private static int digitoVerificador(String digitos) {
        int suma = 0;
        for (int i = 0; i < PESOS.length; i++) {
            suma += (digitos.charAt(i) - '0') * PESOS[i];
        }
        int verificador = 11 - suma % 11;
        return switch (verificador) {
            case 11 -> 0;
            case 10 -> -1;
            default -> verificador;
        };
    }
}
