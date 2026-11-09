package com.ferreteria.auth.totp;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.OptionalLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * El código de la app del super admin (totp-super-admin, S3): TOTP de la RFC 6238 con HMAC-SHA1, 6 dígitos y pasos de
 * 30 s desde 1970, lo mismo que calcula Google Authenticator con el {@code otpauth://} del alta. Se acepta el código del
 * paso actual, el del anterior y el del siguiente (el reloj del celular puede ir unos segundos corrido).
 *
 * <p>Ningún método loguea ni guarda el secreto o los códigos.
 */
public final class Totp {

    /** Segundos de cada paso. */
    public static final long PERIODO = 30;
    /** Cuántos números tiene el código. */
    public static final int DIGITOS = 6;

    private static final int MODULO = 1_000_000;
    private static final String HMAC = "HmacSHA1";

    private Totp() {
    }

    /** El paso de 30 s de ese instante: los segundos desde 1970 divididos por 30. */
    public static long paso(Instant instante) {
        return Math.floorDiv(instante.getEpochSecond(), PERIODO);
    }

    /** El código de 6 números (con los ceros de la izquierda) de ese secreto en ese paso: HOTP (RFC 4226) del paso. */
    public static String codigo(byte[] secreto, long paso) {
        byte[] hash;
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secreto, HMAC));
            hash = mac.doFinal(ByteBuffer.allocate(Long.BYTES).putLong(paso).array());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("La JVM no tiene HmacSHA1.", e);
        }
        // Truncado dinámico (RFC 4226, 5.3): 31 bits desde el desplazamiento que marca el último medio byte.
        int desde = hash[hash.length - 1] & 0x0F;
        int binario = ((hash[desde] & 0x7F) << 24) | ((hash[desde + 1] & 0xFF) << 16) | ((hash[desde + 2] & 0xFF) << 8)
                | (hash[desde + 3] & 0xFF);
        return String.format("%0" + DIGITOS + "d", binario % MODULO);
    }

    /**
     * El paso al que corresponde {@code codigo}, si es el del paso actual, el anterior o el siguiente; vacío si no es ninguno.
     * Compara los tres siempre, en tiempo constante: ni el tiempo de respuesta ni el orden dicen cuál de los tres coincidió.
     * Si coincide más de uno (una casualidad de 1 en 10⁶), devuelve el más nuevo.
     */
    public static OptionalLong pasoDelCodigo(byte[] secreto, String codigo, long pasoActual) {
        byte[] escrito = codigo.getBytes(StandardCharsets.US_ASCII);
        long encontrado = -1;
        for (long paso = pasoActual - 1; paso <= pasoActual + 1; paso++) {
            if (MessageDigest.isEqual(escrito, codigo(secreto, paso).getBytes(StandardCharsets.US_ASCII))) {
                encontrado = paso;
            }
        }
        return encontrado < 0 ? OptionalLong.empty() : OptionalLong.of(encontrado);
    }
}
