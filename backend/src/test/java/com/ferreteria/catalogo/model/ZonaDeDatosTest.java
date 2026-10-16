package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.support.ListasReales;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Zona de datos de la lista (R-102, R-106, R-110): desde donde se leen las filas de cada hoja y cuales son de
 * datos, cuales se saltean (titulos) y cuales se ignoran. Puro, sobre las filas ya leidas.
 */
class ZonaDeDatosTest {

    private static final MapeoLista.Encabezados ENCABEZADOS_336 =
        new MapeoLista.Encabezados("Código", "Artículo", "Lista (unidad)", "Mon.", null, null, null, null, null, null,
            null, null, null);

    /** LISTA 336 con moneda: codigo 0, descripcion 1, moneda 4, costo 5, datos desde la fila 2. */
    private static MapeoLista mapeo336(List<Integer> hojas) {
        return new MapeoLista(0, 2, 0, 1, 5, ENCABEZADOS_336, hojas, 4, null, null, null, null, null, null, null, null,
            null, null);
    }

    /** M-S: codigo 0, descripcion 1, costo 2, datos desde la fila 15, con los textos de la fila 14. */
    private static MapeoLista mapeoMs() {
        return new MapeoLista(0, 15, 0, 1, 2,
            new MapeoLista.Encabezados("Familia: 001 - Pasadores", null, "Unidad.", null, null, null, null, null, null,
                null, null, null, null));
    }

    private static List<Object> fila(Object... valores) {
        return Arrays.asList(valores);
    }

    private static List<Object> titulo336(String texto) {
        return fila(null, null, null, null, texto);
    }

    private static List<Object> dato336(String codigo, String moneda, Number lista) {
        return fila(codigo, "Articulo " + codigo, null, null, moneda, lista, 1, lista, lista);
    }

    private static List<Object> encabezado336() {
        return fila("Código", "Artículo", "Sinónimo", "Stock", "Mon.", "Lista (unidad)", "Bulto", "Sin IVA", "Con IVA");
    }

    private static LecturaLista unaHoja(List<List<Object>> filas) {
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put("H", filas);
        return ListasReales.leida(hojas);
    }

    private static List<String> codigos(ZonaDeDatos.Resultado resultado) {
        return resultado.filas().stream().map(f -> f.celda(0).texto()).toList();
    }

    // ---- LISTA 336 ----

    @Test
    @DisplayName("LISTA 336: el titulo con texto solo en Mon. es una fila salteada y no una fila de datos")
    void tituloDeLista336() {
        LecturaLista lectura = ListasReales.leida(ListasReales.lista336ChicaHojas());

        ZonaDeDatos.Resultado resultado = ZonaDeDatos.clasificar(lectura, mapeo336(List.of(0)));

        assertThat(codigos(resultado)).containsExactly("TOR-001", "TOR-002", "TOR-003", "TOR-004", "TOR-005");
        assertThat(resultado.salteadas()).isEqualTo(1);
        assertThat(resultado.ignoradas()).isZero();
        assertThat(resultado.advertencias()).isEmpty();
    }

    @Test
    @DisplayName("LISTA 336: 39 titulos de una hoja y 3 de otras dan 42 salteadas y ninguna fila de datos de mas")
    void cuarentaYDosSalteadas() {
        List<List<Object>> primera = new ArrayList<>();
        primera.add(encabezado336());
        for (int i = 1; i <= 39; i++) {
            primera.add(titulo336("SUBRUBRO " + i));
            primera.add(dato336("P-" + i, "ARG", 100 + i));
        }
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put("UNO", primera);
        hojas.put("DOS", List.of(encabezado336(), titulo336("OTRO A"), dato336("Q-1", "USD", 10)));
        hojas.put("TRES", List.of(encabezado336(), titulo336("OTRO B"), titulo336("OTRO C"), dato336("R-1", "ARG", 5)));

        ZonaDeDatos.Resultado resultado =
            ZonaDeDatos.clasificar(ListasReales.leida(hojas), mapeo336(List.of(0, 1, 2)));

        assertThat(resultado.salteadas()).isEqualTo(42);
        assertThat(resultado.filas()).hasSize(39 + 1 + 1);
        assertThat(resultado.filas()).extracting(FilaLeida::hoja).contains(0, 1, 2);
        assertThat(resultado.advertencias()).isEmpty();
    }

    // ---- M-S ----

