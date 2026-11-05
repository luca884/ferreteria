package com.ferreteria.catalogo.service.lectura;

import com.ferreteria.catalogo.model.Celda;
import com.ferreteria.catalogo.model.FilaLeida;
import com.ferreteria.catalogo.model.LecturaLista;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.hssf.OldExcelFormatException;
import org.apache.poi.hssf.eventusermodel.HSSFEventFactory;
import org.apache.poi.hssf.eventusermodel.HSSFListener;
import org.apache.poi.hssf.eventusermodel.HSSFRequest;
import org.apache.poi.hssf.record.ArrayRecord;
import org.apache.poi.hssf.record.BOFRecord;
import org.apache.poi.hssf.record.BoolErrRecord;
import org.apache.poi.hssf.record.BoundSheetRecord;
import org.apache.poi.hssf.record.EOFRecord;
import org.apache.poi.hssf.record.ExtendedFormatRecord;
import org.apache.poi.hssf.record.FormatRecord;
import org.apache.poi.hssf.record.FormulaRecord;
import org.apache.poi.hssf.record.LabelRecord;
import org.apache.poi.hssf.record.LabelSSTRecord;
import org.apache.poi.hssf.record.MulRKRecord;
import org.apache.poi.hssf.record.NumberRecord;
import org.apache.poi.hssf.record.RKRecord;
import org.apache.poi.hssf.record.Record;
import org.apache.poi.hssf.record.SSTRecord;
import org.apache.poi.hssf.record.SharedFormulaRecord;
import org.apache.poi.hssf.record.StringRecord;
import org.apache.poi.hssf.record.TableRecord;
import org.apache.poi.poifs.crypt.Decryptor;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.ss.usermodel.BuiltinFormats;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.FormulaError;
import org.apache.poi.ss.util.NumberToTextConverter;

/**
 * Lee un .xls sin armar el libro en memoria (hallazgo alto 1 de la revision del 05/11). POI usermodel arma un objeto por
 * celda: un .xls de 8 MB con una hoja de celdas en blanco con formato (el .xls las guarda seguidas, 2 bytes cada una)
 * agotaba la memoria del servidor de todas las ferreterias. Aca se leen los registros del flujo "Workbook" de a uno con la
 * API de eventos de POI ({@link HSSFEventFactory}), sin {@code HSSFWorkbook}:
 * <ul>
 *   <li>las celdas en blanco no se expanden ni se cuentan;</li>
 *   <li>los topes de filas, columnas y celdas cortan la lectura mientras se lee;</li>
 *   <li>POI arma la tabla de cadenas compartidas entera antes de avisarla: se mira la cantidad que declara en el flujo
 *       crudo y se rechaza la lista si pasa {@link LimitesDeLista#MAXIMO_CELDAS} antes de dejar que POI la arme;</li>
 *   <li>lo que queda en memoria tiene tope: formatos, estilos y texto de las celdas.</li>
 * </ul>
 * Cada celda se lee como la leia POI usermodel (ver {@link LectorExcel}): las formulas con su valor guardado, los numeros
 * con {@link NumberToTextConverter} y el formato de porcentaje del estilo de la celda. Las celdas de una fila vienen
 * seguidas en el flujo: la fila termina cuando cambia el numero o termina la hoja.
 */
final class LibroXls {

    /** Estilos de celda (registros XF): Excel 97 admite 4.050. */
    static final int MAXIMO_ESTILOS = 100_000;

    /** Formatos numericos propios (registros FORMAT): un libro real tiene unas decenas. */
    static final int MAXIMO_FORMATOS = 10_000;

    /** Registro SST: su cabecera trae el total de cadenas y la cantidad de distintas. */
    private static final int SID_SST = 0x00FC;

    /** Registro FILEPASS: el libro esta cifrado desde aca. */
    private static final int SID_FILE_PASS = 0x002F;

    private final List<BoundSheetRecord> hojasDelLibro = new ArrayList<>();
    private final Map<Integer, String> formatosPropios = new HashMap<>();
    private final List<Integer> formatoDeEstilo = new ArrayList<>();
    private final List<FilaLeida> filas = new ArrayList<>();
    private final LimitesDeLista.TopeDeCeldas tope = new LimitesDeLista.TopeDeCeldas();
    private SSTRecord cadenas;
    private List<String> nombres;
    private long textoRetenido;

