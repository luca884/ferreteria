package com.ferreteria.catalogo.model;

/**
 * CUIT de un proveedor. Se escribe con o sin guiones (tambien con espacios o puntos) y se guarda como
 * 30-12345678-1. El ultimo digito es el verificador: 11 menos el resto de dividir por 11 la suma de los
 * diez primeros por los pesos 5,4,3,2,7,6,5,4,3,2 (si da 11 es 0; si da 10 el CUIT no existe). Dominio puro.
 */
public final class Cuit {

    private static final int[] PESOS = {5, 4, 3, 2, 7, 6, 5, 4, 3, 2};

    private Cuit() {
    }

    /** El CUIT con guiones, o null si viene vacio. Lanza IllegalArgumentException con el mensaje al usuario. */
    public static String normalizar(String escrito) {
        if (escrito == null || escrito.isBlank()) {
            return null;
        }
        String digitos = escrito.replaceAll("[\\s.\\-]", "");
        if (!digitos.matches("\\d{11}")) {
            throw new IllegalArgumentException("El CUIT tiene que tener 11 números, por ejemplo 30-12345678-1.");
        }
        int suma = 0;
        for (int i = 0; i < PESOS.length; i++) {
            suma += (digitos.charAt(i) - '0') * PESOS[i];
        }
        int verificador = 11 - suma % 11;
        if (verificador == 11) {
            verificador = 0;
        }
        if (verificador == 10 || verificador != digitos.charAt(10) - '0') {
            throw new IllegalArgumentException("El CUIT no es válido: revisá los números, el último no coincide.");
        }
        return digitos.substring(0, 2) + "-" + digitos.substring(2, 10) + "-" + digitos.substring(10);
    }
}