    @Test
    @DisplayName("M-S: los titulos Familia: NNN - Nombre en la columna del codigo son salteadas, y las filas vacias no suman")
    void titulosDeMs() {
        LecturaLista lectura = ListasReales.leida(Map.of("M-S ", ListasReales.msChicaFilas()));

        ZonaDeDatos.Resultado resultado = ZonaDeDatos.clasificar(lectura, mapeoMs());

        assertThat(resultado.salteadas()).isEqualTo(2);
        assertThat(resultado.filas()).hasSize(ListasReales.MS_CHICA_ARTICULOS);
        assertThat(resultado.filas().get(0).celda(0).texto()).isEqualTo("P-001");
        assertThat(resultado.ignoradas()).isZero();
        // El "Familia: 001" de la fila 14 es del encabezado: queda antes de la primera fila de datos y no cuenta.
        assertThat(codigos(resultado)).noneMatch(c -> c.startsWith("Familia"));
    }

    @Test
    @DisplayName("M-S entero: 76 titulos y 1.167 articulos dan 76 salteadas y 1.167 filas de datos (77 vacias no cuentan)")
    void msCompleta() {
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put("M-S ", ListasReales.msFilas(77, 15));

        ZonaDeDatos.Resultado resultado = ZonaDeDatos.clasificar(ListasReales.leida(hojas), mapeoMs());

        // msFilas(77, 15): la primera familia va en el encabezado; las otras 76 llevan titulo.
        assertThat(resultado.salteadas()).isEqualTo(76);
        assertThat(resultado.filas()).hasSize(77 * 15);
        assertThat(resultado.ignoradas()).isZero();
    }

    // ---- filas de datos, ignoradas y salteadas ----

    @Test
    @DisplayName("codigo, descripcion y costo vacio NO es un titulo: es una fila de datos (el planificador la marca como error)")
    void costoVacioConDescripcionEsDeDatos() {
        LecturaLista lectura = unaHoja(List.of(
            encabezado336(),
            fila("A-1", "Tornillo", null, null, "ARG", null, null, null, null),
            fila("A-2", "Tuerca", null, null, "ARG", 100, 1, 100, 121)));

        ZonaDeDatos.Resultado resultado = ZonaDeDatos.clasificar(lectura, mapeo336(null));

        assertThat(codigos(resultado)).containsExactly("A-1", "A-2");
        assertThat(resultado.salteadas()).isZero();
    }

    @Test
    @DisplayName("un costo escrito como texto (consultar) con descripcion tambien es fila de datos")
    void costoDeTextoEsDeDatos() {
        LecturaLista lectura = unaHoja(List.of(
            encabezado336(),
            fila("A-1", "Tornillo", null, null, "ARG", "consultar", null, null, null)));

        ZonaDeDatos.Resultado resultado = ZonaDeDatos.clasificar(lectura, mapeo336(null));

        assertThat(codigos(resultado)).containsExactly("A-1");
        assertThat(resultado.salteadas()).isZero();
    }

    @Test
    @DisplayName("una fila con costo numerico es de datos aunque no tenga descripcion")
    void costoSinDescripcionEsDeDatos() {
        LecturaLista lectura = unaHoja(List.of(
            encabezado336(),
            fila("A-1", null, null, null, "ARG", 100, 1, 100, 121)));

        ZonaDeDatos.Resultado resultado = ZonaDeDatos.clasificar(lectura, mapeo336(null));

        assertThat(codigos(resultado)).containsExactly("A-1");
        assertThat(resultado.salteadas()).isZero();
    }

    @Test
    @DisplayName("sin costo ni descripcion, un texto en el codigo hace una salteada, y uno en la moneda tambien")
    void definicionDeSalteada() {
        LecturaLista lectura = unaHoja(List.of(
            encabezado336(),
            fila("RUBRO FIJACIONES"),
            titulo336("ALEBA CARGADORES"),
            fila("A-1", "Tornillo", null, null, "ARG", 100, 1, 100, 121)));

        ZonaDeDatos.Resultado resultado = ZonaDeDatos.clasificar(lectura, mapeo336(null));

        assertThat(resultado.salteadas()).isEqualTo(2);
        assertThat(codigos(resultado)).containsExactly("A-1");
    }

