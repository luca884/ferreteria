package com.ferreteria.catalogo.service.lectura;

import com.ferreteria.catalogo.model.Celda;
import com.ferreteria.catalogo.model.FilaLeida;
import com.ferreteria.catalogo.model.LecturaLista;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.utils.SeekableInMemoryByteChannel;
import org.apache.poi.ss.usermodel.BuiltinFormats;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.ss.util.NumberToTextConverter;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/**
 * Lee un .xlsx sin armar el libro en memoria (hallazgo alto 1 de la revision del 05/11). POI usermodel arma el arbol de
 * cada parte entera antes de que corra ningun tope: un .xlsx de 4 MB con una hoja de 46 MB agotaba la memoria del servidor
 * de todas las ferreterias. Aca el zip se abre en memoria y cada parte se lee de corrido con SAX:
 * <ul>
 *   <li>los topes de filas, columnas y celdas cortan la lectura mientras se lee;</li>
 *   <li>cada parte tiene tope sobre los bytes que de verdad descomprime (no sobre lo que declara el zip), y el libro entero
 *       tiene uno comun ({@link LimitesDeLista#MAXIMO_ENTRADA_ZIP_BYTES});</li>
 *   <li>un tramo de mas de {@link #MAXIMO_SIN_MARCA} bytes sin {@code <} ni {@code >} (un texto o un atributo enorme) corta
 *       la lectura: el parser nunca arma un texto gigante;</li>
 *   <li>lo que queda en memoria tiene tope: cadenas compartidas y texto de las celdas.</li>
 * </ul>
 * Cada celda se lee como la leia POI usermodel (ver {@link LectorExcel}): las formulas con su valor guardado, los numeros
 * con {@link NumberToTextConverter} y el formato de porcentaje del estilo de la celda.
 */
final class LibroXlsx {

    /** Relaciones, libro y estilos: partes chicas en una lista real (unos KB). */
    static final long MAXIMO_PARTE_CHICA_BYTES = 4L * 1024 * 1024;

    /** Cadenas compartidas: una lista de 20.000 filas trae 2 a 4 MB. Todo su texto queda en memoria. */
    static final long MAXIMO_CADENAS_BYTES = 32L * 1024 * 1024;

    /** Bytes seguidos sin {@code <} ni {@code >}: ninguna celda real se acerca (Excel tiene tope de 32.767 caracteres). */
    static final int MAXIMO_SIN_MARCA = 1024 * 1024;

    /** Caracteres de texto que las celdas leidas guardan en memoria en todo el libro (una lista real: unos pocos millones). */
    static final long MAXIMO_TEXTO_RETENIDO = 32L * 1024 * 1024;

    /** Estilos de celda: Excel admite unos 64.000. */
    static final int MAXIMO_ESTILOS = 100_000;

    private static final Pattern ESCAPE_XML = Pattern.compile("_x([0-9A-Fa-f]{4})_");

    private final ZipFile zip;
    private long presupuesto = LimitesDeLista.MAXIMO_ENTRADA_ZIP_BYTES;
    private long textoRetenido;

    private LibroXlsx(ZipFile zip) {
        this.zip = zip;
    }

    /** El libro entero; un tope pasado es un {@code BadRequestException}, un archivo roto un {@code IOException}. */
    static LecturaLista leer(byte[] contenido) throws IOException {
        try (ZipFile zip = ZipFile.builder().setSeekableByteChannel(new SeekableInMemoryByteChannel(contenido)).get()) {
            return new LibroXlsx(zip).leerLibro();
        }
    }

    private record Relacion(String tipo, String parte) {
    }

    private record Hoja(String nombre, String idRelacion) {
    }

