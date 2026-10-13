package com.ferreteria.catalogo.service.lectura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.catalogo.model.Celda;
import com.ferreteria.catalogo.model.FilaLeida;
import com.ferreteria.catalogo.model.LecturaLista;
import com.ferreteria.common.exception.BadRequestException;
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
        LecturaLista lectura = lector.leer(ListasDePrueba.csv(LISTA, separador, StandardCharsets.UTF_8, false), 0);

        assertThat(lectura.filas()).hasSize(3);
        assertThat(textos(lectura.filas().get(1))).containsExactly("A-1", "Tornillo", "12,50");
    }

    @Test
    void elMismoContenidoConPuntoYComaYConComaDaLaMismaLectura() {
        LecturaLista conPuntoYComa = lector.leer(ListasDePrueba.csv(LISTA, ';', StandardCharsets.UTF_8, false), 0);
        LecturaLista conComa = lector.leer(ListasDePrueba.csv(LISTA, ',', StandardCharsets.UTF_8, false), 0);

        assertThat(conComa).isEqualTo(conPuntoYComa);
    }

    @Test
    void elDelimitadorSeCuentaFueraDeComillas() {
        // La primera linea tiene 3 comas entre comillas y 1 punto y coma afuera: gana el punto y coma.
        LecturaLista lectura = lector.leer(bytes("\"a,b,c,d\";x\n1;2\n"), 0);

        assertThat(textos(lectura.filas().get(0))).containsExactly("a,b,c,d", "x");
    }

    @Test
    void ceroDelimitadoresOEmpateUsanPuntoYComa() {
        LecturaLista sinDelimitador = lector.leer(bytes("unica\nsegunda\n"), 0);
        LecturaLista empate = lector.leer(bytes("a;b,c\n1;2,3\n"), 0);

        assertThat(textos(sinDelimitador.filas().get(0))).containsExactly("unica");
        assertThat(textos(empate.filas().get(0))).containsExactly("a", "b,c");
    }

    @Test
    void usaLaPrimeraLineaNoVaciaParaDetectar() {
        LecturaLista lectura = lector.leer(bytes("\n\nCodigo,Precio\nA-1,10\n"), 0);

        assertThat(lectura.filas()).extracting(FilaLeida::numero).containsExactly(3, 4);
        assertThat(textos(lectura.filas().get(1))).containsExactly("A-1", "10");
    }

    @Test
    void quitaElBomDelPrimerEncabezado() {
        LecturaLista lectura = lector.leer(ListasDePrueba.csv(LISTA, ';', StandardCharsets.UTF_8, true), 0);

        assertThat(lectura.filas().get(0).celda(0).texto()).isEqualTo("Codigo");
    }

    @Test
    void leeUtf8EstrictoYCaeAWindows1252() {
        List<List<String>> filas = List.of(List.of("Código", "Descripción"), List.of("T-1", "Tuerca ñandú"), List.of("T-2", "Arandela ½"));

        LecturaLista utf8 = lector.leer(ListasDePrueba.csv(filas, ';', StandardCharsets.UTF_8, false), 0);
        LecturaLista win = lector.leer(ListasDePrueba.csv(filas, ';', Charset.forName("windows-1252"), false), 0);

        assertThat(textos(utf8.filas().get(1))).containsExactly("T-1", "Tuerca ñandú");
        assertThat(textos(win.filas().get(1))).containsExactly("T-1", "Tuerca ñandú");
        assertThat(textos(win.filas().get(2))).containsExactly("T-2", "Arandela ½");
        assertThat(win.filas().get(0).celda(0).texto()).isEqualTo("Código");
    }

    @Test
    void unCampoEntrecomilladoConComaEsUnSoloCampo() {
        LecturaLista lectura = lector.leer(bytes("Codigo,Precio\nA-1,\"1.234,56\"\n"), 0);

        assertThat(textos(lectura.filas().get(1))).containsExactly("A-1", "1.234,56");
    }

    @Test
    void recortaLosEspaciosDeLosCampos() {
        LecturaLista lectura = lector.leer(bytes("a;b\n  A-1  ;  Tuerca \n"), 0);

        assertThat(textos(lectura.filas().get(1))).containsExactly("A-1", "Tuerca");
    }

    @Test
    void conservaLosCerosALaIzquierda() {
        LecturaLista lectura = lector.leer(bytes("a;b\n00123;5\n"), 0);

        assertThat(lectura.filas().get(1).celda(0)).isEqualTo(new Celda("00123", null));
    }

    @Test
    void elNumeroDeFilaEsElNumeroDeRegistroYLasFilasVaciasSeIgnoran() {
        LecturaLista lectura = lector.leer(bytes("Codigo;Precio\n\nA-1;10\n;\nA-2;20\n"), 0);

        assertThat(lectura.filas()).extracting(FilaLeida::numero).containsExactly(1, 3, 5);
    }

    @Test
    void aceptaFinesDeLineaDeWindows() {
        LecturaLista lectura = lector.leer(bytes("a;b\r\n1;2\r\n3;4"), 0);

        assertThat(lectura.filas()).hasSize(3);
        assertThat(textos(lectura.filas().get(2))).containsExactly("3", "4");
    }

    @Test
    void unCampoEntrecomilladoPuedeTenerUnSaltoDeLinea() {
        LecturaLista lectura = lector.leer(bytes("a;b\n\"x\ny\";2\n"), 0);

        assertThat(lectura.filas()).hasSize(2);
        assertThat(lectura.filas().get(1).celda(0).texto()).isEqualTo("x\ny");
    }

    @Test
    void tieneUnaSolaHojaLlamadaCsv() {
        LecturaLista lectura = lector.leer(bytes("a;b\n"), 0);

        assertThat(lectura.hojas()).containsExactly("CSV");
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2, -1})
    void unaHojaDistintaDeCeroEsUn400(int hoja) {
        assertThatThrownBy(() -> lector.leer(bytes("a;b\n"), hoja))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("La hoja elegida no existe en el archivo.");
    }

    @Test
    void comillasMalCerradasSonUn400() {
        assertThatThrownBy(() -> lector.leer(bytes("a;b\n\"sin cerrar;2\n3;4\n"), 0))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("No se pudo leer el CSV. Revisá las comillas.");
    }

    @Test
    void unArchivoVacioDaCeroFilas() {
        LecturaLista lectura = lector.leer(new byte[0], 0);

        assertThat(lectura.hojas()).containsExactly("CSV");
        assertThat(lectura.filas()).isEmpty();
    }
}