    @Test
    @DisplayName("texto en la columna de moneda sin que la moneda este mapeada: no hay donde ver un titulo, se ignora")
    void tituloEnMonedaSinMapear() {
        MapeoLista sinMoneda = new MapeoLista(0, 2, 0, 1, 5, ENCABEZADOS_336);
        LecturaLista lectura = unaHoja(List.of(encabezado336(), titulo336("ALEBA CARGADORES")));

        ZonaDeDatos.Resultado resultado = ZonaDeDatos.clasificar(lectura, sinMoneda);

        assertThat(resultado.filas()).isEmpty();
        assertThat(resultado.salteadas()).isZero();
        assertThat(resultado.ignoradas()).isEqualTo(1);
    }

    @Test
    @DisplayName("una fila con contenido solo en columnas sin mapear es ignorada: no suma a salteadas ni a nada")
    void ignoradaPorColumnasSinMapear() {
        LecturaLista lectura = unaHoja(List.of(
            encabezado336(),
            fila(null, null, "sinonimo suelto", 5, null, null, 10, null, null),
            fila("A-1", "Tornillo", null, null, "ARG", 100, 1, 100, 121)));

        ZonaDeDatos.Resultado resultado = ZonaDeDatos.clasificar(lectura, mapeo336(null));

        assertThat(codigos(resultado)).containsExactly("A-1");
        assertThat(resultado.ignoradas()).isEqualTo(1);
        assertThat(resultado.salteadas()).isZero();
    }

    @Test
    @DisplayName("una fila totalmente vacia es ignorada")
    void filaTotalmenteVacia() {
        List<FilaLeida> filas = List.of(
            new FilaLeida(0, 1, List.of(new Celda("Código", null), new Celda("Artículo", null),
                Celda.VACIA, Celda.VACIA, Celda.VACIA, new Celda("Lista (unidad)", null))),
            new FilaLeida(0, 2, List.of(Celda.VACIA, Celda.VACIA)),
            new FilaLeida(0, 3, List.of(new Celda("A-1", null), new Celda("Tornillo", null), Celda.VACIA,
                Celda.VACIA, Celda.VACIA, new Celda("100", new java.math.BigDecimal("100")))));

        ZonaDeDatos.Resultado resultado = ZonaDeDatos.clasificar(new LecturaLista(List.of("H"), filas), mapeo336(null));

        assertThat(codigos(resultado)).containsExactly("A-1");
        assertThat(resultado.ignoradas()).isEqualTo(1);
        assertThat(resultado.salteadas()).isZero();
    }

    @Test
    @DisplayName("un encabezado repetido en medio de los datos es una salteada")
    void encabezadoRepetido() {
        LecturaLista lectura = unaHoja(List.of(
            encabezado336(),
            dato336("A-1", "ARG", 100),
            encabezado336(),
            dato336("A-2", "ARG", 200)));

        ZonaDeDatos.Resultado resultado = ZonaDeDatos.clasificar(lectura, mapeo336(null));

        assertThat(codigos(resultado)).containsExactly("A-1", "A-2");
        assertThat(resultado.salteadas()).isEqualTo(1);
    }

    @Test
    @DisplayName("el encabezado y lo anterior a la primera fila de datos no se cuentan")
    void loAnteriorNoCuenta() {
        LecturaLista lectura = unaHoja(List.of(
            fila("Lista de precios de Aleba"),
            titulo336("ALEBA CARGADORES"),
            encabezado336(),
            dato336("A-1", "ARG", 100)));
        MapeoLista desdeLa4 = new MapeoLista(0, 4, 0, 1, 5, ENCABEZADOS_336, null, 4, null, null, null, null, null, null,
            null, null, null, null);

        ZonaDeDatos.Resultado resultado = ZonaDeDatos.clasificar(lectura, desdeLa4);

        assertThat(codigos(resultado)).containsExactly("A-1");
        assertThat(resultado.salteadas()).isZero();
        assertThat(resultado.ignoradas()).isZero();
    }

    @Test
    @DisplayName("sin columna de descripcion mapeada, un codigo sin costo sigue siendo fila de datos (como en CU-04)")
    void sinDescripcionMapeada() {
        MapeoLista sinDescripcion = new MapeoLista(0, 2, 0, null, 5, ENCABEZADOS_336);
        LecturaLista lectura = unaHoja(List.of(
            encabezado336(),
            fila("A-1", null, null, null, null, null, null, null, null)));

        ZonaDeDatos.Resultado resultado = ZonaDeDatos.clasificar(lectura, sinDescripcion);

        assertThat(codigos(resultado)).containsExactly("A-1");
        assertThat(resultado.salteadas()).isZero();
    }

