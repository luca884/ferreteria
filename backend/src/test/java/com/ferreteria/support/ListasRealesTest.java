package com.ferreteria.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import com.ferreteria.catalogo.model.Celda;
import com.ferreteria.catalogo.model.FilaLeida;
import com.ferreteria.catalogo.model.LecturaLista;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Los fixtures chicos copian la estructura medida en las listas reales (obs #5378): se leen con POI y se
 * verifican hojas, encabezados y posiciones. Los tests del corte 2 nunca leen Lista/ (no esta en git).
 */
class ListasRealesTest {

    // Columnas de LISTA 336 (base 0)
    private static final int CODIGO = 0;
    private static final int ARTICULO = 1;
    private static final int SINONIMO = 2;
    private static final int STOCK = 3;
    private static final int MON = 4;
    private static final int LISTA_UNIDAD = 5;
    private static final int BULTO = 6;
    private static final int SIN_IVA = 7;
    private static final int CON_IVA = 8;

    private static Workbook abrir(byte[] bytes) throws Exception {
        return WorkbookFactory.create(new ByteArrayInputStream(bytes));
    }

    private static String texto(Row fila, int columna) {
        Cell celda = fila == null ? null : fila.getCell(columna);
        return celda == null || celda.getCellType() != CellType.STRING ? null : celda.getStringCellValue();
    }

    private static BigDecimal numero(Row fila, int columna) {
        Cell celda = fila == null ? null : fila.getCell(columna);
        return celda == null || celda.getCellType() != CellType.NUMERIC
            ? null : BigDecimal.valueOf(celda.getNumericCellValue());
    }

    private static boolean filaVacia(Row fila) {
        if (fila == null) {
            return true;
        }
        for (Cell celda : fila) {
            if (celda.getCellType() != CellType.BLANK) {
                return false;
            }
        }
        return true;
    }

    // ---- LISTA 336 ----

    @Test
    @DisplayName("lista336Chica es un .xlsx de 3 hojas con el encabezado real de una fila")
    void lista336TieneTresHojasYElEncabezadoReal() throws Exception {
        byte[] bytes = ListasReales.lista336Chica();

        try (Workbook libro = abrir(bytes)) {
            assertThat(libro).isInstanceOf(XSSFWorkbook.class);
            assertThat(libro.getNumberOfSheets()).isEqualTo(3);
            assertThat(libro.getSheetName(0)).isEqualTo("TORNILLOS");
            assertThat(libro.getSheetName(1)).isEqualTo("TUERCAS");
            assertThat(libro.getSheetName(2)).isEqualTo("ARANDELAS");
            for (int h = 0; h < 3; h++) {
                Row encabezado = libro.getSheetAt(h).getRow(0);
                assertThat(texto(encabezado, CODIGO)).isEqualTo("Código");
                assertThat(texto(encabezado, ARTICULO)).isEqualTo("Artículo");
                assertThat(texto(encabezado, SINONIMO)).isEqualTo("Sinónimo");
                assertThat(texto(encabezado, STOCK)).isEqualTo("Stock");
                assertThat(texto(encabezado, MON)).isEqualTo("Mon.");
                assertThat(texto(encabezado, LISTA_UNIDAD)).isEqualTo("Lista (unidad)");
                assertThat(texto(encabezado, BULTO)).isEqualTo("Bulto");
                assertThat(texto(encabezado, SIN_IVA)).isEqualTo("Sin IVA");
                assertThat(texto(encabezado, CON_IVA)).isEqualTo("Con IVA");
            }
        }
    }

    @Test
    @DisplayName("la primera hoja trae filas ARG y USD, una al 10,5 %, una con bulto 10, un titulo y un costo 0")
    void lista336PrimeraHoja() throws Exception {
        try (Workbook libro = abrir(ListasReales.lista336Chica())) {
            Sheet hoja = libro.getSheetAt(0);
            assertThat(hoja.getLastRowNum()).isEqualTo(6);

            Row arg = hoja.getRow(1);
            assertThat(texto(arg, CODIGO)).isEqualTo("TOR-001");
            assertThat(texto(arg, MON)).isEqualTo("ARG");
            assertThat(numero(arg, LISTA_UNIDAD)).isEqualByComparingTo("1000");
            assertThat(numero(arg, SIN_IVA).multiply(new BigDecimal("1.21"))).isEqualByComparingTo(numero(arg, CON_IVA));

            Row usd = hoja.getRow(2);
            assertThat(texto(usd, MON)).isEqualTo("USD");
            assertThat(numero(usd, LISTA_UNIDAD)).isEqualByComparingTo("10");

            Row alDiezYMedio = hoja.getRow(3);
            assertThat(numero(alDiezYMedio, SIN_IVA).multiply(new BigDecimal("1.105")))
                .isEqualByComparingTo(numero(alDiezYMedio, CON_IVA));
            assertThat(numero(alDiezYMedio, CON_IVA)).isEqualByComparingTo("1105");

            Row conBulto = hoja.getRow(4);
            assertThat(numero(conBulto, BULTO)).isEqualByComparingTo("10");
            assertThat(numero(conBulto, SIN_IVA))
                .isEqualByComparingTo(numero(conBulto, LISTA_UNIDAD).multiply(numero(conBulto, BULTO)));

            Row costoCero = hoja.getRow(6);
            assertThat(texto(costoCero, CODIGO)).isEqualTo("TOR-005");
            assertThat(numero(costoCero, LISTA_UNIDAD)).isEqualByComparingTo("0");
        }
    }

    @Test
    @DisplayName("la fila de titulo trae texto solo en Mon. y ningun importe")
    void lista336Titulo() throws Exception {
        try (Workbook libro = abrir(ListasReales.lista336Chica())) {
            Row titulo = libro.getSheetAt(0).getRow(5);

            assertThat(texto(titulo, MON)).isEqualTo("ALEBA CARGADORES");
            assertThat(texto(titulo, CODIGO)).isNull();
            assertThat(texto(titulo, ARTICULO)).isNull();
            assertThat(numero(titulo, LISTA_UNIDAD)).isNull();
            assertThat(numero(titulo, SIN_IVA)).isNull();
            assertThat(numero(titulo, CON_IVA)).isNull();
        }
    }

    @Test
    @DisplayName("lista336Hojas arma N hojas con el encabezado y M filas ARG cada una")
    void lista336Parametrizable() throws Exception {
        try (Workbook libro = abrir(ListasReales.lista336Hojas(5, 3))) {
            assertThat(libro.getNumberOfSheets()).isEqualTo(5);
            for (int h = 0; h < 5; h++) {
                Sheet hoja = libro.getSheetAt(h);
                assertThat(texto(hoja.getRow(0), MON)).isEqualTo("Mon.");
                assertThat(hoja.getLastRowNum()).isEqualTo(3);
                assertThat(texto(hoja.getRow(1), CODIGO)).isEqualTo("H" + (h + 1) + "-001");
                assertThat(texto(hoja.getRow(3), MON)).isEqualTo("ARG");
            }
        }
        try (Workbook libro = abrir(ListasReales.lista336Hojas(70, 1))) {
            assertThat(libro.getNumberOfSheets()).isEqualTo(70);
        }
    }

    // ---- M-S ----

    @Test
    @DisplayName("msChica es un .xls de 1 hoja con el encabezado de dos filas (13 y 14)")
    void msEncabezadoDeDosFilas() throws Exception {
        try (Workbook libro = abrir(ListasReales.msChica())) {
            assertThat(libro).isInstanceOf(HSSFWorkbook.class);
            assertThat(libro.getNumberOfSheets()).isEqualTo(1);
            Sheet hoja = libro.getSheetAt(0);

            Row primera = hoja.getRow(12);
            assertThat(texto(primera, 0)).isEqualTo("Código");
            assertThat(texto(primera, 1)).isEqualTo("Descripción");
            assertThat(texto(primera, 2)).isEqualTo("Precio X");
            assertThat(texto(primera, 3)).isEqualTo("Cantidad");
            assertThat(texto(primera, 5)).isEqualTo("Precio");

            Row segunda = hoja.getRow(13);
            assertThat(texto(segunda, 0)).isEqualTo("Familia: 001 - Pasadores");
            assertThat(texto(segunda, 2)).isEqualTo("Unidad.");
            assertThat(texto(segunda, 3)).isEqualTo("x Caja");
            assertThat(texto(segunda, 5)).isEqualTo("Final");
        }
    }

    @Test
    @DisplayName("los datos de M-S arrancan en la fila 15 con costos de 4 decimales")
    void msDatos() throws Exception {
        try (Workbook libro = abrir(ListasReales.msChica())) {
            Row primeraDeDatos = libro.getSheetAt(0).getRow(14);

            assertThat(texto(primeraDeDatos, 0)).isEqualTo("P-001");
            assertThat(texto(primeraDeDatos, 1)).isEqualTo("Pasador 1/8");
            assertThat(numero(primeraDeDatos, 2)).isEqualByComparingTo("1532.4361");
            assertThat(numero(primeraDeDatos, 3)).isNotNull();
            assertThat(numero(primeraDeDatos, 5)).isEqualByComparingTo(numero(primeraDeDatos, 2));
            // Las doce filas de arriba son membrete y no traen datos de articulos.
            for (int r = 0; r < 12; r++) {
                assertThat(texto(libro.getSheetAt(0).getRow(r), 1)).as("fila " + (r + 1)).isNull();
            }
        }
    }

    @Test
    @DisplayName("hay dos titulos Familia: NNN - Nombre mas, cada uno precedido de una fila vacia")
    void msTitulosPrecedidosDeFilaVacia() throws Exception {
        try (Workbook libro = abrir(ListasReales.msChica())) {
            Sheet hoja = libro.getSheetAt(0);
            List<Integer> titulos = new ArrayList<>();
            for (int r = 14; r <= hoja.getLastRowNum(); r++) {
                String a = texto(hoja.getRow(r), 0);
                if (a != null && a.startsWith("Familia: ")) {
                    titulos.add(r);
                }
            }

            assertThat(titulos).hasSize(2);
            assertThat(texto(hoja.getRow(titulos.get(0)), 0)).isEqualTo("Familia: 002 - Bulones");
            assertThat(texto(hoja.getRow(titulos.get(1)), 0)).isEqualTo("Familia: 003 - Chavetas");
            for (int fila : titulos) {
                assertThat(filaVacia(hoja.getRow(fila - 1))).as("fila previa al titulo " + (fila + 1)).isTrue();
                assertThat(texto(hoja.getRow(fila), 1)).isNull();
                assertThat(numero(hoja.getRow(fila), 2)).isNull();
            }
        }
    }

    @Test
    @DisplayName("ms(familias, articulos) cuenta los titulos y los articulos pedidos")
    void msParametrizable() throws Exception {
        try (Workbook libro = abrir(ListasReales.ms(4, 3))) {
            Sheet hoja = libro.getSheetAt(0);
            int titulos = 0;
            int articulos = 0;
            for (int r = 14; r <= hoja.getLastRowNum(); r++) {
                if (numero(hoja.getRow(r), 2) != null) {
                    articulos++;
                }
                String a = texto(hoja.getRow(r), 0);
                if (a != null && a.startsWith("Familia: ")) {
                    titulos++;
                }
            }

            // La primera familia va en la segunda fila del encabezado (fila 14), no cuenta aca.
            assertThat(titulos).isEqualTo(3);
            assertThat(articulos).isEqualTo(12);
        }
    }

    // ---- leida: las mismas filas como las deja el lector, sin pasar por POI ----

    @Test
    @DisplayName("leida numera las filas desde 1, salta las vacias y lleva la hoja de cada una")
    void leidaComoElLector() {
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put("A", List.of(Arrays.asList("x", 1500), List.of(), Arrays.asList(null, "b")));
        hojas.put("B", List.of(Arrays.asList("y", 1532.4361, null)));

        LecturaLista lectura = ListasReales.leida(hojas);

        assertThat(lectura.hojas()).containsExactly("A", "B");
        assertThat(lectura.filas()).extracting(FilaLeida::hoja, FilaLeida::numero).containsExactly(
            org.assertj.core.groups.Tuple.tuple(0, 1), org.assertj.core.groups.Tuple.tuple(0, 3),
            org.assertj.core.groups.Tuple.tuple(1, 1));
        FilaLeida primera = lectura.filas().get(0);
        assertThat(primera.celda(0)).isEqualTo(new Celda("x", null));
        assertThat(primera.celda(1).texto()).isEqualTo("1500");
        assertThat(primera.celda(1).numero()).isEqualByComparingTo("1500");
        assertThat(lectura.filas().get(1).celda(0)).isSameAs(Celda.VACIA);
        assertThat(lectura.filas().get(1).celda(1).texto()).isEqualTo("b");
        assertThat(lectura.filas().get(2).celda(1).texto()).isEqualTo("1532.4361");
        assertThat(lectura.filas().get(2).celda(1).numero()).isEqualByComparingTo("1532.4361");
    }

    @Test
    @DisplayName("leida de LISTA 336 chica trae el encabezado en la fila 1 de cada hoja")
    void leidaDeLista336() {
        LecturaLista lectura = ListasReales.leida(ListasReales.lista336ChicaHojas());

        assertThat(lectura.hojas()).containsExactly("TORNILLOS", "TUERCAS", "ARANDELAS");
        assertThat(lectura.filasDeHoja(0).get(0).numero()).isEqualTo(1);
        assertThat(lectura.filasDeHoja(0).get(0).celda(4).texto()).isEqualTo("Mon.");
        assertThat(lectura.filasDeHoja(2).get(0).celda(5).texto()).isEqualTo("Lista (unidad)");
        // Los titulos no se pierden: ALEBA CARGADORES esta en la fila 6 de la primera hoja.
        assertThat(lectura.filasDeHoja(0)).anyMatch(f -> f.numero() == 6 && f.celda(4).texto().equals("ALEBA CARGADORES"));
    }
}
