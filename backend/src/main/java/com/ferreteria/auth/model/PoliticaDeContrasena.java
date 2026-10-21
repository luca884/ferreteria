package com.ferreteria.auth.model;

import com.ferreteria.common.exception.DatosInvalidosException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Largo de la contraseña: mínimo 8 caracteres y máximo 72 bytes UTF-8, el límite de bcrypt. Pasado ese límite el encoder
 * lanza IllegalArgumentException (un 500), así que se corta antes con un 400 en el campo que corresponda.
 */
public final class PoliticaDeContrasena {

    public static final int MIN = 8;
    public static final int MAX = 72;

    static final String MENSAJE = "La contraseña debe tener entre 8 y 72 caracteres.";
    static final String MENSAJE_BYTES = "La contraseña debe tener entre 8 y 72 caracteres (la ñ y las letras con tilde cuentan doble).";

    private PoliticaDeContrasena() {
    }

    /** @throws DatosInvalidosException con el mensaje en {@code campo} si no cumple. */
    public static void validar(String campo, String password) {
        if (password == null || password.length() < MIN || password.length() > MAX) {
            throw new DatosInvalidosException(Map.of(campo, MENSAJE));
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX) {
            throw new DatosInvalidosException(Map.of(campo, MENSAJE_BYTES));
        }
    }
}