    private LecturaLista leerLibro() throws IOException {
        String parteLibro = relaciones("_rels/.rels", "").values().stream()
                .filter(r -> r.tipo().endsWith("/officeDocument"))
                .map(Relacion::parte)
                .findFirst()
                .orElseThrow(() -> new IOException("El zip no tiene un libro"));
        List<Hoja> hojas = hojas(parteLibro);
        Map<String, Relacion> delLibro = relaciones(relacionesDe(parteLibro), carpetaDe(parteLibro));
        List<String> cadenas = parteDeTipo(delLibro, "/sharedStrings").map(this::cadenas).orElse(List.of());
        Estilos estilos = parteDeTipo(delLibro, "/styles").map(this::estilos).orElse(new Estilos());

        List<String> nombres = new ArrayList<>();
        List<FilaLeida> filas = new ArrayList<>();
        LimitesDeLista.TopeDeCeldas tope = new LimitesDeLista.TopeDeCeldas();
        for (Hoja hoja : hojas) {
            // Como POI usermodel: una hoja sin su parte no existe; la de un grafico existe y no tiene filas.
            Relacion relacion = delLibro.get(hoja.idRelacion());
            if (relacion == null || zip.getEntry(relacion.parte()) == null || !esHoja(relacion.tipo())) {
                continue;
            }
            int indice = nombres.size();
            nombres.add(hoja.nombre());
            if (!relacion.tipo().endsWith("/chartsheet")) {
                parsear(relacion.parte(), LimitesDeLista.MAXIMO_ENTRADA_ZIP_BYTES,
                        new LectorDeHoja(indice, cadenas, estilos, tope, filas));
            }
        }
        return new LecturaLista(nombres, filas);
    }

    private static boolean esHoja(String tipo) {
        return tipo.endsWith("/worksheet") || tipo.endsWith("/chartsheet") || tipo.endsWith("/dialogsheet");
    }

    private static Optional<String> parteDeTipo(Map<String, Relacion> relaciones, String tipo) {
        return relaciones.values().stream().filter(r -> r.tipo().endsWith(tipo)).map(Relacion::parte).findFirst();
    }

    // ---- partes chicas: relaciones, libro, estilos ----

    private Map<String, Relacion> relaciones(String parte, String carpeta) throws IOException {
        Map<String, Relacion> relaciones = new HashMap<>();
        if (zip.getEntry(parte) == null) {
            return relaciones;
        }
        parsear(parte, MAXIMO_PARTE_CHICA_BYTES, new DefaultHandler() {
            @Override
            public void startElement(String uri, String local, String qName, Attributes atributos) throws SAXException {
                if (!"Relationship".equals(local) || "External".equals(atributos.getValue("TargetMode"))) {
                    return;
                }
                String id = atributos.getValue("Id");
                String tipo = atributos.getValue("Type");
                String destino = atributos.getValue("Target");
                if (id != null && tipo != null && destino != null) {
                    relaciones.put(id, new Relacion(tipo, resolver(carpeta, destino)));
                }
            }
        });
        return relaciones;
    }

    private List<Hoja> hojas(String parteLibro) throws IOException {
        List<Hoja> hojas = new ArrayList<>();
        boolean[] esLibro = {false};
        parsear(parteLibro, MAXIMO_PARTE_CHICA_BYTES, new DefaultHandler() {
            private boolean primero = true;

            @Override
            public void startElement(String uri, String local, String qName, Attributes atributos) {
                if (primero) {
                    esLibro[0] = "workbook".equals(local);
                    primero = false;
                }
                if (esLibro[0] && "sheet".equals(local)) {
                    hojas.add(new Hoja(atributos.getValue("name"), idDeRelacion(atributos)));
                }
            }
        });
        if (!esLibro[0]) {
            throw new IOException("La parte principal no es un libro de Excel");
        }
        return hojas;
    }

    /** El {@code r:id} de una hoja, en el espacio de nombres de relaciones (transicional o estricto). */
    private static String idDeRelacion(Attributes atributos) {
        for (int i = 0; i < atributos.getLength(); i++) {
            if ("id".equals(atributos.getLocalName(i)) && atributos.getURI(i).endsWith("/relationships")) {
                return atributos.getValue(i);
            }
        }
        return null;
    }

    /** Por estilo de celda (el {@code s} de la celda), si su formato numerico tiene un %. */
    static final class Estilos {
        private final Map<Integer, String> formatosPropios = new HashMap<>();
        private final List<Integer> formatoDeEstilo = new ArrayList<>();

        boolean porcentaje(int estilo) {
            if (estilo < 0 || estilo >= formatoDeEstilo.size()) {
                return false;
            }
            int formato = formatoDeEstilo.get(estilo);
            String patron = formatosPropios.getOrDefault(formato, BuiltinFormats.getBuiltinFormat(formato));
            return patron != null && patron.contains("%");
        }
    }