    // ---- inicio por hoja y un mapeo para todas las hojas (T-114, decision 34) ----

    private static Map<String, List<List<Object>>> hojasConEncabezadoEnOtraFila() {
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put("UNO", List.of(
            encabezado336(), dato336("A-1", "ARG", 100), dato336("A-2", "ARG", 200)));
        hojas.put("DOS", List.of(
            fila("Lista de precios de TUERCAS"), fila(), encabezado336(), dato336("B-1", "ARG", 300)));
        hojas.put("TRES", List.of(
            fila("ALEBA"), fila("Sucursal Mar del Plata"), fila("Vigente al 02/10/2026"), fila("Precios en pesos"),
            encabezado336(), dato336("C-1", "USD", 4)));
        return hojas;
    }

    @Test
    @DisplayName("tres hojas con el encabezado en la fila 1, 3 y 5: en cada una los datos arrancan despues de su encabezado")
    void inicioPorHoja() {
        ZonaDeDatos.Resultado resultado = ZonaDeDatos.clasificar(
            ListasReales.leida(hojasConEncabezadoEnOtraFila()), mapeo336(List.of(0, 1, 2)));

        assertThat(codigos(resultado)).containsExactly("A-1", "A-2", "B-1", "C-1");
        assertThat(resultado.filas()).extracting(FilaLeida::hoja).containsExactly(0, 0, 1, 2);
        assertThat(resultado.filas()).extracting(FilaLeida::numero).containsExactly(2, 3, 4, 6);
        // Lo que esta antes del encabezado de cada hoja no se cuenta como titulo ni como nada.
        assertThat(resultado.salteadas()).isZero();
        assertThat(resultado.ignoradas()).isZero();
        assertThat(resultado.advertencias()).isEmpty();
    }

    @Test
    @DisplayName("el encabezado se reconoce sin distinguir mayusculas ni tildes")
    void encabezadoSinMayusculasNiTildes() {
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put("UNO", List.of(
            fila("texto de portada"),
            fila("CODIGO", "ARTICULO", "SINONIMO", "STOCK", "MON.", "LISTA (UNIDAD)"),
            dato336("A-1", "ARG", 100)));

        ZonaDeDatos.Resultado resultado = ZonaDeDatos.clasificar(ListasReales.leida(hojas), mapeo336(null));

        assertThat(codigos(resultado)).containsExactly("A-1");
        assertThat(resultado.salteadas()).isZero();
        assertThat(resultado.advertencias()).isEmpty();
    }

    @Test
    @DisplayName("el encabezado es la primera fila donde coinciden el codigo y el costo: con uno repetido despues, se cuenta salteada")
    void primerEncabezadoYRepetido() {
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put("UNO", List.of(
            fila("portada"), encabezado336(), dato336("A-1", "ARG", 100), encabezado336(), dato336("A-2", "ARG", 200)));

        ZonaDeDatos.Resultado resultado = ZonaDeDatos.clasificar(ListasReales.leida(hojas), mapeo336(null));

        assertThat(codigos(resultado)).containsExactly("A-1", "A-2");
        assertThat(resultado.salteadas()).isEqualTo(1);
    }

    @Test
    @DisplayName("una fila que coincide solo en el codigo, pero no en el costo, no es el encabezado")
    void tienenQueCoincidirLosDos() {
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put("UNO", List.of(
            fila("Código", "Artículo", null, null, "Mon.", "Precio de venta"),
            fila("portada"),
            encabezado336(),
            dato336("A-1", "ARG", 100)));

        ZonaDeDatos.Resultado resultado = ZonaDeDatos.clasificar(ListasReales.leida(hojas), mapeo336(null));

        // Las dos primeras filas quedan antes del encabezado de verdad (la 3).
        assertThat(codigos(resultado)).containsExactly("A-1");
        assertThat(resultado.salteadas()).isZero();
    }

