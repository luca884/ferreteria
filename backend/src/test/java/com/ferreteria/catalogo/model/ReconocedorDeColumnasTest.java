package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.support.ListasReales;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reconocimiento del encabezado y de las columnas (R-107). Puro: trabaja sobre las filas leidas, sin POI, con
 * fixtures que copian los encabezados reales de LISTA 336 y M-S.
 */
class ReconocedorDeColumnasTest {

    // Columnas de LISTA 336 (base 0)
    private static final int CODIGO = 0;
    private static final int ARTICULO = 1;
    private static final int MON = 4;
    private static final int LISTA_UNIDAD = 5;
    private static final int SIN_IVA = 7;
    private static final int CON_IVA = 8;

    private static LecturaLista lecturaDe(String hoja, List<List<Object>> filas) {
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put(hoja, filas);
        return ListasReales.leida(hojas);
    }

    private static List<Object> fila(Object... valores) {
        return Arrays.asList(valores);
    }

    // ---- LISTA 336: encabezado de una fila ----

    @Test
    @DisplayName("LISTA 336: propone codigo, descripcion, moneda, costo, sin IVA y con IVA")
    void lista336() {
        ReconocedorDeColumnas.Reconocimiento reconocimiento = ReconocedorDeColumnas.reconocer(ListasReales.leida(ListasReales.lista336ChicaHojas()));
        ReconocedorDeColumnas.Propuesta propuesta = reconocimiento.propuesta();

        assertThat(propuesta.columnaCodigo()).isEqualTo(CODIGO);
        assertThat(propuesta.columnaDescripcion()).isEqualTo(ARTICULO);
        assertThat(propuesta.columnaMoneda()).isEqualTo(MON);
        assertThat(propuesta.columnaCosto()).isEqualTo(LISTA_UNIDAD);
        assertThat(propuesta.columnaSinIva()).isEqualTo(SIN_IVA);
        assertThat(propuesta.columnaConIva()).isEqualTo(CON_IVA);
        assertThat(propuesta.hoja()).isZero();
    }

    @Test
    @DisplayName("Lista (unidad) es costo y no la unidad de medida")
    void listaUnidadEsCosto() {
        ReconocedorDeColumnas.Propuesta propuesta =
            ReconocedorDeColumnas.reconocer(ListasReales.leida(ListasReales.lista336ChicaHojas())).propuesta();

        assertThat(propuesta.columnaCosto()).isEqualTo(LISTA_UNIDAD);
        assertThat(propuesta.columnaUnidad()).isNull();
    }

    @Test
    @DisplayName("Sinonimo, Stock y Bulto no se proponen como ningun campo")
    void columnasQueSeIgnoran() {
        ReconocedorDeColumnas.Propuesta propuesta =
            ReconocedorDeColumnas.reconocer(ListasReales.leida(ListasReales.lista336ChicaHojas())).propuesta();

        List<Integer> propuestas = Arrays.asList(propuesta.columnaCodigo(), propuesta.columnaDescripcion(),
            propuesta.columnaCosto(), propuesta.columnaMoneda(), propuesta.columnaSinIva(), propuesta.columnaConIva(),
            propuesta.columnaIva(), propuesta.columnaAjuste1(), propuesta.columnaAjuste2(), propuesta.columnaAjuste3(),
            propuesta.columnaCodigoBarras(), propuesta.columnaUnidad(), propuesta.columnaRubro());
        // Sinonimo = 2, Stock = 3 y Bulto = 6: ninguna de las tres columnas es de ningun campo.
        assertThat(propuestas).doesNotContain(2, 3, 6);
        assertThat(propuestas).contains(CODIGO, ARTICULO, MON, LISTA_UNIDAD, SIN_IVA, CON_IVA);
    }

