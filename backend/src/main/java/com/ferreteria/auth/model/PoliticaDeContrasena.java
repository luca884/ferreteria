package com.ferreteria.auth.model;

import com.ferreteria.common.exception.DatosInvalidosException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Largo de la contraseña: mínimo 8 caracteres, máximo 72 bytes UTF-8 (el límite de bcrypt) y no solo espacios. Pasado ese límite el encoder
 * lanza IllegalArgumentException (un 500), así que se corta antes con un 400 en el campo que corresponda. Tampoco se acepta una
 * contraseña de la lista de comunes, sin distinguir mayúsculas ni los espacios de los costados.
 */
public final class PoliticaDeContrasena {

    public static final int MIN = 8;
    public static final int MAX = 72;

    static final String MENSAJE = "La contraseña debe tener entre 8 y 72 caracteres.";
    static final String MENSAJE_BYTES = "La contraseña debe tener entre 8 y 72 caracteres (la ñ y las letras con tilde cuentan doble).";

    static final String MENSAJE_EN_BLANCO = "La contraseña no puede ser solo espacios.";

    static final String MENSAJE_COMUN = "Esa contraseña es muy común. Elegí otra más difícil de adivinar.";

    /**
     * Repetir la contraseña que ya se tiene no rota nada. Lo usan los dos endpoints que cambian una contraseña
     * (AuthService para el cambio propio con la marca puesta y UsuarioService para el reseteo propio), cada uno con la
     * clave del campo de su DTO; el texto sale de acá para que no derive entre los dos.
     */
    public static final String MISMA_CONTRASENA = "Elegí una contraseña distinta de la que te dieron.";

    /** Contraseñas comunes (S1c): se rechazan al crear o cambiar una; las ya guardadas siguen sirviendo para entrar. */
    static final String LISTA_COMUNES = "/seguridad/contrasenas-comunes.txt";

    private PoliticaDeContrasena() {
    }

    /** @throws DatosInvalidosException con el mensaje en {@code campo} si no cumple. */
    public static void validar(String campo, String password) {
        if (password == null || password.length() < MIN || password.length() > MAX) {
            throw new DatosInvalidosException(Map.of(campo, MENSAJE));
        }
        if (password.isBlank()) {
            throw new DatosInvalidosException(Map.of(campo, MENSAJE_EN_BLANCO));
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX) {
            throw new DatosInvalidosException(Map.of(campo, MENSAJE_BYTES));
        }
        if (Comunes.LISTA.contains(password.strip().toLowerCase(Locale.ROOT))) {
            throw new DatosInvalidosException(Map.of(campo, MENSAJE_COMUN));
        }
    }

    /** La lista se lee una sola vez, la primera vez que se valida una contraseña. */
    private static final class Comunes {
        private static final Set<String> LISTA = leer();

        private static Set<String> leer() {
            try (InputStream in = PoliticaDeContrasena.class.getResourceAsStream(LISTA_COMUNES)) {
                if (in == null) {
                    throw new IllegalStateException("Falta la lista de contraseñas comunes: " + LISTA_COMUNES);
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                        .filter(linea -> !linea.isBlank() && !linea.startsWith("#"))
                        .map(linea -> linea.strip().toLowerCase(Locale.ROOT))
                        .collect(Collectors.toUnmodifiableSet());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }
}