    // Donde esta la lectura: profundidad de BOF/EOF (un grafico dentro de una hoja abre otro BOF) y hoja actual.
    private int profundidad;
    private int hoja = -1;
    private boolean enHoja;
    private int numeroDeFila = -1;
    private TreeMap<Integer, Celda> celdas;
    private FormulaRecord formulaConTexto;

    private LibroXls() {
    }

    /**
     * El libro entero; un tope pasado es un {@code BadRequestException}, un archivo roto un {@code IOException} (o un
     * error de POI sin chequear) y uno cifrado un {@code EncryptedDocumentException}. Un archivo cifrado se rechaza sin
     * descifrarlo, antes de mirar nada mas: POI probaria la contraseña por defecto de Excel.
     */
    static LecturaLista leer(byte[] contenido) throws IOException {
        if (FileMagic.valueOf(contenido) != FileMagic.OLE2) {
            throw new IOException("No es un archivo de Excel");
        }
        try (POIFSFileSystem sistema = new POIFSFileSystem(new ByteArrayInputStream(contenido))) {
            DirectoryNode raiz = sistema.getRoot();
            if (raiz.hasEntryCaseInsensitive(Decryptor.DEFAULT_POIFS_ENTRY)) {
                throw new EncryptedDocumentException("Archivo cifrado");
            }
            String flujo = nombreDelFlujo(raiz);
            try (InputStream datos = raiz.createDocumentInputStream(flujo)) {
                revisarCadenasCompartidas(datos);
            }
            LibroXls libro = new LibroXls();
            HSSFRequest pedido = new HSSFRequest();
            pedido.addListenerForAllRecords(libro::registro);
            try (InputStream datos = raiz.createDocumentInputStream(flujo)) {
                new HSSFEventFactory().processEvents(pedido, datos);
            }
            return libro.resultado();
        }
    }

    /** "Workbook" (Excel 97 o mas nuevo); el flujo "Book" es de Excel 5/7 y POI no lo lee. */
    private static String nombreDelFlujo(DirectoryNode raiz) throws IOException {
        for (String nombre : List.of("Workbook", "WORKBOOK")) {
            if (raiz.hasEntry(nombre)) {
                return nombre;
            }
        }
        if (raiz.hasEntry("Book") || raiz.hasEntry("BOOK")) {
            throw new OldExcelFormatException("Excel 5.0/7.0");
        }
        throw new FileNotFoundException("El archivo no tiene un libro de Excel");
    }

    /**
     * Recorre las cabeceras de los registros del flujo (4 bytes cada una, saltando los datos) y rechaza una tabla de
     * cadenas compartidas con mas distintas que {@link LimitesDeLista#MAXIMO_CELDAS}: POI la arma entera en memoria antes
     * de que nadie la vea (un .xls de 25 MB cabe con millones de cadenas de un caracter). Un flujo cortado no se juzga
     * aca: la lectura de verdad lo informa.
     */
    private static void revisarCadenasCompartidas(InputStream flujo) throws IOException {
        DataInputStream datos = new DataInputStream(flujo);
        byte[] cabecera = new byte[4];
        try {
            while (true) {
                datos.readFully(cabecera);
                int sid = (cabecera[0] & 0xFF) | (cabecera[1] & 0xFF) << 8;
                int largo = (cabecera[2] & 0xFF) | (cabecera[3] & 0xFF) << 8;
                if (sid == SID_FILE_PASS) {
                    // Cifrado: los datos de cada registro (la cabecera de la SST incluida) vienen cifrados, y POI los
                    // descifraria con la contraseña por defecto de Excel. Como el .xlsx cifrado: no se descifra.
                    throw new EncryptedDocumentException("Archivo cifrado");
                }
                if (sid == SID_SST && largo >= 8) {
                    byte[] cantidades = new byte[8];
                    datos.readFully(cantidades);
                    long distintas = Integer.toUnsignedLong(
                            (cantidades[4] & 0xFF) | (cantidades[5] & 0xFF) << 8 | (cantidades[6] & 0xFF) << 16
                                    | (cantidades[7] & 0xFF) << 24);
                    if (distintas > LimitesDeLista.MAXIMO_CELDAS) {
                        throw LimitesDeLista.demasiadoGrande();
                    }
                    datos.skipNBytes(largo - 8);
                } else {
                    datos.skipNBytes(largo);
                }
            }
        } catch (EOFException e) {
            // fin del flujo
        }
    }

