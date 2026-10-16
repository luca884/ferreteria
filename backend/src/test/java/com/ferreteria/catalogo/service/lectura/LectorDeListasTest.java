package com.ferreteria.catalogo.service.lectura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.catalogo.model.FilaLeida;
import com.ferreteria.catalogo.model.LecturaLista;
import com.ferreteria.common.exception.BadRequestException;
import com.ferreteria.support.ListasDePrueba;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class LectorDeListasTest {

    private final LectorDeListas lector = new LectorDeListas(new LectorExcel(), new LectorCsv());

    private static final String MENSAJE_LIMITE = "La lista tiene más de 20.000 filas. Dividila en archivos más chicos.";

    private static List<List<String>> filasCsv(int datos) {
        List<List<String>> filas = new ArrayList<>();
        filas.add(List.of("Codigo", "Precio"));
        IntStream.rangeClosed(1, datos).forEach(i -> filas.add(List.of("C-" + i, "10")));
        return filas;
    }

    @Test
    void unXlsxSeLeeComoXlsxAunqueSeLlameCsv() {
        // La fachada solo ve bytes: el nombre y la extension no se miran.
        byte[] xlsx = ListasDePrueba.xlsx(List.of(java.util.Arrays.asList("A-1", 10)));

        LecturaLista lectura = lector.leer(xlsx, 0);

        assertThat(lectura.hojas()).containsExactly("Hoja1");
        assertThat(lectura.filas().get(0).celda(1).numero()).isEqualByComparingTo("10");
    }

    @Test
    void unXlsSeLeeComoXls() {
        byte[] xls = ListasDePrueba.xls(List.of(java.util.Arrays.asList("A-1", 10)));

        assertThat(lector.leer(xls, 0).hojas()).containsExactly("Hoja1");
    }

    @Test
    void unCsvSeLeeComoCsvAunqueSeLlameXlsx() {
        byte[] csv = ListasDePrueba.csv(List.of(List.of("A-1", "10")), ';', StandardCharsets.UTF_8, false);

        LecturaLista lectura = lector.leer(csv, 0);

        assertThat(lectura.hojas()).containsExactly("CSV");
        assertThat(lectura.filas().get(0).celda(0).texto()).isEqualTo("A-1");
    }

    @Test
    void procesaVeinteMilFilasDeDatos() {
        byte[] csv = ListasDePrueba.csv(filasCsv(20_000), ';', StandardCharsets.UTF_8, false);

        assertThat(lector.leer(csv, 0).filas()).hasSize(20_001);
    }

    /** Titulos, encabezado y despues las filas de datos: el lector no cuenta desde la primera fila de datos. */
    private static List<List<String>> filasCsvConTitulos(int titulos, int datos) {
        List<List<String>> filas = new ArrayList<>();
        IntStream.rangeClosed(1, titulos).forEach(i -> filas.add(List.of("Titulo " + i)));
        filas.add(List.of("Codigo", "Precio"));
        IntStream.rangeClosed(1, datos).forEach(i -> filas.add(List.of("C-" + i, "10")));
        return filas;
    }

    private static List<List<Object>> filasExcelConTitulos(int titulos, int datos) {
        List<List<Object>> filas = new ArrayList<>();
        IntStream.rangeClosed(1, titulos).forEach(i -> filas.add(List.of("Titulo " + i)));
        filas.add(List.of("Codigo", "Precio"));
        IntStream.rangeClosed(1, datos).forEach(i -> filas.add(List.of("C-" + i, i)));
        return filas;
    }

    @Test
    void tresTitulosElEncabezadoYVeinteMilFilasDeDatosSeLeenEnCsv() {
        byte[] csv = ListasDePrueba.csv(filasCsvConTitulos(3, 20_000), ';', StandardCharsets.UTF_8, false);

        assertThat(lector.leer(csv, 0).filas()).hasSize(20_004);
    }

    @Test
    void tresTitulosElEncabezadoYVeinteMilFilasDeDatosSeLeenEnExcel() {
        byte[] xlsx = ListasDePrueba.xlsx(filasExcelConTitulos(3, 20_000));

        assertThat(lector.leer(xlsx, 0).filas()).hasSize(20_004);
    }

    @Test
    void muchasMasDeVeinteMilFilasEsUn400EnCsv() {
        byte[] csv = ListasDePrueba.csv(filasCsv(25_000), ';', StandardCharsets.UTF_8, false);

        assertThatThrownBy(() -> lector.leer(csv, 0))
            .isInstanceOf(BadRequestException.class)
            .hasMessage(MENSAJE_LIMITE);
    }

    @Test
    void muchasMasDeVeinteMilFilasEsUn400EnExcel() {
        List<List<Object>> filas = new ArrayList<>();
        filas.add(List.of("Codigo", "Precio"));
        IntStream.rangeClosed(1, 25_000).forEach(i -> filas.add(List.of("C-" + i, i)));

        assertThatThrownBy(() -> lector.leer(ListasDePrueba.xlsx(filas), 0))
            .isInstanceOf(BadRequestException.class)
            .hasMessage(MENSAJE_LIMITE);
    }

    @Test
    void elCsvDejaDeLeerAlPasarElTopeYNoMiraLoQueSigue() {
        // Pasado el tope no se parsea mas: una comilla sin cerrar al final no llega a dar "revisa las comillas".
        String conComillaSinCerrar = String.join("\n", filasCsvComoLineas(25_000)) + "\n\"sin cerrar;1\n";
        byte[] csv = conComillaSinCerrar.getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> lector.leer(csv, 0))
            .isInstanceOf(BadRequestException.class)
            .hasMessage(MENSAJE_LIMITE);
    }

    private static List<String> filasCsvComoLineas(int datos) {
        List<String> lineas = new ArrayList<>();
        lineas.add("Codigo;Precio");
        IntStream.rangeClosed(1, datos).forEach(i -> lineas.add("C-" + i + ";10"));
        return lineas;
    }

    @Test
    void unaHojaInexistenteEsUn400() {
        byte[] xlsx = ListasDePrueba.xlsx(List.of(List.of("a")));
        byte[] csv = ListasDePrueba.csv(List.of(List.of("a")), ';', StandardCharsets.UTF_8, false);

        assertThatThrownBy(() -> lector.leer(xlsx, 3))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("La hoja elegida no existe en el archivo.");
        assertThatThrownBy(() -> lector.leer(csv, 1))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("La hoja elegida no existe en el archivo.");
    }

    @Test
    void unArchivoDeCeroBytesEsUn400() {
        assertThatThrownBy(() -> lector.leer(new byte[0], 0))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("No se pudo leer el archivo. Verificá que no esté dañado.");
    }

    @Test
    void unPdfSeRechazaConSuMensaje() {
        assertThatThrownBy(() -> lector.leer("%PDF-1.7 contenido".getBytes(StandardCharsets.UTF_8), 0))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("Las listas en PDF todavía no se pueden importar.");
    }

    @Test
    void unZipQueNoEsXlsxEsUn400() {
        assertThatThrownBy(() -> lector.leer(ListasDePrueba.docx(), 0))
            .isInstanceOf(BadRequestException.class);
    }

    // ---- corte 2: todas las hojas, elegidas por tipo de archivo, y el tope grueso de 25.000 filas ----

    private static Map<String, List<List<Object>>> tresHojas(int filasPrimera, int filasSegunda, int filasTercera) {
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put("UNO", filasExcel("U", filasPrimera));
        hojas.put("DOS", filasExcel("D", filasSegunda));
        hojas.put("TRES", filasExcel("T", filasTercera));
        return hojas;
    }

    private static List<List<Object>> filasExcel(String prefijo, int cantidad) {
        List<List<Object>> filas = new ArrayList<>();
        IntStream.rangeClosed(1, cantidad).forEach(i -> filas.add(List.of(prefijo + "-" + i, i)));
        return filas;
    }

    @Test
    void sinHojaUnXlsxSeLeeEnteroConSusHojas() {
        LecturaLista lectura = lector.leer(ListasDePrueba.xlsxHojas(tresHojas(2, 1, 3)));

        assertThat(lectura.hojas()).containsExactly("UNO", "DOS", "TRES");
        assertThat(lectura.filas()).extracting(FilaLeida::hoja).containsExactly(0, 0, 1, 2, 2, 2);
    }

    @Test
    void sinHojaUnXlsSeLeeEnteroConSusHojas() {
        LecturaLista lectura = lector.leer(ListasDePrueba.xlsHojas(tresHojas(1, 2, 1)));

        assertThat(lectura.hojas()).containsExactly("UNO", "DOS", "TRES");
        assertThat(lectura.filas()).extracting(FilaLeida::hoja).containsExactly(0, 1, 1, 2);
    }

    @Test
    void sinHojaUnCsvEsUnaSolaHojaLaCero() {
        byte[] csv = ListasDePrueba.csv(List.of(List.of("A-1", "10"), List.of("A-2", "20")), ';',
            StandardCharsets.UTF_8, false);

        LecturaLista lectura = lector.leer(csv);

        assertThat(lectura.hojas()).containsExactly("CSV");
        assertThat(lectura.filas()).extracting(FilaLeida::hoja).containsOnly(0);
        assertThat(lectura.filas()).hasSize(2);
    }

    @Test
    void sinHojaUnPdfSeRechazaConSuMensaje() {
        assertThatThrownBy(() -> lector.leer("%PDF-1.7 contenido".getBytes(StandardCharsets.UTF_8)))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("Las listas en PDF todavía no se pueden importar.");
    }

    @Test
    void sinHojaUnArchivoVacioOUnZipQueNoEsXlsxEsUn400() {
        assertThatThrownBy(() -> lector.leer(new byte[0]))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("No se pudo leer el archivo. Verificá que no esté dañado.");
        assertThatThrownBy(() -> lector.leer(ListasDePrueba.docx())).isInstanceOf(BadRequestException.class);
    }

    @Test
    void elTopeGruesoSeCuentaPorLibroEs25000() {
        assertThat(LimitesDeLista.MAXIMO_FILAS_LEIDAS).isEqualTo(25_000);
        assertThat(LimitesDeLista.MAXIMO_FILAS_DE_DATOS).isEqualTo(20_000);
    }

    @Test
    void unCsvDe25000FilasLeidasPasaYUnoDe25001EsUn400() {
        // Con el encabezado: 1 + 24.999 = 25.000 filas leidas; con una mas, 25.001.
        byte[] justo = ListasDePrueba.csv(filasCsv(24_999), ';', StandardCharsets.UTF_8, false);
        byte[] unaMas = ListasDePrueba.csv(filasCsv(25_000), ';', StandardCharsets.UTF_8, false);

        assertThat(lector.leer(justo).filas()).hasSize(25_000);
        assertThatThrownBy(() -> lector.leer(unaMas))
            .isInstanceOf(BadRequestException.class)
            .hasMessage(MENSAJE_LIMITE);
    }

    @Test
    void elTopeDeUnLibroDeExcelSumaTodasLasHojas() {
        // 3 hojas que suman 25.000 filas pasan; con una fila mas en cualquiera, 25.001, es un 400.
        byte[] justo = ListasDePrueba.xlsxHojas(tresHojas(8_334, 8_333, 8_333));
        byte[] unaMas = ListasDePrueba.xlsxHojas(tresHojas(8_334, 8_334, 8_333));

        assertThat(lector.leer(justo).filas()).hasSize(25_000);
        assertThatThrownBy(() -> lector.leer(unaMas))
            .isInstanceOf(BadRequestException.class)
            .hasMessage(MENSAJE_LIMITE);
    }
}
