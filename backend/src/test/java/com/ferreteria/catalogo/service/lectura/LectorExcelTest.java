package com.ferreteria.catalogo.service.lectura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.catalogo.model.AlicuotaIva;
import com.ferreteria.catalogo.model.Celda;
import com.ferreteria.catalogo.model.FilaLeida;
import com.ferreteria.catalogo.model.LecturaLista;
import com.ferreteria.catalogo.model.ParserPorcentaje;
import com.ferreteria.catalogo.model.AlicuotaDeFila;
import com.ferreteria.common.exception.BadRequestException;
import com.ferreteria.support.ListasDePrueba;
import com.ferreteria.support.ListasDePrueba.ConFormato;
import com.ferreteria.support.ListasDePrueba.Formula;
import com.ferreteria.support.ListasReales;
import java.io.IOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.poi.openxml4j.util.ZipSecureFile;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.util.IOUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class LectorExcelTest {

    private final LectorExcel lector = new LectorExcel();

    private static final Map<String, Function<List<List<Object>>, byte[]>> FORMATOS = Map.of(
        "xlsx", ListasDePrueba::xlsx,
        "xls", ListasDePrueba::xls);

    private LecturaLista leer(String formato, List<List<Object>> filas) {
        return lector.leer(FORMATOS.get(formato).apply(filas), 0);
    }

    private static List<Object> fila(Object... valores) {
        return Arrays.asList(valores);
    }

    private static byte[] concatenar(byte[] a, byte[] b) {
        byte[] r = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, r, a.length, b.length);
        return r;
    }

    @Test
    void elMismoContenidoEnXlsxYXlsDaLoMismo() {
        List<List<Object>> filas = List.of(
            fila("Codigo", "Descripcion", "Precio"),
            fila("A-1", "Tornillo", 12.5),
            fila("00123", "Tuerca", 100));

        assertThat(leer("xlsx", filas)).isEqualTo(leer("xls", filas));
    }

    @ParameterizedTest
    @ValueSource(strings = {"xlsx", "xls"})
    void unaCeldaNumericaEnteraSeLeeComoTextoSinDecimales(String formato) {
        LecturaLista lectura = leer(formato, List.of(fila(123, 10000000, 12.5)));

        FilaLeida fila = lectura.filas().get(0);
        assertThat(fila.celda(0).texto()).isEqualTo("123");
        assertThat(fila.celda(0).numero()).isEqualByComparingTo("123");
        assertThat(fila.celda(1).texto()).isEqualTo("10000000");
        assertThat(fila.celda(1).numero()).isEqualByComparingTo("10000000");
        assertThat(fila.celda(2).texto()).isEqualTo("12.5");
        assertThat(fila.celda(2).numero()).isEqualByComparingTo("12.5");
    }

    @ParameterizedTest
    @ValueSource(strings = {"xlsx", "xls"})
    void elTextoConCerosALaIzquierdaSeConserva(String formato) {
        LecturaLista lectura = leer(formato, List.of(fila("00123", "  Tuerca  ")));

        FilaLeida fila = lectura.filas().get(0);
        assertThat(fila.celda(0)).isEqualTo(new Celda("00123", null));
        assertThat(fila.celda(1)).isEqualTo(new Celda("Tuerca", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"xlsx", "xls"})
    void unaFormulaUsaSuValorCacheadoSinRuidoDeDoble(String formato) {
        LecturaLista lectura = leer(formato, List.of(
            fila("Costo", "Con aumento", "Suma"),
            fila(100, new Formula("A2*1.1"), new Formula("0.1+0.2"))));

        FilaLeida fila = lectura.filas().get(1);
        assertThat(fila.celda(1).numero()).isEqualByComparingTo("110");
        assertThat(fila.celda(1).texto()).isEqualTo("110");
        assertThat(fila.celda(2).numero()).isEqualByComparingTo("0.3");
        assertThat(fila.celda(2).texto()).isEqualTo("0.3");
    }

    @ParameterizedTest
    @ValueSource(strings = {"xlsx", "xls"})
    void unaFormulaDeTextoSeLeeComoTexto(String formato) {
        LecturaLista lectura = leer(formato, List.of(fila(new Formula("\"AB\"&\"-1\""))));

        assertThat(lectura.filas().get(0).celda(0)).isEqualTo(new Celda("AB-1", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {"xlsx", "xls"})
    void unaFormulaConErrorSeLeeComoTextoYNoRompe(String formato) {
        LecturaLista lectura = leer(formato, List.of(fila(new Formula("1/0"))));

        Celda celda = lectura.filas().get(0).celda(0);
        assertThat(celda.numero()).isNull();
        assertThat(celda.texto()).isEqualTo("#DIV/0!");
    }

    @ParameterizedTest
    @ValueSource(strings = {"xlsx", "xls"})
    void ignoraFilasVaciasYConservaLosNumerosOriginales(String formato) {
        LecturaLista lectura = leer(formato, List.of(
            fila("Codigo", "Precio"),
            fila(),
            fila("A-1", 10),
            fila("  ", null),
            fila(),
            fila("A-2", 20)));

        assertThat(lectura.filas()).extracting(FilaLeida::numero).containsExactly(1, 3, 6);
        assertThat(lectura.filas().get(2).celda(0).texto()).isEqualTo("A-2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"xlsx", "xls"})
    void unaCeldaFueraDeRangoEsVacia(String formato) {
        LecturaLista lectura = leer(formato, List.of(fila("a", null, "c")));

        FilaLeida fila = lectura.filas().get(0);
        assertThat(fila.celda(1)).isEqualTo(Celda.VACIA);
        assertThat(fila.celda(9)).isEqualTo(Celda.VACIA);
    }

    @ParameterizedTest
    @ValueSource(strings = {"xlsx", "xls"})
    void leeLaHojaElegidaYInformaLosNombresDeTodas(String formato) {
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put("Portada", List.of(fila("nada")));
        hojas.put("Precios", List.of(fila("X-1", 5), fila("X-2", 6)));
        byte[] bytes = formato.equals("xlsx") ? ListasDePrueba.xlsxHojas(hojas) : ListasDePrueba.xlsHojas(hojas);

        LecturaLista segunda = lector.leer(bytes, 1);
        LecturaLista primera = lector.leer(bytes, 0);

        assertThat(segunda.hojas()).containsExactly("Portada", "Precios");
        assertThat(segunda.filas()).hasSize(2);
        assertThat(segunda.filas().get(0).celda(0).texto()).isEqualTo("X-1");
        assertThat(primera.filas()).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 5, -1})
    void unaHojaInexistenteEsUn400(int hoja) {
        byte[] bytes = ListasDePrueba.xlsx(List.of(fila("a")));

        assertThatThrownBy(() -> lector.leer(bytes, hoja))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("La hoja elegida no existe en el archivo.");
    }

    @Test
    void unArchivoCorruptoEsUn400NuncaUn500() {
        byte[] zipConBasura = concatenar(new byte[] {0x50, 0x4B, 0x03, 0x04}, "esto no es un zip valido".repeat(20).getBytes());
        byte[] oleConBasura = concatenar(
            new byte[] {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, 0x1A, (byte) 0xE1},
            "basura".repeat(50).getBytes());
        byte[] valido = ListasDePrueba.xlsx(List.of(fila("a", "b", "c")));
        byte[] truncado = Arrays.copyOf(valido, valido.length / 2);

        for (byte[] malo : List.of(zipConBasura, oleConBasura, truncado, new byte[0], "texto".getBytes())) {
            assertThatThrownBy(() -> lector.leer(malo, 0))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("No se pudo leer el archivo. Verificá que no esté dañado.");
        }
    }

    @Test
    void unZipQueNoEsUnXlsxComoUnDocxEsUn400() {
        assertThatThrownBy(() -> lector.leer(ListasDePrueba.docx(), 0))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("No se pudo leer el archivo. Verificá que no esté dañado.");
    }

    @Test
    void alCargarseElLectorPoiQuedaConLimitesConservadores() {
        new LectorExcel();

        // Los valores por defecto de POI son 4 GB por entrada y 100 MB por arreglo: demasiado para un servidor compartido.
        assertThat(ZipSecureFile.getMaxEntrySize()).isEqualTo(LimitesDeLista.MAXIMO_ENTRADA_ZIP_BYTES);
        assertThat(IOUtils.getByteArrayMaxOverride()).isEqualTo(LimitesDeLista.MAXIMO_ARREGLO_POI_BYTES);
        assertThat(LimitesDeLista.MAXIMO_ENTRADA_ZIP_BYTES).isLessThanOrEqualTo(256L * 1024 * 1024);
        // Una lista legitima de 25 MB (comprimida) tiene que seguir entrando: al menos 10 veces su tamano.
        assertThat(LimitesDeLista.MAXIMO_ENTRADA_ZIP_BYTES).isGreaterThanOrEqualTo(10L * 25 * 1024 * 1024);
    }

    @Test
    void unXlsxProtegidoConContrasenaEsUn400() {
        byte[] protegido = ListasDePrueba.xlsxProtegido(List.of(fila("a", 1)), "secreto");

        assertThatThrownBy(() -> lector.leer(protegido, 0))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("El archivo está protegido con contraseña.");
    }

    // ---- corte 2: todas las hojas con una sola apertura ----

    /** Un lector que cuenta cuantas veces se abre el libro. */
    private static final class LectorQueCuentaAperturas extends LectorExcel {
        int aperturas;

        @Override
        Workbook abrir(byte[] contenido) throws IOException {
            aperturas++;
            return super.abrir(contenido);
        }
    }

    private static byte[] libro(String formato, Map<String, List<List<Object>>> hojas) {
        return formato.equals("xlsx") ? ListasDePrueba.xlsxHojas(hojas) : ListasDePrueba.xlsHojas(hojas);
    }

    @ParameterizedTest
    @ValueSource(strings = {"xlsx", "xls"})
    void leerSinHojaDevuelveLasFilasDeTodasLasHojasConLaHojaDeCadaUna(String formato) {
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put("TORNILLOS", List.of(fila("T-1", 10), fila(), fila("T-2", 20)));
        hojas.put("TUERCAS", List.of(fila("U-1", 30)));
        hojas.put("ARANDELAS", List.of(fila("A-1", 40), fila("A-2", 50), fila("A-3", 60)));

        LecturaLista lectura = lector.leer(libro(formato, hojas));

        assertThat(lectura.hojas()).containsExactly("TORNILLOS", "TUERCAS", "ARANDELAS");
        assertThat(lectura.filas()).extracting(FilaLeida::hoja).containsExactly(0, 0, 1, 2, 2, 2);
        assertThat(lectura.filas()).extracting(FilaLeida::numero).containsExactly(1, 3, 1, 1, 2, 3);
        assertThat(lectura.filas()).extracting(f -> f.celda(0).texto())
            .containsExactly("T-1", "T-2", "U-1", "A-1", "A-2", "A-3");
    }

    @ParameterizedTest
    @ValueSource(strings = {"xlsx", "xls"})
    void abreElLibroUnaSolaVezAunqueTengaSetentaHojas(String formato) {
        LectorQueCuentaAperturas contador = new LectorQueCuentaAperturas();
        Map<String, List<List<Object>>> setentaHojas = ListasReales.lista336HojasFilas(70, 2);

        LecturaLista lectura = contador.leer(libro(formato, setentaHojas));

        assertThat(contador.aperturas).isEqualTo(1);
        assertThat(lectura.hojas()).hasSize(70);
        assertThat(lectura.filas()).hasSize(70 * 3);
        assertThat(lectura.filasDeHoja(69).get(1).celda(0).texto()).isEqualTo("H70-001");
        assertThat(lectura.filas().get(lectura.filas().size() - 1).hoja()).isEqualTo(69);
    }

    @ParameterizedTest
    @ValueSource(strings = {"xlsx", "xls"})
    void unaHojaVaciaNoRompeLaLectura(String formato) {
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put("Primera", List.of(fila("A-1", 1)));
        hojas.put("Vacia", List.of());
        hojas.put("Tercera", List.of(fila("C-1", 3)));

        LecturaLista lectura = lector.leer(libro(formato, hojas));

        assertThat(lectura.hojas()).containsExactly("Primera", "Vacia", "Tercera");
        assertThat(lectura.filas()).extracting(FilaLeida::hoja).containsExactly(0, 2);
    }

    @Test
    void leerConHojaFiltraLaSobrecargaVieja() {
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put("Portada", List.of(fila("nada")));
        hojas.put("Precios", List.of(fila("X-1", 5), fila("X-2", 6)));
        byte[] bytes = ListasDePrueba.xlsxHojas(hojas);

        LecturaLista segunda = lector.leer(bytes, 1);

        assertThat(segunda.hojas()).containsExactly("Portada", "Precios");
        assertThat(segunda.filas()).extracting(FilaLeida::hoja).containsOnly(1);
        assertThat(segunda.filas()).extracting(FilaLeida::numero).containsExactly(1, 2);
    }

    // ---- corte 2: Celda.porcentaje desde el formato de Excel ----

    @ParameterizedTest
    @ValueSource(strings = {"xlsx", "xls"})
    void unaCeldaNumericaConFormatoDePorcentajeLlegaConPorcentajeTrue(String formato) {
        LecturaLista lectura = lector.leer(libro(formato, Map.of("Hoja1", List.of(
            fila(new ConFormato(0.21, "0%"), new ConFormato(0.105, "0.00%"), new ConFormato(0.21, "0.00"), 21)))));

        FilaLeida fila = lectura.filas().get(0);
        assertThat(fila.celda(0).porcentaje()).isTrue();
        assertThat(fila.celda(0).numero()).isEqualByComparingTo("0.21");
        assertThat(fila.celda(1).porcentaje()).isTrue();
        assertThat(fila.celda(1).numero()).isEqualByComparingTo("0.105");
        // Con otro formato, o sin formato, no es un porcentaje.
        assertThat(fila.celda(2).porcentaje()).isFalse();
        assertThat(fila.celda(2).numero()).isEqualByComparingTo("0.21");
        assertThat(fila.celda(3).porcentaje()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"xlsx", "xls"})
    void unaCeldaDeTextoVeintiunoPorCientoLlegaConPorcentajeFalse(String formato) {
        LecturaLista lectura = lector.leer(libro(formato, Map.of("Hoja1", List.of(fila("21 %", "21%")))));

        assertThat(lectura.filas().get(0).celda(0).porcentaje()).isFalse();
        assertThat(lectura.filas().get(0).celda(0).numero()).isNull();
        assertThat(lectura.filas().get(0).celda(1).porcentaje()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"xlsx", "xls"})
    void elCeroVeintiunoConFormatoPorcentajeSeLeeComoVeintiunoYEsIva21(String formato) {
        LecturaLista lectura = lector.leer(libro(formato, Map.of("Hoja1", List.of(
            fila(new ConFormato(0.21, "0%"), new ConFormato(0.21, "0.00"))))));
        Celda conFormato = lectura.filas().get(0).celda(0);
        Celda sinFormato = lectura.filas().get(0).celda(1);

        assertThat(ParserPorcentaje.parsear(conFormato).valor()).isEqualByComparingTo("21");
        assertThat(AlicuotaDeFila.interpretar(conFormato, null, null).alicuota()).isEqualTo(AlicuotaIva.IVA_21);
        // El mismo 0,21 sin formato % no se adivina: no es una alicuota legal.
        assertThat(ParserPorcentaje.parsear(sinFormato).valor()).isEqualByComparingTo("0.21");
        assertThat(AlicuotaDeFila.interpretar(sinFormato, null, null).esError()).isTrue();
    }
}