    private Estilos estilos(String parte) {
        Estilos estilos = new Estilos();
        parsearSinChequeo(parte, MAXIMO_PARTE_CHICA_BYTES, new DefaultHandler() {
            private boolean enEstilosDeCelda;

            @Override
            public void startElement(String uri, String local, String qName, Attributes atributos) {
                switch (local) {
                    case "numFmt" -> {
                        Integer id = entero(atributos.getValue("numFmtId"));
                        String codigo = atributos.getValue("formatCode");
                        if (id != null && codigo != null) {
                            estilos.formatosPropios.put(id, codigo);
                        }
                    }
                    case "cellXfs" -> enEstilosDeCelda = true;
                    case "xf" -> {
                        if (enEstilosDeCelda) {
                            if (estilos.formatoDeEstilo.size() >= MAXIMO_ESTILOS) {
                                throw LimitesDeLista.demasiadoGrande();
                            }
                            Integer formato = entero(atributos.getValue("numFmtId"));
                            estilos.formatoDeEstilo.add(formato == null ? 0 : formato);
                        }
                    }
                    default -> {
                    }
                }
            }

            @Override
            public void endElement(String uri, String local, String qName) {
                if ("cellXfs".equals(local)) {
                    enEstilosDeCelda = false;
                }
            }
        });
        return estilos;
    }

    // ---- cadenas compartidas ----

    /**
     * Junta el texto de una cadena de Excel ({@code <si>} o {@code <is>}) como {@code XSSFRichTextString.getString}: los
     * tramos con formato ({@code <r><t>}) si los hay, si no el {@code <t>} directo; nunca la guia fonetica ({@code <rPh>}).
     */
    private static final class Texto {
        private final StringBuilder directo = new StringBuilder();
        private final StringBuilder tramos = new StringBuilder();
        private boolean conTramos;
        private int profundidadFonetica;
        private boolean enTramo;
        private StringBuilder destino;

        void empezar(String local) {
            switch (local) {
                case "rPh" -> profundidadFonetica++;
                case "r" -> {
                    enTramo = true;
                    conTramos = true;
                }
                case "t" -> destino = profundidadFonetica > 0 ? null : enTramo ? tramos : directo;
                default -> {
                }
            }
        }

        void terminar(String local) {
            switch (local) {
                case "rPh" -> profundidadFonetica--;
                case "r" -> enTramo = false;
                case "t" -> destino = null;
                default -> {
                }
            }
        }

        void caracteres(char[] ch, int inicio, int largo) {
            if (destino != null) {
                // Muchos tramos chicos no arman un texto gigante: el mismo tope que un tramo sin marcas.
                if (directo.length() + tramos.length() + largo > MAXIMO_SIN_MARCA) {
                    throw LimitesDeLista.demasiadoGrande();
                }
                destino.append(ch, inicio, largo);
            }
        }

        String valor() {
            return desescapar((conTramos ? tramos : directo).toString());
        }
    }

    private List<String> cadenas(String parte) {
        List<String> cadenas = new ArrayList<>();
        parsearSinChequeo(parte, MAXIMO_CADENAS_BYTES, new DefaultHandler() {
            private Texto texto;

            @Override
            public void startElement(String uri, String local, String qName, Attributes atributos) {
                if ("si".equals(local)) {
                    if (cadenas.size() >= LimitesDeLista.MAXIMO_CELDAS) {
                        throw LimitesDeLista.demasiadoGrande();
                    }
                    texto = new Texto();
                } else if (texto != null) {
                    texto.empezar(local);
                }
            }

            @Override
            public void endElement(String uri, String local, String qName) {
                if ("si".equals(local)) {
                    cadenas.add(texto.valor());
                    texto = null;
                } else if (texto != null) {
                    texto.terminar(local);
                }
            }

            @Override
            public void characters(char[] ch, int inicio, int largo) {
                if (texto != null) {
                    texto.caracteres(ch, inicio, largo);
                }
            }
        });
        return cadenas;
    }

    // ---- hojas ----

    private final class LectorDeHoja extends DefaultHandler {
        private final int hoja;
        private final List<String> cadenas;
        private final Estilos estilos;
        private final LimitesDeLista.TopeDeCeldas tope;
        private final List<FilaLeida> filas;

        private int numeroDeFila;
        private TreeMap<Integer, Celda> celdas;
        private int columna;
        private String tipo;
        private int estilo;
        private boolean conFormula;
        private StringBuilder valor;
        private Texto enLinea;
        private boolean enValor;

