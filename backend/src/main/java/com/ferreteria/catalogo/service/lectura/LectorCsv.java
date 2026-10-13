package com.ferreteria.catalogo.service.lectura;

import com.ferreteria.catalogo.model.Celda;
import com.ferreteria.catalogo.model.FilaLeida;
import com.ferreteria.catalogo.model.LecturaLista;
import com.ferreteria.common.exception.BadRequestException;
import java.io.IOException;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;

/**
 * Lee CSV: quita el BOM, decodifica UTF-8 estricto (si falla, windows-1252) y detecta el delimitador entre
 * {@code ;}, {@code ,} y tabulador en la primera linea con contenido. El numero de fila es el del registro.
 */
@Component
public class LectorCsv implements LectorDeLista {

    private static final String HOJA = "CSV";
    private static final char[] CANDIDATOS = {';', ',', '\t'};
    private static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    private static final byte[] BOM ={(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    @Override
    public LecturaLista leer(byte[] contenido, int hoja) {
        if (hoja != 0) {
            throw new BadRequestException("La hoja elegida no existe en el archivo.");
        }
        String texto = decodificar(contenido);
        CSVFormat formato = CSVFormat.DEFAULT.builder()
            .setDelimiter(detectarDelimitador(texto))
            .setTrim(true)
            .setIgnoreEmptyLines(false)
            .get();
        List<FilaLeida> filas = new ArrayList<>();
        try (CSVParser parser = formato.parse(new StringReader(texto))) {
            for (CSVRecord registro : parser) {
                FilaLeida fila = new FilaLeida((int) registro.getRecordNumber(), celdas(registro));
                if (!fila.vacia()) {
                    // Tope grueso: se deja de parsear apenas se pasa, sin mirar lo que sigue.
                    if (filas.size() >= LimitesDeLista.MAXIMO_FILAS_LEIDAS) {
                        throw LimitesDeLista.demasiadasFilas();
                    }
                    filas.add(fila);
                }
            }
        } catch (IOException | UncheckedIOException | IllegalStateException e) {
            throw new BadRequestException("No se pudo leer el CSV. Revisá las comillas.");
        }
        return new LecturaLista(List.of(HOJA), filas);
    }

    private static List<Celda> celdas(CSVRecord registro) {
        List<Celda> celdas = new ArrayList<>(registro.size());
        for (String valor : registro) {
            celdas.add(new Celda(valor, null));
        }
        return celdas;
    }

    private static String decodificar(byte[] contenido) {
        int desde = empiezaConBom(contenido) ? BOM.length : 0;
        ByteBuffer bytes = ByteBuffer.wrap(contenido, desde, contenido.length - desde);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(bytes)
                .toString();
        } catch (CharacterCodingException e) {
            return new String(contenido, desde, contenido.length - desde, WINDOWS_1252);
        }
    }

    private static boolean empiezaConBom(byte[] contenido) {
        return contenido.length >= BOM.length
            && contenido[0] == BOM[0] && contenido[1] == BOM[1] && contenido[2] == BOM[2];
    }

    /** Cuenta cada candidato fuera de comillas en la primera linea con contenido; empate o cero da {@code ;}. */
    private static char detectarDelimitador(String texto) {
        int[] cuentas = new int[CANDIDATOS.length];
        boolean entreComillas = false;
        boolean hayContenido = false;
        for (int i = 0; i < texto.length(); i++) {
            char c = texto.charAt(i);
            if (c == '"') {
                entreComillas = !entreComillas;
                hayContenido = true;
            } else if (!entreComillas && (c == '\n' || c == '\r')) {
                if (hayContenido) {
                    break;
                }
            } else {
                if (!entreComillas) {
                    for (int k = 0; k < CANDIDATOS.length; k++) {
                        if (c == CANDIDATOS[k]) {
                            cuentas[k]++;
                        }
                    }
                }
                if (!Character.isWhitespace(c) || c == '\t') {
                    hayContenido = true;
                }
            }
        }
        int mejor = 0;
        for (int k = 1; k < cuentas.length; k++) {
            if (cuentas[k] > cuentas[mejor]) {
                mejor = k;
            }
        }
        if (cuentas[mejor] == 0) {
            return ';';
        }
        for (int k = 0; k < cuentas.length; k++) {
            if (k != mejor && cuentas[k] == cuentas[mejor]) {
                return ';';
            }
        }
        return CANDIDATOS[mejor];
    }
}
