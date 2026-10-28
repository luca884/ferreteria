package com.ferreteria.facturacion.documento;

import com.google.zxing.WriterException;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.ByteMatrix;
import com.google.zxing.qrcode.encoder.Encoder;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * El código QR de ARCA de un comprobante autorizado (cu-07-factura-mostrador S7 y S14; especificación del 03/02/2025,
 * versión 1). El texto es {@code https://www.arca.gob.ar/fe/qr/?p=} seguido del JSON del comprobante en Base64 estándar,
 * con relleno: la URL sale del texto de la especificación, no de su ejemplo (que todavía usa {@code afip.gob.ar}). La
 * matriz la arma el core de ZXing con corrección de errores M; el front la dibuja.
 *
 * <p>El JSON va en el orden de la especificación, con los números como números JSON: {@code ver} 1, {@code fecha} (la
 * CbteFch, aaaa-mm-dd), {@code cuit}, {@code ptoVta}, {@code tipoCmp}, {@code nroCmp}, {@code importe} (con 2 decimales),
 * {@code moneda} "PES", {@code ctz} 1, {@code tipoDocRec} y {@code nroDocRec} tal como se enviaron a ARCA (también 99 y 0),
 * {@code tipoCodAut} "E" y {@code codAut} (el CAE, 14 dígitos).
 */
public final class QrArca {

    /** La URL de la especificación, con el parámetro {@code p} listo para el Base64. */
    public static final String URL = "https://www.arca.gob.ar/fe/qr/?p=";

    private static final int VERSION = 1;
    private static final String MONEDA = "PES";
    private static final int COTIZACION = 1;
    private static final String TIPO_CODIGO_AUTORIZACION = "E";

    /**
     * Lo que va en el QR, tal como se informó a ARCA y como lo autorizó.
     *
     * @param fecha la CbteFch
     * @param cuit la CUIT emisora, 11 dígitos
     * @param ptoVta el punto de venta
     * @param tipoCmp el {@code CbteTipo}
     * @param nroCmp el número del comprobante
     * @param importe el {@code ImpTotal}, con 2 decimales como mucho
     * @param tipoDocRec el {@code DocTipo} del receptor (99 sin identificar)
     * @param nroDocRec el {@code DocNro} del receptor (0 sin identificar)
     * @param codAut el CAE
     */
    public record Datos(LocalDate fecha, long cuit, int ptoVta, int tipoCmp, long nroCmp, BigDecimal importe,
            int tipoDocRec, long nroDocRec, long codAut) {

        public Datos {
            Objects.requireNonNull(fecha, "Falta la fecha del comprobante.");
            Objects.requireNonNull(importe, "Falta el importe del comprobante.");
        }
    }

    private QrArca() {
    }

    /** El texto del QR: la URL con el JSON del comprobante en Base64. */
    public static String texto(Datos datos) {
        return URL + Base64.getEncoder().encodeToString(json(datos).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * La matriz del QR de ese texto, con corrección de errores M y sin zona de silencio (la agrega quien dibuja): una fila
     * por texto, con '1' en los módulos oscuros y '0' en los claros, todas del mismo largo.
     */
    public static List<String> modulos(String texto) {
        ByteMatrix matriz;
        try {
            matriz = Encoder.encode(texto, ErrorCorrectionLevel.M).getMatrix();
        } catch (WriterException e) {
            throw new IllegalStateException("No se pudo armar el QR del comprobante.", e);
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

    /** El JSON v1, en el orden de la especificación. Ningún valor necesita escape: son números, una fecha ISO y constantes. */
    static String json(Datos d) {
        BigDecimal importe = d.importe().setScale(2, RoundingMode.UNNECESSARY);
        return "{\"ver\":" + VERSION
                + ",\"fecha\":\"" + d.fecha() + "\""
                + ",\"cuit\":" + d.cuit()
                + ",\"ptoVta\":" + d.ptoVta()
                + ",\"tipoCmp\":" + d.tipoCmp()
                + ",\"nroCmp\":" + d.nroCmp()
                + ",\"importe\":" + importe.toPlainString()
                + ",\"moneda\":\"" + MONEDA + "\""
                + ",\"ctz\":" + COTIZACION
                + ",\"tipoDocRec\":" + d.tipoDocRec()
                + ",\"nroDocRec\":" + d.nroDocRec()
                + ",\"tipoCodAut\":\"" + TIPO_CODIGO_AUTORIZACION + "\""
                + ",\"codAut\":" + d.codAut()
                + "}";
    }
}