        LectorDeHoja(int hoja, List<String> cadenas, Estilos estilos, LimitesDeLista.TopeDeCeldas tope,
                List<FilaLeida> filas) {
            this.hoja = hoja;
            this.cadenas = cadenas;
            this.estilos = estilos;
            this.tope = tope;
            this.filas = filas;
        }

        @Override
        public void startElement(String uri, String local, String qName, Attributes atributos) throws SAXException {
            switch (local) {
                case "row" -> {
                    Integer numero = entero(atributos.getValue("r"));
                    numeroDeFila = numero == null ? numeroDeFila + 1 : numero;
                    celdas = new TreeMap<>();
                    columna = -1;
                }
                case "c" -> {
                    if (celdas == null) {
                        return;
                    }
                    String referencia = atributos.getValue("r");
                    columna = referencia == null ? columna + 1 : columnaDe(referencia);
                    tipo = atributos.getValue("t") == null ? "n" : atributos.getValue("t");
                    Integer s = entero(atributos.getValue("s"));
                    estilo = s == null ? 0 : s;
                    conFormula = false;
                    valor = null;
                    enLinea = null;
                }
                case "f" -> conFormula = true;
                case "v" -> {
                    valor = new StringBuilder();
                    enValor = true;
                }
                case "is" -> enLinea = new Texto();
                default -> {
                    if (enLinea != null) {
                        enLinea.empezar(local);
                    }
                }
            }
        }

        @Override
        public void endElement(String uri, String local, String qName) throws SAXException {
            switch (local) {
                case "v" -> enValor = false;
                case "is" -> {
                }
                case "c" -> {
                    if (celdas != null) {
                        agregar(celda());
                    }
                }
                case "row" -> {
                    if (celdas != null) {
                        terminarFila();
                    }
                    celdas = null;
                }
                default -> {
                    if (enLinea != null) {
                        enLinea.terminar(local);
                    }
                }
            }
        }

        @Override
        public void characters(char[] ch, int inicio, int largo) {
            if (enValor) {
                valor.append(ch, inicio, largo);
            } else if (enLinea != null) {
                enLinea.caracteres(ch, inicio, largo);
            }
        }

        /** Como POI usermodel lee la celda (XSSFCell), con el tipo de su valor guardado si es una formula. */
        private Celda celda() throws SAXException {
            String v = valor == null ? null : valor.toString();
            return switch (tipo) {
                case "s" -> {
                    if (v == null || v.isBlank()) {
                        yield Celda.VACIA;
                    }
                    Integer indice = entero(v.strip());
                    if (indice == null || indice < 0 || indice >= cadenas.size()) {
                        throw new SAXException("La celda apunta a una cadena que no existe");
                    }
                    yield new Celda(cadenas.get(indice).strip(), null);
                }
                case "inlineStr" -> new Celda(
                        enLinea != null ? enLinea.valor().strip() : v == null ? "" : desescapar(v).strip(), null);
                case "str" -> new Celda(v == null ? "" : desescapar(v).strip(), null);
                case "b" -> new Celda("1".equals(v) ? "TRUE" : "FALSE", null);
                case "e" -> new Celda(error(v), null);
                case "n" -> numero(v);
                default -> new Celda(v == null ? "" : v.strip(), null);
            };
        }

        private Celda numero(String v) throws SAXException {
            if (v == null) {
                // Una formula sin valor guardado vale 0, como en POI; una celda sin valor esta vacia.
                return conFormula ? numerica(0.0) : Celda.VACIA;
            }
            if (v.isEmpty()) {
                return numerica(0.0);
            }
            try {
                return numerica(Double.parseDouble(v));
            } catch (NumberFormatException e) {
                throw new SAXException("La celda numerica no tiene un numero");
            }
        }

        private Celda numerica(double numero) {
            String texto = NumberToTextConverter.toText(numero);
            boolean porcentaje = estilos.porcentaje(estilo);
            try {
                return new Celda(texto, new BigDecimal(texto), porcentaje);
            } catch (NumberFormatException e) {
                return new Celda(texto, null);
            }
        }

        private String error(String v) throws SAXException {
            if (v == null) {
                return FormulaError.forInt(0).getString();
            }
            try {
                return FormulaError.forString(v).getString();
            } catch (IllegalArgumentException e) {
                throw new SAXException("La celda tiene un error desconocido");
            }
        }

