package com.ferreteria.auth.totp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Cifra el secreto del código de la app del super admin (totp-super-admin, S5e): AES-256-GCM del JDK con la clave maestra
 * {@code TOTP_CLAVE_MAESTRA} (32 bytes en Base64), propia de {@code auth} y distinta de la de ARCA. La clave vive en una
 * variable de entorno, nunca en el repo ni en la base.
 *
 * <p>Formato guardado en {@code usuario.totp_secreto}, byte a byte:
 * <pre>
 *   [0]        versión del formato: 0x01
 *   [1..12]    nonce de 12 bytes, aleatorio en cada cifrado (SecureRandom)
 *   [13..]     el secreto cifrado, con el tag de GCM de 128 bits (16 bytes) al final
 * </pre>
 * El AAD es la versión seguida del UTF-8 de {@code "totp|<id del usuario>"}: el secreto de un usuario no se descifra como
 * el de otro, y la versión no se puede cambiar sin que el tag lo note.
 *
 * <p>La clave maestra se valida al construir el bean, o sea al arrancar: sin la variable, o con algo que no es Base64 de
 * 32 bytes, la aplicación no arranca (S7). Si la clave cambia, lo guardado ya no se descifra: el super admin se resetea por
 * base (README, Deploy). Ni el log, ni los mensajes, ni {@link #toString()} muestran la clave, el dato cifrado o el secreto.
 */
@Component
public class CifradorTotp {

    private static final Logger log = LoggerFactory.getLogger(CifradorTotp.class);

    private static final byte VERSION = 1;
    private static final int LARGO_CLAVE = 32;
    private static final int LARGO_NONCE = 12;
    private static final int BITS_TAG = 128;
    private static final int LARGO_MINIMO = 1 + LARGO_NONCE + BITS_TAG / 8;
    private static final String TRANSFORMACION = "AES/GCM/NoPadding";
    private static final String NOMBRE = "TOTP_CLAVE_MAESTRA (app.auth.totp.clave-maestra)";

    private final SecureRandom azar = new SecureRandom();
    private final SecretKey clave;

    public CifradorTotp(@Value("${app.auth.totp.clave-maestra}") String claveMaestra) {
        this.clave = leer(claveMaestra);
    }

    /** Cifra el secreto del usuario con un nonce nuevo. */
    public byte[] cifrar(long usuarioId, byte[] secreto) {
        byte[] nonce = new byte[LARGO_NONCE];
        azar.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMACION);
            cipher.init(Cipher.ENCRYPT_MODE, clave, new GCMParameterSpec(BITS_TAG, nonce));
            cipher.updateAAD(aad(usuarioId));
            byte[] cifrado = cipher.doFinal(secreto);
            return ByteBuffer.allocate(1 + nonce.length + cifrado.length).put(VERSION).put(nonce).put(cifrado).array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo cifrar con AES-GCM.", e);
        }
    }

    /**
     * Descifra lo que guardó {@link #cifrar} para ese usuario.
     *
     * @throws IllegalStateException si es de otra versión, de otro usuario, de otra clave maestra o está tocado
     */
    public byte[] descifrar(long usuarioId, byte[] guardado) {
        if (guardado == null || guardado.length < LARGO_MINIMO || guardado[0] != VERSION) {
            throw ilegible(usuarioId);
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMACION);
            cipher.init(Cipher.DECRYPT_MODE, clave,
                    new GCMParameterSpec(BITS_TAG, Arrays.copyOfRange(guardado, 1, 1 + LARGO_NONCE)));
            cipher.updateAAD(aad(usuarioId));
            return cipher.doFinal(guardado, 1 + LARGO_NONCE, guardado.length - 1 - LARGO_NONCE);
        } catch (AEADBadTagException e) {
            throw ilegible(usuarioId);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo descifrar con AES-GCM.", e);
        }
    }

    /** Se loguea solo el usuario: nunca el dato ni la clave. */
    private static IllegalStateException ilegible(long usuarioId) {
        log.error("El secreto del código de la app del usuario {} no se descifra con TOTP_CLAVE_MAESTRA. Si cambió la "
                + "clave, reseteá el código de la app por base (README, Deploy).", usuarioId);
        return new IllegalStateException("El secreto del código de la app del usuario " + usuarioId
                + " no se descifra con TOTP_CLAVE_MAESTRA.");
    }

    private static byte[] aad(long usuarioId) {
        byte[] texto = ("totp|" + usuarioId).getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(1 + texto.length).put(VERSION).put(texto).array();
    }

    /** Valida y lee la clave; el mensaje de error nunca lleva el valor recibido. */
    private static SecretKey leer(String base64) {
        String mensaje = NOMBRE + " tiene que ser una clave de 32 bytes en Base64 (por ejemplo, la salida de "
                + "«openssl rand -base64 32»).";
        if (base64 == null || base64.isBlank()) {
            throw new IllegalStateException(mensaje + " No está definida.");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.strip());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(mensaje + " No es Base64.");
        }
        if (bytes.length != LARGO_CLAVE) {
            throw new IllegalStateException(mensaje + " Tiene " + bytes.length + " bytes.");
        }
        return new SecretKeySpec(bytes, "AES");
    }

    @Override
    public String toString() {
        return "CifradorTotp[claveMaestra=***]";
    }
}
