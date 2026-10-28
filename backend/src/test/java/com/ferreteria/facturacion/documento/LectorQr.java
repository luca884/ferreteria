package com.ferreteria.facturacion.documento;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.ResultMetadataType;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Lee un QR como lo dibuja el front (cu-07-factura-mostrador S7): la matriz de {@code qrModulos} ('1' oscuro, '0' claro),
 * con la zona de silencio de 4 módulos que agrega quien dibuja y 4 píxeles por módulo, con el lector del core de ZXing. Y
 * abre el texto: saca {@code p} de la URL y lo decodifica como Base64 estándar (con relleno) a JSON.
 */
public final class LectorQr {

    private static final int SILENCIO = 4;
    private static final int ESCALA = 4;
    private static final int OSCURO = 0xFF000000;
    private static final int CLARO = 0xFFFFFFFF;

    private LectorQr() {
    }

    /** Lo que lee ZXing de la matriz: el texto y su nivel de corrección de errores. */
    public record Lectura(String texto, String nivelDeCorreccion) {}

    public static Lectura leer(List<String> modulos) {
        int n = modulos.size();
        int lado = (n + 2 * SILENCIO) * ESCALA;
        int[] pixeles = new int[lado * lado];
        java.util.Arrays.fill(pixeles, CLARO);
        for (int fila = 0; fila < n; fila++) {
            for (int columna = 0; columna < n; columna++) {
                if (modulos.get(fila).charAt(columna) != '1') {
                    continue;
                }
                for (int dy = 0; dy < ESCALA; dy++) {
                    for (int dx = 0; dx < ESCALA; dx++) {
                        int y = (fila + SILENCIO) * ESCALA + dy;
                        int x = (columna + SILENCIO) * ESCALA + dx;
                        pixeles[y * lado + x] = OSCURO;
                    }
                }
            }
        }
        RGBLuminanceSource imagen = new RGBLuminanceSource(lado, lado, pixeles);
        try {
            Result resultado = new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(imagen)),
                    Map.of(DecodeHintType.PURE_BARCODE, Boolean.TRUE));
            return new Lectura(resultado.getText(),
                    String.valueOf(resultado.getResultMetadata().get(ResultMetadataType.ERROR_CORRECTION_LEVEL)));
        } catch (Exception e) {
            throw new AssertionError("ZXing no pudo leer la matriz del QR", e);
        }
    }

    /** El parámetro {@code p} de la URL del QR, tal cual (Base64). */
    public static String parametro(String url) {
        int desde = url.indexOf("?p=");
        if (desde < 0) {
            throw new AssertionError("La URL del QR no trae ?p=: " + url);
        }
        return url.substring(desde + 3);
    }

    /** El JSON del comprobante: {@code p} decodificado como Base64 estándar. */
    public static JsonNode datos(String url) {
        byte[] json = Base64.getDecoder().decode(parametro(url));
        try {
            // Los decimales como BigDecimal: el importe se compara exacto, con su escala.
            return new ObjectMapper().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                    .readTree(new String(json, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