        private void agregar(Celda celda) {
            if (celda.vacia()) {
                return;
            }
            if (columna >= LimitesDeLista.MAXIMO_COLUMNAS) {
                throw LimitesDeLista.demasiadasColumnas();
            }
            textoRetenido += celda.texto().length();
            if (textoRetenido > MAXIMO_TEXTO_RETENIDO) {
                throw LimitesDeLista.demasiadoGrande();
            }
            celdas.put(columna, celda);
        }

        private void terminarFila() {
            List<Celda> lista = new ArrayList<>();
            int ancho = celdas.isEmpty() ? 0 : celdas.lastKey() + 1;
            tope.sumarFila(ancho);
            for (Map.Entry<Integer, Celda> entrada : celdas.entrySet()) {
                while (lista.size() < entrada.getKey()) {
                    lista.add(Celda.VACIA);
                }
                lista.add(entrada.getValue());
            }
            FilaLeida fila = new FilaLeida(hoja, numeroDeFila, lista);
            if (!fila.vacia()) {
                // Tope grueso: no se sigue armando la lista una vez pasado.
                if (filas.size() >= LimitesDeLista.MAXIMO_FILAS_LEIDAS) {
                    throw LimitesDeLista.demasiadasFilas();
                }
                filas.add(fila);
            }
        }
    }

    private static int columnaDe(String referencia) throws SAXException {
        int fin = 0;
        while (fin < referencia.length() && Character.isLetter(referencia.charAt(fin))) {
            fin++;
        }
        if (fin == 0 || fin > 3) {
            throw new SAXException("Referencia de celda invalida");
        }
        return CellReference.convertColStringToIndex(referencia.substring(0, fin));
    }

    // ---- utilidades ----

    /** Los {@code _xHHHH_} con que Excel escribe los caracteres de control, como {@code XSSFRichTextString}. */
    private static String desescapar(String texto) {
        if (!texto.contains("_x")) {
            return texto;
        }
        Matcher m = ESCAPE_XML.matcher(texto);
        StringBuilder resultado = new StringBuilder(texto.length());
        int desde = 0;
        while (m.find()) {
            resultado.append(texto, desde, m.start()).append((char) Integer.parseInt(m.group(1), 16));
            desde = m.end();
        }
        return resultado.append(texto, desde, texto.length()).toString();
    }

