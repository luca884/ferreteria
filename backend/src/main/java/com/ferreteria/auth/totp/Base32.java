package com.ferreteria.auth.totp;

/**
 * Base32 de la RFC 4648 sin el relleno {@code =}: así va la clave del código de la app en el {@code otpauth://} y así la
 * carga a mano quien no puede escanear el QR (totp-super-admin, S2 y S3).
 */
public final class Base32 {

    private static final char[] ALFABETO = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();

    private Base32() {
    }

    /** Cada 5 bits, un carácter; los bits que sobran al final se completan con ceros. */
    public static String codificar(byte[] bytes) {
        StringBuilder texto = new StringBuilder((bytes.length * 8 + 4) / 5);
        int acumulado = 0;
        int bits = 0;
        for (byte b : bytes) {
            acumulado = (acumulado << 8) | (b & 0xFF);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                texto.append(ALFABETO[(acumulado >> bits) & 0x1F]);
            }
        }
        if (bits > 0) {
            texto.append(ALFABETO[(acumulado << (5 - bits)) & 0x1F]);
        }
        return texto.toString();
    }
}
