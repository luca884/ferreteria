package com.ferreteria.auth.totp;

import com.google.zxing.WriterException;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import java.util.ArrayList;
import java.util.List;

/**
 * El QR del alta del código de la app (totp-super-admin, S2): la matriz del {@code otpauth://} que escanea Google
 * Authenticator, armada con el core de ZXing con corrección de errores M. Mismo formato que la del QR de la factura
 * ({@code qrModulos}): el front la dibuja.
 */
public final class QrTotp {

    private QrTotp() {
    }

    /**
     * La matriz del QR de ese texto, sin zona de silencio (la agrega quien dibuja): una fila por texto, con '1' en los
     * módulos oscuros y '0' en los claros, todas del mismo largo.
     */
    public static List<String> modulos(String texto) {
        ByteMatrix matriz;
        try {
            matriz = Encoder.encode(texto, ErrorCorrectionLevel.M).getMatrix();
        } catch (WriterException e) {
            throw new IllegalStateException("No se pudo armar el QR del código de la app.", e);
        }
        List<String> filas = new ArrayList<>(matriz.getHeight());
        for (int y = 0; y < matriz.getHeight(); y++) {
            StringBuilder fila = new StringBuilder(matriz.getWidth());
            for (int x = 0; x < matriz.getWidth(); x++) {
                fila.append(matriz.get(x, y) == 1 ? '1' : '0');
            }
            filas.add(fila.toString());
        }
        return List.copyOf(filas);
    }
}