    private LecturaLista resultado() {
        return new LecturaLista(nombres == null ? List.of() : nombres, filas);
    }

    // ---- registros ----

    private void registro(Record registro) {
        if (formulaConTexto != null && !(registro instanceof StringRecord) && !esDeFormula(registro)) {
            // La formula daba un texto y no vino su StringRecord: vale "" (POI usermodel igual).
            FormulaRecord formula = formulaConTexto;
            formulaConTexto = null;
            agregar(formula.getRow(), columna(formula.getColumn()), new Celda("", null));
        }
        if (registro instanceof BOFRecord bof) {
            abrirFlujo(bof);
        } else if (registro instanceof EOFRecord) {
            cerrarFlujo();
        } else if (profundidad == 1 && hoja < 0) {
            globales(registro);
        } else if (profundidad == 1 && enHoja) {
            celda(registro);
        }
    }

    /** La columna como numero sin signo (el registro la guarda en un short). */
    private static int columna(short columna) {
        return columna & 0xFFFF;
    }

    private static boolean esDeFormula(Record registro) {
        return registro instanceof SharedFormulaRecord || registro instanceof ArrayRecord
                || registro instanceof TableRecord;
    }

    private void abrirFlujo(BOFRecord bof) {
        if (profundidad == 0 && bof.getType() != BOFRecord.TYPE_WORKBOOK) {
            if (nombres == null) {
                nombres = nombresDeLasHojas();
            }
            hoja++;
            // Una hoja de mas (un archivo raro) o un grafico no tienen celdas que leer.
            enHoja = hoja < nombres.size();
            numeroDeFila = -1;
            celdas = null;
        }
        profundidad++;
    }

    private void cerrarFlujo() {
        profundidad--;
        if (profundidad == 0 && enHoja) {
            terminarFila();
            enHoja = false;
        }
    }

    /** Los nombres de las hojas en el orden de sus flujos (por posicion del BOF), como los da {@code HSSFWorkbook}. */
    private List<String> nombresDeLasHojas() {
        List<String> lista = new ArrayList<>();
        for (BoundSheetRecord hojaDelLibro : BoundSheetRecord.orderByBofPosition(hojasDelLibro)) {
            lista.add(hojaDelLibro.getSheetname());
        }
        return lista;
    }

    /** Lo que trae el libro antes de la primera hoja: nombres de hojas, cadenas compartidas, formatos y estilos. */
    private void globales(Record registro) {
        if (registro instanceof BoundSheetRecord boundSheet) {
            hojasDelLibro.add(boundSheet);
        } else if (registro instanceof SSTRecord sst) {
            cadenas = sst;
        } else if (registro instanceof FormatRecord formato) {
            if (formatosPropios.size() >= MAXIMO_FORMATOS) {
                throw LimitesDeLista.demasiadoGrande();
            }
            formatosPropios.put(formato.getIndexCode(), formato.getFormatString());
        } else if (registro instanceof ExtendedFormatRecord estilo) {
            if (formatoDeEstilo.size() >= MAXIMO_ESTILOS) {
                throw LimitesDeLista.demasiadoGrande();
            }
            formatoDeEstilo.add(estilo.getFormatIndex() & 0xFFFF);
        }
    }

    /** Las celdas con valor; las en blanco (BLANK y MULBLANK) no llegan a mirarse. */
    private void celda(Record registro) {
        if (registro instanceof NumberRecord numero) {
            agregar(numero.getRow(), columna(numero.getColumn()), numerica(numero.getValue(), numero.getXFIndex()));
        } else if (registro instanceof RKRecord rk) {
            agregar(rk.getRow(), columna(rk.getColumn()), numerica(rk.getRKNumber(), rk.getXFIndex()));
        } else if (registro instanceof MulRKRecord varios) {
            for (int i = 0; i < varios.getNumColumns(); i++) {
                agregar(varios.getRow(), columna(varios.getFirstColumn()) + i,
                        numerica(varios.getRKNumberAt(i), varios.getXFAt(i)));
            }
        } else if (registro instanceof LabelSSTRecord etiqueta) {
            agregar(etiqueta.getRow(), columna(etiqueta.getColumn()), textoCompartido(etiqueta.getSSTIndex()));
        } else if (registro instanceof LabelRecord etiqueta) {
            agregar(etiqueta.getRow(), columna(etiqueta.getColumn()), new Celda(etiqueta.getValue().strip(), null));
        } else if (registro instanceof BoolErrRecord booleano) {
            agregar(booleano.getRow(), columna(booleano.getColumn()), booleanoOError(booleano));
        } else if (registro instanceof FormulaRecord formula) {
            formula(formula);
        } else if (registro instanceof StringRecord texto && formulaConTexto != null) {
            FormulaRecord formula = formulaConTexto;
            formulaConTexto = null;
            agregar(formula.getRow(), columna(formula.getColumn()), new Celda(texto.getString().strip(), null));
        }
    }