    @Test
    @DisplayName("Percepcion IIBB y Marca se ignoran")
    void percepcionYMarca() {
        LecturaLista lectura = lecturaDe("Hoja1", List.of(
            fila("Código", "Descripción", "Marca", "Percepción IIBB", "Precio"),
            fila("A-1", "Tornillo", "Bremen", 3, 100)));

        ReconocedorDeColumnas.Propuesta propuesta = ReconocedorDeColumnas.reconocer(lectura).propuesta();

        assertThat(propuesta.columnaCodigo()).isZero();
        assertThat(propuesta.columnaDescripcion()).isEqualTo(1);
        assertThat(propuesta.columnaCosto()).isEqualTo(4);
        assertThat(propuesta.columnaRubro()).isNull();
        assertThat(propuesta.columnaAjuste1()).isNull();
        assertThat(propuesta.columnaUnidad()).isNull();
    }

    @Test
    @DisplayName("compara sin distinguir mayusculas, tildes ni puntos")
    void normalizacion() {
        LecturaLista lectura = lecturaDe("Hoja1", List.of(
            fila("CÓDIGO.", "descripcion", "pRECIO"),
            fila("A-1", "Tornillo", 100)));

        ReconocedorDeColumnas.Propuesta propuesta = ReconocedorDeColumnas.reconocer(lectura).propuesta();

        assertThat(propuesta.columnaCodigo()).isZero();
        assertThat(propuesta.columnaDescripcion()).isEqualTo(1);
        assertThat(propuesta.columnaCosto()).isEqualTo(2);
    }

    @Test
    @DisplayName("la comparacion es por igualdad: un texto que solo contiene una palabra no cuenta")
    void igualdadYNoContiene() {
        LecturaLista lectura = lecturaDe("Hoja1", List.of(
            fila("Código", "Descripción", "Precio de venta al público sugerido", "Costo"),
            fila("A-1", "Tornillo", 120, 100)));

        ReconocedorDeColumnas.Propuesta propuesta = ReconocedorDeColumnas.reconocer(lectura).propuesta();

        assertThat(propuesta.columnaCosto()).isEqualTo(3);
    }

    @Test
    @DisplayName("si dos columnas son costo, gana la de sinonimo mas prioritario: Lista (unidad) antes que Precio")
    void prioridadDeSinonimos() {
        LecturaLista lectura = lecturaDe("Hoja1", List.of(
            fila("Código", "Artículo", "Precio", "Lista (unidad)"),
            fila("A-1", "Tornillo", 120, 100)));

        assertThat(ReconocedorDeColumnas.reconocer(lectura).propuesta().columnaCosto()).isEqualTo(3);
    }

    @Test
    @DisplayName("con «Código» y «Código del proveedor», el código es el del proveedor; con «Código» solo, sigue siendo ese")
    void codigoDelProveedorAntesQueCodigo() {
        LecturaLista ambas = lecturaDe("Hoja1", List.of(
            fila("Código", "Código del proveedor", "Descripción", "Costo"),
            fila("T-1", "PROV-9", "Tornillo", 100)));
        LecturaLista abreviada = lecturaDe("Hoja1", List.of(
            fila("Cod", "Cod. proveedor", "Descripción", "Costo"),
            fila("T-1", "PROV-9", "Tornillo", 100)));
        LecturaLista sola = lecturaDe("Hoja1", List.of(
            fila("Código", "Descripción", "Costo"),
            fila("T-1", "Tornillo", 100)));

        assertThat(ReconocedorDeColumnas.reconocer(ambas).propuesta().columnaCodigo()).isEqualTo(1);
        assertThat(ReconocedorDeColumnas.reconocer(abreviada).propuesta().columnaCodigo()).isEqualTo(1);
        assertThat(ReconocedorDeColumnas.reconocer(sola).propuesta().columnaCodigo()).isZero();
    }

    @Test
    @DisplayName("con el encabezado en la fila 1, los datos arrancan en la 2")
    void filaDeDatosDespuesDelEncabezado() {
        ReconocedorDeColumnas.Reconocimiento reconocimiento = ReconocedorDeColumnas.reconocer(ListasReales.leida(ListasReales.lista336ChicaHojas()));

        assertThat(reconocimiento.propuesta().filaDesde()).isEqualTo(2);
        assertThat(reconocimiento.hojas().get(0).filaEncabezado()).isEqualTo(1);
        assertThat(reconocimiento.hojas().get(0).primeraFilaDeDatos()).isEqualTo(2);
    }