    private static Integer entero(String texto) {
        if (texto == null) {
            return null;
        }
        try {
            return Integer.valueOf(texto.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String carpetaDe(String parte) {
        int barra = parte.lastIndexOf('/');
        return barra < 0 ? "" : parte.substring(0, barra + 1);
    }

    /** Las relaciones de {@code xl/workbook.xml} estan en {@code xl/_rels/workbook.xml.rels}. */
    private static String relacionesDe(String parte) {
        return carpetaDe(parte) + "_rels/" + parte.substring(carpetaDe(parte).length()) + ".rels";
    }

    /** El destino de una relacion, relativo a la carpeta de su parte o absoluto desde la raiz del zip. */
    private static String resolver(String carpeta, String destino) throws SAXException {
        try {
            String ruta = URI.create("/" + carpeta).resolve(destino).getPath();
            return ruta.startsWith("/") ? ruta.substring(1) : ruta;
        } catch (IllegalArgumentException e) {
            throw new SAXException("Relacion con un destino invalido");
        }
    }

    /** Parsea una parte; un tope pasado sale tal cual, lo demas es un archivo danado. */
    private void parsear(String parte, long maximo, DefaultHandler manejador) throws IOException {
        ZipArchiveEntry entrada = zip.getEntry(parte);
        if (entrada == null) {
            throw new IOException("Falta la parte " + parte);
        }
        try (InputStream datos = new Acotado(zip.getInputStream(entrada), maximo)) {
            fabrica().newSAXParser().parse(datos, manejador);
        } catch (SAXException | ParserConfigurationException e) {
            throw new IOException("No se pudo leer la parte " + parte, e);
        }
    }

    /** Para las partes opcionales leidas desde un {@code map}: el {@code IOException} sale como unchecked y se desenvuelve. */
    private void parsearSinChequeo(String parte, long maximo, DefaultHandler manejador) {
        try {
            parsear(parte, maximo, manejador);
        } catch (IOException e) {
            throw new ParteIlegible(e);
        }
    }

    /** Un {@code IOException} de una parte opcional; {@link LectorExcel} lo trata como archivo danado. */
    static final class ParteIlegible extends RuntimeException {
        ParteIlegible(IOException causa) {
            super(causa);
        }
    }

    private static SAXParserFactory fabrica() throws ParserConfigurationException, SAXException {
        SAXParserFactory fabrica = SAXParserFactory.newInstance();
        fabrica.setNamespaceAware(true);
        fabrica.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        fabrica.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        fabrica.setFeature("http://xml.org/sax/features/external-general-entities", false);
        fabrica.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        fabrica.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        fabrica.setXIncludeAware(false);
        return fabrica;
    }

    /** Donde esta {@link Acotado} dentro del XML: lo que el parser junta entero antes de entregarlo. */
    private enum Tramo {
        NORMAL, DESPUES_DE_MENOR, DESPUES_DE_BANG, COMENTARIO, CDATA, INSTRUCCION, DECLARACION
    }

    /**
     * Cuenta los bytes descomprimidos de verdad: la parte tiene su tope y el libro entero el suyo. Corta tambien un tramo
     * de mas de {@link #MAXIMO_SIN_MARCA} bytes que el parser juntaria entero en memoria: un texto o un atributo terminan en
     * el proximo {@code <} (no pueden tenerlo; un {@code >} si), y un comentario, un CDATA o una instruccion de proceso
     * recien en su cierre ({@code -->}, {@code ]]>}, {@code ?>}), aunque tengan {@code <} adentro.
     */
    private final class Acotado extends FilterInputStream {
        private final long maximo;
        private long leidos;
        private int sinMarca;
        private Tramo tramo = Tramo.NORMAL;
        private byte anterior;
        private byte anteAnterior;

        Acotado(InputStream datos, long maximo) {
            super(datos);
            this.maximo = maximo;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                contar(1);
                revisar((byte) b);
            }
            return b;
        }

        @Override
        public int read(byte[] destino, int desde, int largo) throws IOException {
            int n = super.read(destino, desde, largo);
            if (n > 0) {
                contar(n);
                for (int i = desde; i < desde + n; i++) {
                    revisar(destino[i]);
                }
            }
            return n;
        }

        @Override
        public long skip(long n) throws IOException {
            byte[] descarte = new byte[8192];
            long salteados = 0;
            while (salteados < n) {
                int leido = read(descarte, 0, (int) Math.min(descarte.length, n - salteados));
                if (leido < 0) {
                    break;
                }
                salteados += leido;
            }
            return salteados;
        }

        private void contar(int n) {
            leidos += n;
            presupuesto -= n;
            if (leidos > maximo || presupuesto < 0) {
                throw LimitesDeLista.demasiadoGrande();
            }
        }

        private void revisar(byte b) {
            tramo = switch (tramo) {
                case NORMAL, DESPUES_DE_MENOR -> b == '<' ? Tramo.DESPUES_DE_MENOR
                        : tramo == Tramo.DESPUES_DE_MENOR && b == '!' ? Tramo.DESPUES_DE_BANG
                        : tramo == Tramo.DESPUES_DE_MENOR && b == '?' ? Tramo.INSTRUCCION
                        : Tramo.NORMAL;
                case DESPUES_DE_BANG -> b == '-' ? Tramo.COMENTARIO : b == '[' ? Tramo.CDATA : Tramo.DECLARACION;
                case COMENTARIO -> cierra(b, '-', '-') ? Tramo.NORMAL : tramo;
                case CDATA -> cierra(b, ']', ']') ? Tramo.NORMAL : tramo;
                case INSTRUCCION -> b == '>' && anterior == '?' ? Tramo.NORMAL : tramo;
                case DECLARACION -> b == '>' ? Tramo.NORMAL : tramo;
            };
            anteAnterior = anterior;
            anterior = b;
            if (tramo == Tramo.DESPUES_DE_MENOR) {
                sinMarca = 0;
            } else if (++sinMarca > MAXIMO_SIN_MARCA) {
                throw LimitesDeLista.demasiadoGrande();
            }
        }

        private boolean cierra(byte b, char primero, char segundo) {
            return b == '>' && anterior == segundo && anteAnterior == primero;
        }
    }
}
