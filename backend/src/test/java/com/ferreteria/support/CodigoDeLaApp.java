package com.ferreteria.support;

import com.ferreteria.auth.totp.Totp;
import java.io.ByteArrayOutputStream;
import java.time.Instant;

/**
 * Lo que muestra Google Authenticator para una clave del alta (totp-super-admin, S3): la clave en Base32 (RFC 4648, sin
 * relleno) se decodifica acá, aparte del codificador de producción, y el código sale de {@link Totp}, al que prueban los
 * vectores de la RFC 6238 ({@code TotpTest}).
 */
public final class CodigoDeLaApp {

    private static final String ALFABETO = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private CodigoDeLaApp() {
    }

    /** El código de la app para esa clave en el paso de 30 s de {@code cuando}. */
    public static String para(String clave, Instant cuando) {
        return paraElPaso(clave, Totp.paso(cuando));
    }

    /** El código de la app para esa clave en ese paso de 30 s. */
    public static String paraElPaso(String clave, long paso) {
        return Totp.codigo(decodificar(clave), paso);
    }

    /** Un código de 6 números que la app no muestra ni en el paso de {@code cuando}, ni en el anterior ni en el siguiente. */
    public static String incorrecto(String clave, Instant cuando) {
        long paso = Totp.paso(cuando);
        for (int candidato = 0; ; candidato++) {
            String codigo = String.format("%06d", candidato);
            if (!codigo.equals(paraElPaso(clave, paso - 1)) && !codigo.equals(paraElPaso(clave, paso))
                    && !codigo.equals(paraElPaso(clave, paso + 1))) {
                return codigo;
            }
        }
    }

    /** Base32 de la RFC 4648, sin relleno: cada carácter son 5 bits. */
    public static byte[] decodificar(String base32) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int acumulado = 0;
        int bits = 0;
        for (char caracter : base32.toCharArray()) {
            int valor = ALFABETO.indexOf(caracter);
            if (valor < 0) {
                throw new AssertionError("La clave no es Base32: " + base32);
            }
            acumulado = (acumulado << 5) | valor;
            bits += 5;
            if (bits >= 8) {
                bits -= 8;
                bytes.write((acumulado >> bits) & 0xFF);
            }
        }
        return bytes.toByteArray();
    }
}
