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

    /** Un CSV es una sola hoja, la 0: todas sus filas llevan hoja 0. */
    @Override
    public LecturaLista leer(byte[] contenido) {
        String decodificado = decodificar(contenido);
        char delimitador = detectarDelimitador(decodificado);
        // commons-csv arma el registro entero antes de devolverlo: los topes se controlan antes de parsear.
        String texto = escanear(decodificado, delimitador);
        CSVFormat formato = CSVFormat.DEFAULT.builder()
            .setDelimiter(delimitador)
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

    /** Las celdas hasta la ultima con datos: las vacias del final no se guardan. */
    private static List<Celda> celdas(CSVRecord registro) {
        List<Celda> celdas = new ArrayList<>(registro.size());
        int ancho = 0;
        for (String valor : registro) {
            Celda celda = new Celda(sinFormulaDeTexto(valor), null);
            celdas.add(celda);
            if (!celda.vacia()) {
                ancho = celdas.size();
            }
        }
        return new ArrayList<>(celdas.subList(0, ancho));
    }

    /**
     * Recorre el texto como lo parte commons-csv (una comilla abre comillas solo al principio del campo; adentro,
     * dos comillas son una) sin crear objetos. Por registro, cuenta hasta la ultima columna con datos contra
     * {@link LimitesDeLista.TopeDeCeldas}: un dato mas alla de la columna 200 o mas de un millon de celdas rechazan
     * la lista antes de armarla. Si un registro tiene mas de 200 campos pero los del final estan vacios, se los
     * saca del texto, para que el parser no arme miles de campos vacios.
     */
    private static String escanear(String texto, char delimitador) {
        EscaneoCsv escaneo = new EscaneoCsv();
        boolean principioDeCampo = true;
        boolean entreComillas = false;
        int largo = texto.length();
        for (int i = 0; i < largo; i++) {
            char c = texto.charAt(i);
            if (entreComillas) {
                if (c == '"' && i + 1 < largo && texto.charAt(i + 1) == '"') {
                    escaneo.dato();
                    i++;
                } else if (c == '"') {
                    entreComillas = false;
                } else if (c > ' ') {
                    escaneo.dato();
                }
            } else if (c == delimitador) {
                escaneo.separador(i);
                principioDeCampo = true;
            } else if (c == '\n' || c == '\r') {
                int fin = i;
                if (c == '\r' && i + 1 < largo && texto.charAt(i + 1) == '\n') {
                    i++;
                }
                escaneo.finDeRegistro(fin, i + 1);
                principioDeCampo = true;
            } else if (principioDeCampo && c == '"') {
                entreComillas = true;
                principioDeCampo = false;
            } else {
                principioDeCampo = false;
                if (c > ' ') {
                    escaneo.dato();
                }
            }
        }
        // El ultimo registro, aunque no tenga fin de linea o le queden comillas abiertas.
        escaneo.finDeRegistro(largo, largo);
        return escaneo.sinRecortes(texto);
    }

    /** Lo que {@link #escanear} lleva del registro en curso y los tramos vacios a sacar del texto. */
    private static final class EscaneoCsv {

        private final LimitesDeLista.TopeDeCeldas tope = new LimitesDeLista.TopeDeCeldas();
        private final List<int[]> recortes = new ArrayList<>();
        private int campo;
        private int ultimaConDatos = -1;
        /** Donde termina lo que tiene datos del registro: el separador que sigue al ultimo campo con datos. */
        private int corte;

        void dato() {
            if (campo >= LimitesDeLista.MAXIMO_COLUMNAS) {
                throw LimitesDeLista.demasiadasColumnas();
            }
            ultimaConDatos = campo;
        }

        void separador(int posicion) {
            if (ultimaConDatos == campo) {
                corte = posicion;
            }
            campo++;
        }

        /** {@code fin}: donde empieza el fin de linea; {@code siguiente}: donde empieza el proximo registro. */
        void finDeRegistro(int fin, int siguiente) {
            tope.sumarFila(ultimaConDatos + 1);
            if (campo >= LimitesDeLista.MAXIMO_COLUMNAS) {
                recortes.add(new int[] {corte, fin});
            }
            campo = 0;
            ultimaConDatos = -1;
            corte = siguiente;
        }

        String sinRecortes(String texto) {
            if (recortes.isEmpty()) {
                return texto;
            }
            StringBuilder recortado = new StringBuilder(texto.length());
            int desde = 0;
            for (int[] recorte : recortes) {
                recortado.append(texto, desde, recorte[0]);
                desde = recorte[1];
            }
            return recortado.append(texto, desde, texto.length()).toString();
        }
    }

    /**
     * {@code ="0012"} es como Excel guarda un texto que no tiene que pasar a numero, y asi escribe los codigos la
     * exportacion de articulos (GDS-19): se lee 0012, con las comillas dobladas de adentro como una sola. Cualquier
     * otra cosa queda como esta.
     */
    private static String sinFormulaDeTexto(String valor) {
        if (valor.length() < 3 || !valor.startsWith("=\"") || !valor.endsWith("\"")) {
            return valor;
        }
        String adentro = valor.substring(2, valor.length() - 1);
        if (adentro.replace("\"\"", "").contains("\"")) {
            return valor;
        }
        return adentro.replace("\"\"", "\"");
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
