package com.ferreteria.catalogo.service.lectura;

import com.ferreteria.common.exception.BadRequestException;

/** Detecta el formato por los bytes magicos del contenido; la extension y el Content-Type no se miran. */
public final class DetectorDeFormato {

    private static final int BYTES_A_REVISAR = 4096;
    private static final byte[] ZIP = {0x50, 0x4B, 0x03, 0x04};
    private static final byte[] OLE2 = {
        (byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1};
    private static final byte[] PDF = {0x25, 0x50, 0x44, 0x46};
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47};
    private static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] GIF = {0x47, 0x49, 0x46, 0x38};

    private DetectorDeFormato() {
    }

    public static FormatoLista detectar(byte[] contenido) {
        if (contenido == null || contenido.length == 0) {
            throw new BadRequestException("No se pudo leer el archivo. Verificá que no esté dañado.");
        }
        if (empiezaCon(contenido, ZIP)) {
            return FormatoLista.XLSX;
        }
        if (empiezaCon(contenido, OLE2)) {
            return FormatoLista.XLS;
        }
        if (empiezaCon(contenido, PDF)) {
            throw new BadRequestException("Las listas en PDF todavía no se pueden importar.");
        }
        if (empiezaCon(contenido, PNG) || empiezaCon(contenido, JPEG) || empiezaCon(contenido, GIF)
            || tieneBytesNulos(contenido)) {
            throw new BadRequestException("El archivo no es una lista válida. Subí un Excel (.xlsx o .xls) o un CSV.");
        }
        return FormatoLista.CSV;
    }

    private static boolean empiezaCon(byte[] contenido, byte[] firma) {
        if (contenido.length < firma.length) {
            return false;
        }
        for (int i = 0; i < firma.length; i++) {
            if (contenido[i] != firma[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean tieneBytesNulos(byte[] contenido) {
        int limite = Math.min(contenido.length, BYTES_A_REVISAR);
        for (int i = 0; i < limite; i++) {
            if (contenido[i] == 0) {
                return true;
            }
        }
        return false;
    }
}
