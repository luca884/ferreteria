package com.ferreteria.catalogo.service.lectura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.catalogo.model.Celda;
import com.ferreteria.catalogo.model.FilaLeida;
import com.ferreteria.catalogo.model.LecturaLista;
import com.ferreteria.common.csv.EscritorCsv;
import com.ferreteria.common.exception.BadRequestException;
import java.math.BigDecimal;
import java.util.Arrays;
import com.ferreteria.support.ListasDePrueba;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LectorCsvTest {

    private final LectorCsv lector = new LectorCsv();

    private static final List<List<String>> LISTA = List.of(
        List.of("Codigo", "Descripcion", "Precio"),
        List.of("A-1", "Tornillo", "12,50"),
        List.of("A-2", "Tuerca", "100"));

    private static byte[] bytes(String texto) {
        return texto.getBytes(StandardCharsets.UTF_8);
    }

    private static List<String> textos(FilaLeida fila) {
        return fila.celdas().stream().map(Celda::texto).toList();
    }

    @ParameterizedTest
    @ValueSource(chars = {';', ',', '\t'})
    void detectaElDelimitador(char separador) {
        LecturaLista lectura = lector.leer(ListasDePrueba.csv(LISTA, separador, StandardCharsets.UTF_8, false));

        assertThat(lectura.filas()).hasSize(3);
        assertThat(textos(lectura.filas().get(1))).containsExactly("A-1", "Tornillo", "12,50");
    }

    @Test
    void elMismoContenidoConPuntoYComaYConComaDaLaMismaLectura() {
        LecturaLista conPuntoYComa = lector.leer(ListasDePrueba.csv(LISTA, ';', StandardCharsets.UTF_8, false));
        LecturaLista conComa = lector.leer(ListasDePrueba.csv(LISTA, ',', StandardCharsets.UTF_8, false));

        assertThat(conComa).isEqualTo(conPuntoYComa);
    }

    @Test
    void elDelimitadorSeCuentaFueraDeComillas() {
        // La primera linea tiene 3 comas entre comillas y 1 punto y coma afuera: gana el punto y coma.
        LecturaLista lectura = lector.leer(bytes("\"a,b,c,d\";x\n1;2\n"));

        assertThat(textos(lectura.filas().get(0))).containsExactly("a,b,c,d", "x");
    }

    @Test
    void ceroDelimitadoresOEmpateUsanPuntoYComa() {
        LecturaLista sinDelimitador = lector.leer(bytes("unica\nsegunda\n"));
        LecturaLista empate = lector.leer(bytes("a;b,c\n1;2,3\n"));

        assertThat(textos(sinDelimitador.filas().get(0))).containsExactly("unica");
        assertThat(textos(empate.filas().get(0))).containsExactly("a", "b,c");
    }

    @Test
    void usaLaPrimeraLineaNoVaciaParaDetectar() {
        LecturaLista lectura = lector.leer(bytes("\n\nCodigo,Precio\nA-1,10\n"));

        assertThat(lectura.filas()).extracting(FilaLeida::numero).containsExactly(3, 4);
        assertThat(textos(lectura.filas().get(1))).containsExactly("A-1", "10");
    }

    @Test
    void quitaElBomDelPrimerEncabezado() {
        LecturaLista lectura = lector.leer(ListasDePrueba.csv(LISTA, ';', StandardCharsets.UTF_8, true));

        assertThat(lectura.filas().get(0).celda(0).texto()).isEqualTo("Codigo");
    }

    @Test
    void leeUtf8EstrictoYCaeAWindows1252() {
        List<List<String>> filas = List.of(List.of("Código", "Descripción"), List.of("T-1", "Tuerca ñandú"), List.of("T-2", "Arandela ½"));

        LecturaLista utf8 = lector.leer(ListasDePrueba.csv(filas, ';', StandardCharsets.UTF_8, false));
        LecturaLista win = lector.leer(ListasDePrueba.csv(filas, ';', Charset.forName("windows-1252"), false));

        assertThat(textos(utf8.filas().get(1))).containsExactly("T-1", "Tuerca ñandú");
        assertThat(textos(win.filas().get(1))).containsExactly("T-1", "Tuerca ñandú");
        assertThat(textos(win.filas().get(2))).containsExactly("T-2", "Arandela ½");
        assertThat(win.filas().get(0).celda(0).texto()).isEqualTo("Código");
    }

    @Test
    void unCampoEntrecomilladoConComaEsUnSoloCampo() {
        LecturaLista lectura = lector.leer(bytes("Codigo,Precio\nA-1,\"1.234,56\"\n"));

        assertThat(textos(lectura.filas().get(1))).containsExactly("A-1", "1.234,56");
    }

    @Test
    void unaCeldaComoTextoDeExcelSeLeeComoSuTexto() {
        // La exportacion de articulos escribe los codigos asi, para que Excel no les saque los ceros (GDS-19).
        LecturaLista lectura = lector.leer(bytes("Codigo;Barras;Prov;Desc\n=\"0012\";=\"AB\"\"C\";\"=\"\"a;b\"\"\";=\"\";fin\n"));

        assertThat(textos(lectura.filas().get(1))).containsExactly("0012", "AB\"C", "a;b", "", "fin");
    }

    @Test
    void loQueEscribeLaExportacionSeVuelveALeerIgual() {
        byte[] exportado = EscritorCsv.escribir(List.of("Código", "Barras", "Descripción", "Costo"), List.of(
            Arrays.asList(EscritorCsv.codigo("0012"), EscritorCsv.codigo("7790001234567"), "Caño 1/2\"; largo",
                new BigDecimal("1234.50")),
            Arrays.asList(EscritorCsv.codigo("A\"B;C"), EscritorCsv.codigo(null), "Tuerca", new BigDecimal("-3"))));

        LecturaLista lectura = lector.leer(exportado);

        assertThat(textos(lectura.filas().get(0))).containsExactly("Código", "Barras", "Descripción", "Costo");
        assertThat(textos(lectura.filas().get(1))).containsExactly("0012", "7790001234567", "Caño 1/2\"; largo", "1234,50");
        assertThat(textos(lectura.filas().get(2))).containsExactly("A\"B;C", "", "Tuerca", "-3");
    }

    @Test
    void elTextoDeExcelSirveEnCualquierColumnaYEnWindows1252() {
        byte[] win = "Codigo;Desc;Precio\n=\"0012\";Caño;=\"1.234,56\"\n".getBytes(Charset.forName("windows-1252"));

        assertThat(textos(lector.leer(win).filas().get(1))).containsExactly("0012", "Caño", "1.234,56");
    }

    @Test
    void unaFilaSoloDeTextosDeExcelVaciosCuentaComoVaciaYLaSiguienteConservaSuNumero() {
        LecturaLista lectura = lector.leer(bytes("a;b\n=\"\";=\"\"\nc;d\n"));

        assertThat(lectura.filas()).extracting(FilaLeida::numero).containsExactly(1, 3);
    }

    @Test
    void loQueEscribeLaExportacionVuelveIgualAunConComillasYFormulas() {
        List<String> codigos = List.of("\"", "\"\"", "=\"\"", "=\"x\"", "x\"&HYPERLINK(\"http://e\")&\"", "'0012", "a,b");
        byte[] exportado = EscritorCsv.escribir(List.of("Codigo"),
            codigos.stream().map(c -> List.of(EscritorCsv.codigo(c))).toList());

        assertThat(lector.leer(exportado).filas().subList(1, codigos.size() + 1)).extracting(f -> f.celdas().get(0).texto())
            .containsExactlyElementsOf(codigos);
    }

    @Test
    void loQueNoEsTextoDeExcelQuedaComoEsta() {
        LecturaLista lectura = lector.leer(bytes("a;b;c;d;e;f;g\n=1+1;'=\"x\";=\"abierto;x=\"y\";\"=\"\"\";=\"a\"b\";=\"a\"&\"b\"\n"));

        assertThat(textos(lectura.filas().get(1)))
            .containsExactly("=1+1", "'=\"x\"", "=\"abierto", "x=\"y\"", "=\"", "=\"a\"b\"", "=\"a\"&\"b\"");
    }

    @Test
    void recortaLosEspaciosDeLosCampos() {
        LecturaLista lectura = lector.leer(bytes("a;b\n  A-1  ;  Tuerca \n"));

        assertThat(textos(lectura.filas().get(1))).containsExactly("A-1", "Tuerca");
    }

    @Test
    void conservaLosCerosALaIzquierda() {
        LecturaLista lectura = lector.leer(bytes("a;b\n00123;5\n"));

        assertThat(lectura.filas().get(1).celda(0)).isEqualTo(new Celda("00123", null));
    }

    @Test
    void elNumeroDeFilaEsElNumeroDeRegistroYLasFilasVaciasSeIgnoran() {
        LecturaLista lectura = lector.leer(bytes("Codigo;Precio\n\nA-1;10\n;\nA-2;20\n"));

        assertThat(lectura.filas()).extracting(FilaLeida::numero).containsExactly(1, 3, 5);
    }

    @Test
    void aceptaFinesDeLineaDeWindows() {
        LecturaLista lectura = lector.leer(bytes("a;b\r\n1;2\r\n3;4"));

        assertThat(lectura.filas()).hasSize(3);
        assertThat(textos(lectura.filas().get(2))).containsExactly("3", "4");
    }

    @Test
    void unCampoEntrecomilladoPuedeTenerUnSaltoDeLinea() {
        LecturaLista lectura = lector.leer(bytes("a;b\n\"x\ny\";2\n"));

        assertThat(lectura.filas()).hasSize(2);
        assertThat(lectura.filas().get(1).celda(0).texto()).isEqualTo("x\ny");
    }

    @Test
    void tieneUnaSolaHojaLlamadaCsv() {
        LecturaLista lectura = lector.leer(bytes("a;b\n"));

        assertThat(lectura.hojas()).containsExactly("CSV");
    }

    @Test
    void comillasMalCerradasSonUn400() {
        assertThatThrownBy(() -> lector.leer(bytes("a;b\n\"sin cerrar;2\n3;4\n")))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("No se pudo leer el CSV. Revisá las comillas.");
    }

    @Test
    void unArchivoVacioDaCeroFilas() {
        LecturaLista lectura = lector.leer(new byte[0]);

        assertThat(lectura.hojas()).containsExactly("CSV");
        assertThat(lectura.filas()).isEmpty();
    }

    // ---- corte 2: una sola hoja, la 0, sin pedirla ----

    @Test
    void leerSinHojaDevuelveElCsvComoUnaSolaHojaLaCero() {
        LecturaLista lectura = lector.leer(ListasDePrueba.csv(LISTA, ';', StandardCharsets.UTF_8, false));

        assertThat(lectura.hojas()).containsExactly("CSV");
        assertThat(lectura.filas()).hasSize(3);
        assertThat(lectura.filas()).extracting(FilaLeida::hoja).containsOnly(0);
        assertThat(lectura.filas()).extracting(FilaLeida::numero).containsExactly(1, 2, 3);
        assertThat(textos(lectura.filas().get(2))).containsExactly("A-2", "Tuerca", "100");
    }

    // ---- tope de columnas y de celdas (hallazgo 4 de la revision del 01/11) ----

    private static final String MENSAJE_COLUMNAS =
        "La lista tiene datos en más de 200 columnas. Dejá solo las columnas que usás.";
    private static final String MENSAJE_GRANDE =
        "La lista es demasiado grande para leerla. Dividila en archivos más chicos.";

    private static String fila(int columnas) {
        return String.join(";", java.util.Collections.nCopies(columnas, "1"));
    }

    @Test
    void doscientasColumnasConDatosSeLeen() {
        LecturaLista lectura = lector.leer(bytes(fila(200) + "\n"));

        assertThat(lectura.filas().get(0).celdas()).hasSize(200);
    }

    @Test
    void unDatoEnLaColumna201RechazaLaLista() {
        assertThatThrownBy(() -> lector.leer(bytes("a;b\n" + fila(201) + "\n")))
            .isInstanceOf(BadRequestException.class)
            .hasMessage(MENSAJE_COLUMNAS);
    }

    @Test
    void unaLineaDeVeinticincoMegasDeSeparadoresSeRechazaSinLeerla() {
        String ataque = "a;".repeat(12_500_000);

        assertThatThrownBy(() -> lector.leer(bytes(ataque)))
            .isInstanceOf(BadRequestException.class)
            .hasMessage(MENSAJE_COLUMNAS);
    }

    @Test
    void lasColumnasVaciasDelFinalSeIgnoran() {
        LecturaLista lectura = lector.leer(bytes("a;b" + "; ".repeat(5_000) + "\n1;2" + ";".repeat(300) + "\n"));

        assertThat(lectura.filas()).hasSize(2);
        assertThat(textos(lectura.filas().get(0))).containsExactly("a", "b");
        assertThat(textos(lectura.filas().get(1))).containsExactly("1", "2");
        assertThat(lectura.filas()).extracting(FilaLeida::numero).containsExactly(1, 2);
    }

    @Test
    void losSeparadoresEntreComillasNoSonColumnas() {
        LecturaLista lectura = lector.leer(bytes("\"" + ";".repeat(500) + "\";b\n\"x\"\"" + ";\n".repeat(300) + "\";c\n"));

        assertThat(textos(lectura.filas().get(0))).containsExactly(";".repeat(500), "b");
        assertThat(textos(lectura.filas().get(1))).containsExactly(("x\"" + ";\n".repeat(300)).trim(), "c");
    }

    @Test
    void unaComillaEnMedioDelCampoNoEscondeLosSeparadores() {
        // Para el CSV una comilla solo abre comillas al principio del campo: aca es un caracter mas.
        assertThatThrownBy(() -> lector.leer(bytes("a\"b;" + fila(300) + "\n")))
            .isInstanceOf(BadRequestException.class)
            .hasMessage(MENSAJE_COLUMNAS);
    }

    @Test
    void unMillonDeCeldasSeLee() {
        String lista = (fila(200) + "\n").repeat(5_000);

        assertThat(lector.leer(bytes(lista)).filas()).hasSize(5_000);
    }

    @Test
    void masDeUnMillonDeCeldasRechazaLaLista() {
        String lista = (fila(200) + "\n").repeat(5_000) + "1\n";

        assertThatThrownBy(() -> lector.leer(bytes(lista)))
            .isInstanceOf(BadRequestException.class)
            .hasMessage(MENSAJE_GRANDE);
    }
}
