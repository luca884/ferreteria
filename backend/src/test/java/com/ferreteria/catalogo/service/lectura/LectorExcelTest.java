package com.ferreteria.catalogo.service.lectura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.catalogo.model.Celda;
import com.ferreteria.catalogo.model.FilaLeida;
import com.ferreteria.catalogo.model.LecturaLista;
import com.ferreteria.common.exception.BadRequestException;
import com.ferreteria.support.ListasDePrueba;
import com.ferreteria.support.ListasDePrueba.Formula;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.apache.poi.openxml4j.util.ZipSecureFile;
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
        // Una lista legitima de 10 MB (comprimida) tiene que seguir entrando: al menos 10 veces su tamano.
        assertThat(LimitesDeLista.MAXIMO_ENTRADA_ZIP_BYTES).isGreaterThanOrEqualTo(10L * 10 * 1024 * 1024);
    }

    @Test
    void unXlsxProtegidoConContrasenaEsUn400() {
        byte[] protegido = ListasDePrueba.xlsxProtegido(List.of(fila("a", 1)), "secreto");

        assertThatThrownBy(() -> lector.leer(protegido, 0))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("El archivo está protegido con contraseña.");
    }
}
