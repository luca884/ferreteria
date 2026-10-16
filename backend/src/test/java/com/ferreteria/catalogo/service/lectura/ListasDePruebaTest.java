package com.ferreteria.catalogo.service.lectura;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.support.ListasDePrueba;
import com.ferreteria.support.ListasDePrueba.Formula;
import java.io.ByteArrayInputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

class ListasDePruebaTest {

    private static final List<List<Object>> FILAS = List.of(
        List.of("Codigo", "Descripcion", "Precio"),
        List.of("A-1", "Tornillo", 12.5),
        List.of("A-2", "Tuerca", 100));

    @Test
    void unXlsxGeneradoSeReabreConWorkbookFactory() throws Exception {
        byte[] bytes = ListasDePrueba.xlsx(FILAS);

        assertThat(bytes).startsWith(0x50, 0x4B, 0x03, 0x04);
        try (Workbook libro = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet hoja = libro.getSheetAt(0);
            assertThat(hoja.getRow(1).getCell(0).getStringCellValue()).isEqualTo("A-1");
            assertThat(hoja.getRow(1).getCell(2).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(hoja.getRow(1).getCell(2).getNumericCellValue()).isEqualTo(12.5);
        }
    }

    @Test
    void unXlsGeneradoSeReabreConWorkbookFactory() throws Exception {
        byte[] bytes = ListasDePrueba.xls(FILAS);

        assertThat(bytes).startsWith(0xD0, 0xCF, 0x11, 0xE0);
        try (Workbook libro = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet hoja = libro.getSheetAt(0);
            assertThat(hoja.getRow(2).getCell(1).getStringCellValue()).isEqualTo("Tuerca");
            assertThat(hoja.getRow(2).getCell(2).getNumericCellValue()).isEqualTo(100d);
        }
    }

    @Test
    void variasHojasConNombre() throws Exception {
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put("Lista", FILAS);
        hojas.put("Otra", List.of(List.of("x")));

        try (Workbook libro = WorkbookFactory.create(new ByteArrayInputStream(ListasDePrueba.xlsxHojas(hojas)))) {
            assertThat(libro.getNumberOfSheets()).isEqualTo(2);
            assertThat(libro.getSheetName(0)).isEqualTo("Lista");
            assertThat(libro.getSheetName(1)).isEqualTo("Otra");
        }
    }

    @Test
    void lasFormulasSeEvaluanYQuedaElValorCacheado() throws Exception {
        List<List<Object>> filas = List.of(
            List.of("Costo", "Con aumento"),
            java.util.Arrays.asList(100, new Formula("A2*1.1")));

        try (Workbook libro = WorkbookFactory.create(new ByteArrayInputStream(ListasDePrueba.xlsx(filas)))) {
            var celda = libro.getSheetAt(0).getRow(1).getCell(1);
            assertThat(celda.getCellType()).isEqualTo(CellType.FORMULA);
            assertThat(celda.getCachedFormulaResultType()).isEqualTo(CellType.NUMERIC);
            assertThat(celda.getNumericCellValue()).isCloseTo(110d, org.assertj.core.data.Offset.offset(1e-9));
        }
    }

    @Test
    void unaCeldaNulaQuedaSinCrear() throws Exception {
        List<List<Object>> filas = List.of(java.util.Arrays.asList("a", null, "c"));

        try (Workbook libro = WorkbookFactory.create(new ByteArrayInputStream(ListasDePrueba.xls(filas)))) {
            assertThat(libro.getSheetAt(0).getRow(0).getCell(1)).isNull();
            assertThat(libro.getSheetAt(0).getRow(0).getCell(2).getStringCellValue()).isEqualTo("c");
        }
    }

    @Test
    void csvConBomYCharset() {
        List<List<String>> filas = List.of(List.of("Código", "Desc"), List.of("A-1", "Tuerca ñandú"));

        byte[] conBom = ListasDePrueba.csv(filas, ';', StandardCharsets.UTF_8, true);
        byte[] sinBom = ListasDePrueba.csv(filas, ';', StandardCharsets.UTF_8, false);

        assertThat(conBom).startsWith(0xEF, 0xBB, 0xBF);
        assertThat(sinBom[0]).isNotEqualTo((byte) 0xEF);
        assertThat(new String(sinBom, StandardCharsets.UTF_8)).isEqualTo("Código;Desc\nA-1;Tuerca ñandú\n");
        assertThat(conBom).hasSize(sinBom.length + 3);
    }

    @Test
    void csvEnWindows1252UsaUnByteParaLaEnie() {
        Charset win = Charset.forName("windows-1252");

        byte[] bytes = ListasDePrueba.csv(List.of(List.of("ñ")), ',', win, false);

        assertThat(bytes).containsExactly((byte) 0xF1, (byte) '\n');
    }

    @Test
    void csvEntrecomillaLosCamposConDelimitadorComillasOSaltos() {
        byte[] bytes = ListasDePrueba.csv(List.of(List.of("1.234,56", "a,b", "di\"jo", "x")), ',', StandardCharsets.UTF_8, false);

        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("\"1.234,56\",\"a,b\",\"di\"\"jo\",x\n");
    }

    @Test
    void csvConTabulador() {
        byte[] bytes = ListasDePrueba.csv(List.of(List.of("a", "b")), '\t', StandardCharsets.UTF_8, false);

        assertThat(new String(bytes, StandardCharsets.UTF_8)).isEqualTo("a\tb\n");
    }

    @Test
    void unNumeroConFormatoQuedaConEseFormatoEnXlsxYXls() throws Exception {
        List<List<Object>> filas = List.of(java.util.Arrays.asList(
            new ListasDePrueba.ConFormato(0.21, "0%"), new ListasDePrueba.ConFormato(0.21, "0.00"), 21));

        for (byte[] bytes : List.of(ListasDePrueba.xlsx(filas), ListasDePrueba.xls(filas))) {
            try (Workbook libro = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
                var fila = libro.getSheetAt(0).getRow(0);
                assertThat(fila.getCell(0).getCellType()).isEqualTo(CellType.NUMERIC);
                assertThat(fila.getCell(0).getNumericCellValue()).isEqualTo(0.21);
                assertThat(fila.getCell(0).getCellStyle().getDataFormatString()).isEqualTo("0%");
                assertThat(fila.getCell(1).getNumericCellValue()).isEqualTo(0.21);
                assertThat(fila.getCell(1).getCellStyle().getDataFormatString()).isEqualTo("0.00");
                assertThat(fila.getCell(2).getCellStyle().getDataFormatString()).isEqualTo("General");
            }
        }
    }
}
