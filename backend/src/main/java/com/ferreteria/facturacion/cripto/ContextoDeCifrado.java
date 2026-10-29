package com.ferreteria.facturacion.cripto;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A qué está atado un dato cifrado: el AAD de AES-GCM (ver {@link CifradorDeClaves}). Un dato cifrado con un contexto no se
 * descifra con otro: ni en otra ferretería, ni con otro propósito, ni con la huella de otra clave pública.
 *
 * @param proposito para qué es el dato
 * @param ferreteriaId la ferretería dueña del dato
 * @param huella el SHA-256 de la clave pública que acompaña a la clave privada (su {@code SubjectPublicKeyInfo} en DER),
 *     en hexadecimal en mayúsculas, sin separadores (64 caracteres)
 */
public record ContextoDeCifrado(Proposito proposito, long ferreteriaId, String huella) {

    private static final Pattern HEXA_DE_64 = Pattern.compile("[0-9A-F]{64}");

    public ContextoDeCifrado {
        Objects.requireNonNull(proposito, "Falta el propósito del dato cifrado.");
        if (huella == null || !HEXA_DE_64.matcher(huella).matches()) {
            throw new IllegalArgumentException("La huella tiene que ser el SHA-256 en hexadecimal en mayúsculas.");
        }
    }
}