    /** Una formula vale su resultado guardado: numero, booleano, error o el texto del StringRecord que sigue. */
    private void formula(FormulaRecord formula) {
        CellType tipo = formula.getCachedResultTypeEnum();
        switch (tipo) {
            case NUMERIC -> agregar(formula.getRow(), columna(formula.getColumn()),
                    numerica(formula.getValue(), formula.getXFIndex()));
            case BOOLEAN -> agregar(formula.getRow(), columna(formula.getColumn()),
                    new Celda(formula.getCachedBooleanValue() ? "TRUE" : "FALSE", null));
            case ERROR -> agregar(formula.getRow(), columna(formula.getColumn()),
                    new Celda(FormulaError.forInt((byte) formula.getCachedErrorValue()).getString(), null));
            case STRING -> formulaConTexto = formula;
            default -> {
            }
        }
    }

    private Celda textoCompartido(int indice) {
        if (cadenas == null || indice < 0 || indice >= cadenas.getNumUniqueStrings()) {
            throw new IllegalStateException("La celda apunta a una cadena que no existe");
        }
        return new Celda(cadenas.getString(indice).getString().strip(), null);
    }

    private static Celda booleanoOError(BoolErrRecord registro) {
        if (registro.isBoolean()) {
            return new Celda(registro.getBooleanValue() ? "TRUE" : "FALSE", null);
        }
        return new Celda(FormulaError.forInt(registro.getErrorValue()).getString(), null);
    }

    private Celda numerica(double valor, int estilo) {
        String texto = NumberToTextConverter.toText(valor);
        boolean porcentaje = conFormatoDePorcentaje(estilo);
        try {
            return new Celda(texto, new BigDecimal(texto), porcentaje);
        } catch (NumberFormatException e) {
            return new Celda(texto, null);
        }
    }

    /** El formato del estilo de la celda tiene un %: los propios del libro mandan sobre los de fabrica. */
    private boolean conFormatoDePorcentaje(int estilo) {
        if (estilo < 0 || estilo >= formatoDeEstilo.size()) {
            return false;
        }
        int formato = formatoDeEstilo.get(estilo);
        String patron = formatosPropios.getOrDefault(formato, BuiltinFormats.getBuiltinFormat(formato));
        return patron != null && patron.contains("%");
    }

    // ---- filas ----

    private void agregar(int fila, int columna, Celda celda) {
        if (celda.vacia()) {
            return;
        }
        if (fila != numeroDeFila) {
            terminarFila();
            numeroDeFila = fila;
            celdas = new TreeMap<>();
        }
        if (columna >= LimitesDeLista.MAXIMO_COLUMNAS) {
            throw LimitesDeLista.demasiadasColumnas();
        }
        textoRetenido += celda.texto().length();
        if (textoRetenido > LibroXlsx.MAXIMO_TEXTO_RETENIDO) {
            throw LimitesDeLista.demasiadoGrande();
        }
        celdas.put(columna, celda);
    }

    private void terminarFila() {
        if (celdas == null) {
            return;
        }
        List<Celda> lista = new ArrayList<>();
        int ancho = celdas.isEmpty() ? 0 : celdas.lastKey() + 1;
        tope.sumarFila(ancho);
        for (Map.Entry<Integer, Celda> entrada : celdas.entrySet()) {
            while (lista.size() < entrada.getKey()) {
                lista.add(Celda.VACIA);
            }
            lista.add(entrada.getValue());
        }
        FilaLeida fila = new FilaLeida(hoja, numeroDeFila + 1, lista);
        celdas = null;
        if (!fila.vacia()) {
            // Tope grueso: no se sigue armando la lista una vez pasado.
            if (filas.size() >= LimitesDeLista.MAXIMO_FILAS_LEIDAS) {
                throw LimitesDeLista.demasiadasFilas();
            }
            filas.add(fila);
        }
    }
}