    @Test
    @DisplayName("un encabezado en la fila 5 da datos desde la 6")
    void encabezadoMasAbajo() {
        LecturaLista lectura = lecturaDe("Hoja1", List.of(
            fila("Ferretería Los Tornillos"),
            fila(),
            fila("Lista de precios 2026"),
            fila(),
            fila("Código", "Descripción", "Costo"),
            fila("A-1", "Tornillo", 100)));

        ReconocedorDeColumnas.Reconocimiento reconocimiento = ReconocedorDeColumnas.reconocer(lectura);

        assertThat(reconocimiento.hojas().get(0).filaEncabezado()).isEqualTo(5);
        assertThat(reconocimiento.propuesta().filaDesde()).isEqualTo(6);
        assertThat(reconocimiento.propuesta().columnaCosto()).isEqualTo(2);
    }

    @Test
    @DisplayName("un encabezado pasada la fila 30 no se busca")
    void soloLasPrimeras30Filas() {
        List<List<Object>> filas = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            filas.add(fila("texto " + i));
        }
        filas.add(fila("Código", "Descripción", "Costo"));
        filas.add(fila("A-1", "Tornillo", 100));

        ReconocedorDeColumnas.Reconocimiento reconocimiento = ReconocedorDeColumnas.reconocer(lecturaDe("Hoja1", filas));