    @Test
    @DisplayName("una hoja donde ninguna fila coincide usa filaDesde y suma una advertencia con el nombre de la hoja")
    void hojaSinEncabezado() {
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put("UNO", List.of(encabezado336(), dato336("A-1", "ARG", 100)));
        hojas.put("TUERCAS", List.of(
            fila("Cod.", "Detalle", null, null, "Mon.", "Precio"), dato336("B-1", "ARG", 200)));
        hojas.put("ARANDELAS", List.of(
            fila("Cod.", "Detalle", null, null, "Mon.", "Precio"), dato336("C-1", "ARG", 300)));

        ZonaDeDatos.Resultado resultado = ZonaDeDatos.clasificar(ListasReales.leida(hojas), mapeo336(List.of(0, 1, 2)));

        // Sin encabezado reconocible usa filaDesde (2) y lee los datos de todos modos.
        assertThat(codigos(resultado)).containsExactly("A-1", "B-1", "C-1");
        assertThat(resultado.advertencias()).hasSize(2);
        assertThat(resultado.advertencias()).extracting(AvisoDeFila::hoja).containsExactly(1, 2);
        assertThat(resultado.advertencias()).allMatch(a -> a.fila() == null && a.codigo() == null);
        assertThat(resultado.advertencias().get(0).mensaje()).contains("«TUERCAS»").contains("fila 2");
        assertThat(resultado.advertencias().get(1).mensaje()).contains("«ARANDELAS»");
    }

    @Test
    @DisplayName("un mapeo viejo, sin hojas, lee solo la hoja del mapeo")
    void mapeoViejoSoloLaHoja() {
        MapeoLista viejo = new MapeoLista(1, 2, 0, 1, 5, ENCABEZADOS_336);

        ZonaDeDatos.Resultado resultado =
            ZonaDeDatos.clasificar(ListasReales.leida(hojasConEncabezadoEnOtraFila()), viejo);

        // La hoja 1 (DOS) tiene el encabezado en la fila 3: se lee desde su dato.
        assertThat(codigos(resultado)).containsExactly("B-1");
        assertThat(resultado.filas()).extracting(FilaLeida::hoja).containsOnly(1);
    }

    @Test
    @DisplayName("con hojas = [0, 1] se leen solo esas dos")
    void soloLasHojasElegidas() {
        ZonaDeDatos.Resultado resultado = ZonaDeDatos.clasificar(
            ListasReales.leida(hojasConEncabezadoEnOtraFila()), mapeo336(List.of(0, 1)));

        assertThat(codigos(resultado)).containsExactly("A-1", "A-2", "B-1");
        assertThat(resultado.filas()).extracting(FilaLeida::hoja).doesNotContain(2);
    }

    @Test
    @DisplayName("las hojas salen en el orden del mapeo, no en el del archivo")
    void ordenDelMapeo() {
        ZonaDeDatos.Resultado resultado = ZonaDeDatos.clasificar(
            ListasReales.leida(hojasConEncabezadoEnOtraFila()), mapeo336(List.of(2, 0)));

        assertThat(codigos(resultado)).containsExactly("C-1", "A-1", "A-2");
    }

    @Test
    @DisplayName("un mapeo viejo sin textos de encabezado usa filaDesde en cada hoja y nunca falla")
    void sinTextosDeEncabezado() {
        MapeoLista sinTextos = new MapeoLista(0, 2, 0, 1, 5, null, List.of(0, 1), 4, null, null, null, null, null,
            null, null, null, null, null);

        ZonaDeDatos.Resultado resultado =
            ZonaDeDatos.clasificar(ListasReales.leida(hojasConEncabezadoEnOtraFila()), sinTextos);

        // Hoja 0: desde la 2. Hoja 1: desde la 2 tambien; el texto de portada y el encabezado de la 3 entran
        // como filas (no hay con que reconocerlos) y el planificador los marcara.
        assertThat(resultado.filas()).extracting(FilaLeida::hoja).containsOnly(0, 1);
        assertThat(codigos(resultado)).contains("A-1", "A-2", "B-1", "Código");
        assertThat(resultado.advertencias()).isEmpty();
    }

    @Test
    @DisplayName("los textos guardados con celda vacia no se exigen: con solo el del costo alcanza")
    void soloElTextoDelCosto() {
        MapeoLista.Encabezados soloCosto = new MapeoLista.Encabezados(null, null, "Lista (unidad)");
        MapeoLista mapeo = new MapeoLista(0, 2, 0, 1, 5, soloCosto, null, 4, null, null, null, null, null, null, null,
            null, null, null);
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put("UNO", List.of(fila("portada"), encabezado336(), dato336("A-1", "ARG", 100)));

        ZonaDeDatos.Resultado resultado = ZonaDeDatos.clasificar(ListasReales.leida(hojas), mapeo);

        assertThat(codigos(resultado)).containsExactly("A-1");
        assertThat(resultado.advertencias()).isEmpty();
    }
}