        assertThat(reconocimiento.hojas().get(0).filaEncabezado()).isNull();
        assertThat(reconocimiento.propuesta().columnaCodigo()).isNull();
    }

    @Test
    @DisplayName("un archivo sin encabezados reconocibles da una propuesta vacia, sin excepcion")
    void sinEncabezados() {
        LecturaLista lectura = lecturaDe("Hoja1", List.of(
            fila("xx", "yy", "zz"),
            fila("A-1", "Tornillo", 100),
            fila("A-2", "Tuerca", 200)));

        ReconocedorDeColumnas.Reconocimiento reconocimiento = ReconocedorDeColumnas.reconocer(lectura);
        ReconocedorDeColumnas.Propuesta propuesta = reconocimiento.propuesta();

        assertThat(reconocimiento.hojas()).hasSize(1);
        assertThat(reconocimiento.hojas().get(0).nombre()).isEqualTo("Hoja1");
        assertThat(reconocimiento.hojas().get(0).filaEncabezado()).isNull();
        assertThat(reconocimiento.hojas().get(0).primeraFilaDeDatos()).isNull();
        assertThat(propuesta.hoja()).isNull();
        assertThat(propuesta.filaDesde()).isNull();
        assertThat(propuesta.columnaCodigo()).isNull();
        assertThat(propuesta.columnaCosto()).isNull();
        assertThat(propuesta.hojas()).isEmpty();
    }

    @Test
    @DisplayName("una lectura sin filas tampoco rompe")
    void lecturaVacia() {
        ReconocedorDeColumnas.Reconocimiento reconocimiento = ReconocedorDeColumnas.reconocer(new LecturaLista(List.of("Hoja1"), List.of()));

        assertThat(reconocimiento.hojas()).hasSize(1);
        assertThat(reconocimiento.propuesta().columnaCodigo()).isNull();
        assertThat(reconocimiento.propuesta().hojas()).isEmpty();
    }

    @Test
    @DisplayName("el par de IVA se propone solo si se reconocen las dos columnas")
    void parDeIva() {
        LecturaLista soloSinIva = lecturaDe("Hoja1", List.of(
            fila("Código", "Artículo", "Lista", "Sin IVA"), fila("A-1", "Tornillo", 100, 100)));
        LecturaLista soloConIva = lecturaDe("Hoja1", List.of(
            fila("Código", "Artículo", "Lista", "Con IVA"), fila("A-1", "Tornillo", 100, 121)));
        LecturaLista las2 = lecturaDe("Hoja1", List.of(
            fila("Código", "Artículo", "Lista", "Sin IVA", "Con IVA"), fila("A-1", "Tornillo", 100, 100, 121)));

        assertThat(ReconocedorDeColumnas.reconocer(soloSinIva).propuesta().columnaSinIva()).isNull();
        assertThat(ReconocedorDeColumnas.reconocer(soloSinIva).propuesta().columnaConIva()).isNull();
        assertThat(ReconocedorDeColumnas.reconocer(soloConIva).propuesta().columnaSinIva()).isNull();
        assertThat(ReconocedorDeColumnas.reconocer(soloConIva).propuesta().columnaConIva()).isNull();
        assertThat(ReconocedorDeColumnas.reconocer(las2).propuesta().columnaSinIva()).isEqualTo(3);
        assertThat(ReconocedorDeColumnas.reconocer(las2).propuesta().columnaConIva()).isEqualTo(4);
    }

    @Test
    @DisplayName("una columna sin IVA sola, sin otra de costo, es el costo: «Precio s/IVA» o «Neto» (QA 05/11)")
    void sinIvaSolaEsCosto() {
        LecturaLista precioSinIva = lecturaDe("Hoja1", List.of(
            fila("Código", "Artículo", "Precio s/IVA"), fila("A-1", "Tornillo", 100)));
        LecturaLista neto = lecturaDe("Hoja1", List.of(
            fila("Código", "Artículo", "Neto"), fila("A-1", "Tornillo", 100)));
        LecturaLista listaYSinIva = lecturaDe("Hoja1", List.of(
            fila("Código", "Artículo", "Lista", "Sin IVA"), fila("A-1", "Tornillo", 100, 100)));

        ReconocedorDeColumnas.Propuesta conPrecio = ReconocedorDeColumnas.reconocer(precioSinIva).propuesta();
        assertThat(conPrecio.columnaCosto()).isEqualTo(2);
        assertThat(conPrecio.columnaSinIva()).isNull();
        assertThat(ReconocedorDeColumnas.reconocer(neto).propuesta().columnaCosto()).isEqualTo(2);
        // Con una columna de costo reconocida, la de sin IVA sola se sigue descartando.
        assertThat(ReconocedorDeColumnas.reconocer(listaYSinIva).propuesta().columnaCosto()).isEqualTo(2);
        assertThat(ReconocedorDeColumnas.reconocer(listaYSinIva).propuesta().columnaSinIva()).isNull();
    }

    @Test
    @DisplayName("reconoce tambien % IVA, descuentos, codigo de barras, unidad y rubro")
    void otrosCampos() {
        LecturaLista lectura = lecturaDe("Hoja1", List.of(
            fila("Código", "Descripción", "Precio", "% IVA", "Desc 1", "Desc 2", "Desc 3", "EAN", "Unidad", "Rubro"),
            fila("A-1", "Tornillo", 100, 21, -5, 3, null, "7791234567890", "u", "Tornillería")));

        ReconocedorDeColumnas.Propuesta propuesta = ReconocedorDeColumnas.reconocer(lectura).propuesta();

        assertThat(propuesta.columnaIva()).isEqualTo(3);
        assertThat(propuesta.columnaAjuste1()).isEqualTo(4);
        assertThat(propuesta.columnaAjuste2()).isEqualTo(5);
        assertThat(propuesta.columnaAjuste3()).isEqualTo(6);
        assertThat(propuesta.columnaCodigoBarras()).isEqualTo(7);
        assertThat(propuesta.columnaUnidad()).isEqualTo(8);
        assertThat(propuesta.columnaRubro()).isEqualTo(9);
    }

    @Test
    @DisplayName("la propuesta solo depende del archivo: es la misma sin importar nada de afuera")
    void soloDependeDelArchivo() {
        LecturaLista lectura = ListasReales.leida(ListasReales.lista336ChicaHojas());

        assertThat(ReconocedorDeColumnas.reconocer(lectura)).isEqualTo(ReconocedorDeColumnas.reconocer(lectura));
        assertThat(ReconocedorDeColumnas.reconocer(lectura))
            .isEqualTo(ReconocedorDeColumnas.reconocer(ListasReales.leida(ListasReales.lista336ChicaHojas())));
    }

    // ---- M-S: encabezado de dos filas, y varias hojas (T-112) ----

    private static LecturaLista ms() {
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put("M-S ", ListasReales.msChicaFilas());
        return ListasReales.leida(hojas);
    }

    @Test
    @DisplayName("M-S: Precio X + Unidad. es el costo (columna C), codigo A, descripcion B y datos desde la 15")
    void msEncabezadoDeDosFilas() {
        ReconocedorDeColumnas.Reconocimiento reconocimiento = ReconocedorDeColumnas.reconocer(ms());
        ReconocedorDeColumnas.Propuesta propuesta = reconocimiento.propuesta();

        assertThat(propuesta.columnaCodigo()).isZero();
        assertThat(propuesta.columnaDescripcion()).isEqualTo(1);
        assertThat(propuesta.columnaCosto()).isEqualTo(2);
        assertThat(propuesta.filaDesde()).isEqualTo(15);
        assertThat(propuesta.hoja()).isZero();
        assertThat(reconocimiento.hojas()).hasSize(1);
        assertThat(reconocimiento.hojas().get(0).nombre()).isEqualTo("M-S ");
        assertThat(reconocimiento.hojas().get(0).filaEncabezado()).isEqualTo(14);
        assertThat(reconocimiento.hojas().get(0).primeraFilaDeDatos()).isEqualTo(15);
    }

    @Test
    @DisplayName("M-S: Cantidad x Caja y Precio Final no se proponen, y no hay moneda ni IVA")
    void msNoProponeLoDemas() {
        ReconocedorDeColumnas.Propuesta propuesta = ReconocedorDeColumnas.reconocer(ms()).propuesta();

        // Cantidad = D (3) y "Precio / Final" = F (5): ningun campo cae en ellas.
        List<Integer> columnas = Arrays.asList(propuesta.columnaCodigo(), propuesta.columnaDescripcion(),
            propuesta.columnaCosto(), propuesta.columnaMoneda(), propuesta.columnaSinIva(), propuesta.columnaConIva(),
            propuesta.columnaIva(), propuesta.columnaAjuste1(), propuesta.columnaAjuste2(), propuesta.columnaAjuste3(),
            propuesta.columnaCodigoBarras(), propuesta.columnaUnidad(), propuesta.columnaRubro());
        assertThat(columnas).doesNotContain(3, 5);
        assertThat(propuesta.columnaMoneda()).isNull();
        assertThat(propuesta.columnaSinIva()).isNull();
        assertThat(propuesta.columnaConIva()).isNull();
        assertThat(propuesta.columnaIva()).isNull();
        assertThat(propuesta.columnaUnidad()).isNull();
        assertThat(propuesta.columnaRubro()).isNull();
    }

    @Test
    @DisplayName("M-S: el Familia: 001 de la fila 14 en la columna A no rompe el reconocimiento del codigo")
    void msTituloEnLaFilaDelEncabezado() {
        assertThat(ReconocedorDeColumnas.reconocer(ms()).propuesta().columnaCodigo()).isZero();
    }

    @Test
    @DisplayName("M-S: un encabezado de dos filas cualquiera (Precio / Unitario) tambien se reconoce")
    void dosFilasGenerico() {
        LecturaLista lectura = lecturaDe("Hoja1", List.of(
            fila("Código", "Descripción", "Precio", "Cant."),
            fila(null, null, "Unitario", "x Caja"),
            fila("A-1", "Tornillo", 100, 10)));

        ReconocedorDeColumnas.Reconocimiento reconocimiento = ReconocedorDeColumnas.reconocer(lectura);

        assertThat(reconocimiento.propuesta().columnaCosto()).isEqualTo(2);
        assertThat(reconocimiento.propuesta().filaDesde()).isEqualTo(3);
        assertThat(reconocimiento.hojas().get(0).filaEncabezado()).isEqualTo(2);
    }

    @Test
    @DisplayName("con varias hojas devuelve una entrada por hoja con su fila de encabezado")
    void unaEntradaPorHoja() {
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put("UNO", List.of(
            fila("Código", "Artículo", "Lista"), fila("A-1", "Tornillo", 100)));
        hojas.put("DOS", List.of(
            fila("Ferretería"), fila(), fila("Código", "Artículo", "Lista"), fila("B-1", "Tuerca", 200)));
        hojas.put("TRES", List.of(
            fila("xx", "yy"), fila("C-1", "Arandela", 300)));

        ReconocedorDeColumnas.Reconocimiento reconocimiento = ReconocedorDeColumnas.reconocer(ListasReales.leida(hojas));

        assertThat(reconocimiento.hojas()).extracting(ReconocedorDeColumnas.HojaReconocida::indice)
            .containsExactly(0, 1, 2);
        assertThat(reconocimiento.hojas()).extracting(ReconocedorDeColumnas.HojaReconocida::nombre)
            .containsExactly("UNO", "DOS", "TRES");
        assertThat(reconocimiento.hojas()).extracting(ReconocedorDeColumnas.HojaReconocida::filaEncabezado)
            .containsExactly(1, 3, null);
    }

    @Test
    @DisplayName("las hojas propuestas son las que tienen encabezado reconocido")
    void hojasPropuestas() {
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put("UNO", List.of(fila("Código", "Artículo", "Lista"), fila("A-1", "Tornillo", 100)));
        hojas.put("DOS", List.of(fila("xx", "yy"), fila("C-1", "Arandela", 300)));
        hojas.put("TRES", List.of(fila("Código", "Artículo", "Lista"), fila("B-1", "Tuerca", 200)));

        ReconocedorDeColumnas.Propuesta propuesta = ReconocedorDeColumnas.reconocer(ListasReales.leida(hojas)).propuesta();

        assertThat(propuesta.hojas()).containsExactly(0, 2);
        assertThat(propuesta.hoja()).isZero();
        assertThat(ReconocedorDeColumnas.reconocer(ListasReales.leida(ListasReales.lista336ChicaHojas())).propuesta().hojas())
            .containsExactly(0, 1, 2);
    }

    @Test
    @DisplayName("si la primera hoja no tiene encabezado, la propuesta sale de la primera que si")
    void propuestaDeLaPrimeraHojaReconocida() {
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put("PORTADA", List.of(fila("Lista de precios")));
        hojas.put("DATOS", List.of(fila("Ferretería"), fila("Código", "Artículo", "Costo"), fila("B-1", "Tuerca", 200)));

        ReconocedorDeColumnas.Propuesta propuesta = ReconocedorDeColumnas.reconocer(ListasReales.leida(hojas)).propuesta();

        assertThat(propuesta.hoja()).isEqualTo(1);
        assertThat(propuesta.hojas()).containsExactly(1);
        assertThat(propuesta.filaDesde()).isEqualTo(3);
        assertThat(propuesta.columnaCosto()).isEqualTo(2);
    }

    @Test
    @DisplayName("un encabezado parcial (sin costo) propone lo que reconoce, y no marca la hoja como propuesta")
    void encabezadoParcial() {
        LecturaLista lectura = lecturaDe("Hoja1", List.of(
            fila("Código", "Descripción", "Importe"),
            fila("A-1", "Tornillo", 100)));

        ReconocedorDeColumnas.Reconocimiento reconocimiento = ReconocedorDeColumnas.reconocer(lectura);

        assertThat(reconocimiento.propuesta().columnaCodigo()).isZero();
        assertThat(reconocimiento.propuesta().columnaDescripcion()).isEqualTo(1);
        assertThat(reconocimiento.propuesta().columnaCosto()).isNull();
        assertThat(reconocimiento.propuesta().hojas()).isEmpty();
        assertThat(reconocimiento.hojas().get(0).filaEncabezado()).isEqualTo(1);
    }
}
