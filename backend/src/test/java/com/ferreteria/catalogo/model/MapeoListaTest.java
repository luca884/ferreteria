package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Encabezados del mapeo: lectura al guardar y advertencias cuando cambian. */
class MapeoListaTest {

    private static FilaLeida fila(int numero, String... textos) {
        List<Celda> celdas = new ArrayList<>();
        for (String texto : textos) {
            celdas.add(texto == null ? Celda.VACIA : new Celda(texto, null));
        }
        return new FilaLeida(numero, celdas);
    }

    private static MapeoLista mapeo(int filaDesde, int codigo, Integer descripcion, int costo,
                                    MapeoLista.Encabezados encabezados) {
        return new MapeoLista(0, filaDesde, codigo, descripcion, costo, encabezados);
    }

    private static MapeoLista guardado(String codigo, String descripcion, String costo) {
        return mapeo(2, 0, 1, 2, new MapeoLista.Encabezados(codigo, descripcion, costo));
    }

    // conEncabezados

    @Test
    @DisplayName("conEncabezados lee los textos de las columnas mapeadas de la fila de encabezado")
    void leeLosTextos() {
        MapeoLista mapeo = mapeo(2, 0, 1, 2, null);

        MapeoLista conEncabezados = mapeo.conEncabezados(fila(1, "Codigo", "Detalle", "Precio"));

        assertThat(conEncabezados.encabezados())
            .isEqualTo(new MapeoLista.Encabezados("Codigo", "Detalle", "Precio"));
        assertThat(conEncabezados.hoja()).isEqualTo(mapeo.hoja());
        assertThat(conEncabezados.filaDesde()).isEqualTo(2);
        assertThat(conEncabezados.columnaCodigo()).isZero();
        assertThat(conEncabezados.columnaDescripcion()).isEqualTo(1);
        assertThat(conEncabezados.columnaCosto()).isEqualTo(2);
    }

    @Test
    @DisplayName("conEncabezados respeta las columnas mapeadas aunque no esten en orden")
    void respetaLasColumnas() {
        MapeoLista mapeo = mapeo(3, 2, 0, 1, null);

        MapeoLista conEncabezados = mapeo.conEncabezados(fila(2, "Detalle", "Precio", "Codigo"));

        assertThat(conEncabezados.encabezados())
            .isEqualTo(new MapeoLista.Encabezados("Codigo", "Detalle", "Precio"));
    }

    @Test
    @DisplayName("conEncabezados recorta los espacios del texto")
    void recortaEspacios() {
        MapeoLista mapeo = mapeo(2, 0, 1, 2, null);

        MapeoLista conEncabezados = mapeo.conEncabezados(fila(1, "  Codigo ", "Detalle", "Precio  "));

        assertThat(conEncabezados.encabezados())
            .isEqualTo(new MapeoLista.Encabezados("Codigo", "Detalle", "Precio"));
    }

    @Test
    @DisplayName("sin columna de descripcion, el encabezado de descripcion es null")
    void sinColumnaDeDescripcion() {
        MapeoLista mapeo = mapeo(2, 0, null, 2, null);

        MapeoLista conEncabezados = mapeo.conEncabezados(fila(1, "Codigo", "Detalle", "Precio"));

        assertThat(conEncabezados.encabezados())
            .isEqualTo(new MapeoLista.Encabezados("Codigo", null, "Precio"));
    }

    @Test
    @DisplayName("una celda de encabezado vacia o fuera de rango queda en null")
    void celdaVaciaQuedaNull() {
        MapeoLista mapeo = mapeo(2, 0, 1, 5, null);

        MapeoLista conEncabezados = mapeo.conEncabezados(fila(1, "Codigo", null));

        assertThat(conEncabezados.encabezados())
            .isEqualTo(new MapeoLista.Encabezados("Codigo", null, null));
    }

    @Test
    @DisplayName("filaDesde = 1 no tiene fila de encabezado: encabezados null aunque se pase una fila")
    void filaDesdeUnoNoTieneEncabezado() {
        MapeoLista mapeo = mapeo(1, 0, 1, 2, null);

        assertThat(mapeo.conEncabezados(fila(1, "Codigo", "Detalle", "Precio")).encabezados()).isNull();
        assertThat(mapeo.conEncabezados(null).encabezados()).isNull();
    }

    @Test
    @DisplayName("sin fila de encabezado (null) los encabezados quedan en null")
    void sinFilaDeEncabezado() {
        MapeoLista mapeo = mapeo(3, 0, 1, 2, new MapeoLista.Encabezados("viejo", "viejo", "viejo"));

        assertThat(mapeo.conEncabezados(null).encabezados()).isNull();
    }

    // advertenciasContra

    @Test
    @DisplayName("si el texto del encabezado del costo cambio, hay una advertencia con el texto anterior y el actual")
    void advierteCambioDelCosto() {
        MapeoLista actual = mapeo(2, 0, 1, 2, null).conEncabezados(fila(1, "Codigo", "Detalle", "Costo"));

        List<String> advertencias = actual.advertenciasContra(guardado("Codigo", "Detalle", "Precio"));

        assertThat(advertencias).containsExactly(
            "El encabezado de la columna del costo cambió: antes decía «Precio» y ahora dice «Costo». Revisá el mapeo.");
    }

    @Test
    @DisplayName("hay una advertencia por cada columna que cambio")
    void unaAdvertenciaPorColumna() {
        MapeoLista actual = mapeo(2, 0, 1, 2, null).conEncabezados(fila(1, "Cod", "Articulo", "Costo"));

        List<String> advertencias = actual.advertenciasContra(guardado("Codigo", "Detalle", "Precio"));

        assertThat(advertencias).containsExactly(
            "El encabezado de la columna del código cambió: antes decía «Codigo» y ahora dice «Cod». Revisá el mapeo.",
            "El encabezado de la columna de la descripción cambió: antes decía «Detalle» y ahora dice «Articulo». Revisá el mapeo.",
            "El encabezado de la columna del costo cambió: antes decía «Precio» y ahora dice «Costo». Revisá el mapeo.");
    }

    @Test
    @DisplayName("no distingue mayusculas ni espacios sobrantes")
    void ignoraMayusculasYEspacios() {
        MapeoLista actual = mapeo(2, 0, 1, 2, null).conEncabezados(fila(1, " CODIGO ", "detalle", "PRECIO lista"));

        assertThat(actual.advertenciasContra(guardado("codigo", "DETALLE ", "precio lista"))).isEmpty();
    }

    @Test
    @DisplayName("los acentos no cuentan como diferencia (misma clave de comparacion)")
    void ignoraAcentos() {
        MapeoLista actual = mapeo(2, 0, 1, 2, null).conEncabezados(fila(1, "Código", "Detalle", "Precio"));

        assertThat(actual.advertenciasContra(guardado("Codigo", "Detalle", "Precio"))).isEmpty();
    }

    @Test
    @DisplayName("sin mapeo guardado no hay advertencias")
    void sinMapeoGuardado() {
        MapeoLista actual = mapeo(2, 0, 1, 2, null).conEncabezados(fila(1, "Codigo", "Detalle", "Costo"));

        assertThat(actual.advertenciasContra(null)).isEmpty();
    }

    @Test
    @DisplayName("el mapeo guardado sin encabezados (filaDesde era 1) no genera advertencias")
    void guardadoSinEncabezados() {
        MapeoLista actual = mapeo(2, 0, 1, 2, null).conEncabezados(fila(1, "Codigo", "Detalle", "Costo"));

        assertThat(actual.advertenciasContra(mapeo(1, 0, 1, 2, null))).isEmpty();
    }

    @Test
    @DisplayName("sin fila de encabezado en el archivo actual no hay advertencias")
    void actualSinEncabezados() {
        MapeoLista actual = mapeo(2, 0, 1, 2, null).conEncabezados(null);

        assertThat(actual.advertenciasContra(guardado("Codigo", "Detalle", "Precio"))).isEmpty();
    }

    @Test
    @DisplayName("solo compara las columnas que el mapeo guardado usa igual: si el costo cambio de columna no advierte el costo")
    void soloMismasColumnas() {
        // Actual: costo en la columna 3. Guardado: costo en la columna 2. Codigo y descripcion igual.
        MapeoLista actual = mapeo(2, 0, 1, 3, null).conEncabezados(fila(1, "Codigo", "Detalle", "X", "Costo"));

        assertThat(actual.advertenciasContra(guardado("Codigo", "Detalle", "Precio"))).isEmpty();
    }

    @Test
    @DisplayName("si el guardado no tenia columna de descripcion no se compara la descripcion")
    void descripcionNoMapeadaEnElGuardado() {
        MapeoLista guardado = mapeo(2, 0, null, 2, new MapeoLista.Encabezados("Codigo", null, "Precio"));
        MapeoLista actual = mapeo(2, 0, 1, 2, null).conEncabezados(fila(1, "Codigo", "Detalle", "Precio"));

        assertThat(actual.advertenciasContra(guardado)).isEmpty();
    }

    @Test
    @DisplayName("si el texto guardado de una columna era null no hay con que comparar")
    void textoGuardadoNull() {
        MapeoLista guardado = mapeo(2, 0, 1, 2, new MapeoLista.Encabezados(null, "Detalle", null));
        MapeoLista actual = mapeo(2, 0, 1, 2, null).conEncabezados(fila(1, "Codigo", "Detalle", "Precio"));

        assertThat(actual.advertenciasContra(guardado)).isEmpty();
    }

    @Test
    @DisplayName("si ahora la celda de encabezado esta vacia y antes tenia texto, advierte")
    void encabezadoActualVacio() {
        MapeoLista actual = mapeo(2, 0, 1, 2, null).conEncabezados(fila(1, "Codigo", "Detalle", null));

        assertThat(actual.advertenciasContra(guardado("Codigo", "Detalle", "Precio"))).containsExactly(
            "El encabezado de la columna del costo cambió: antes decía «Precio» y ahora dice «». Revisá el mapeo.");
    }

    @Test
    @DisplayName("el mapeo no cambia: advertenciasContra es una consulta pura")
    void noMutaElMapeo() {
        MapeoLista actual = mapeo(2, 0, 1, 2, null).conEncabezados(fila(1, "Codigo", "Detalle", "Costo"));
        MapeoLista antes = actual;

        actual.advertenciasContra(guardado("Codigo", "Detalle", "Precio"));

        assertThat(actual).isEqualTo(antes);
    }

    // ---- corte 2: campos opcionales y retrocompatibilidad del JSON guardado ----

    /** Como lo lee Hibernate: un ObjectMapper pelado, que falla con propiedades desconocidas. */
    private static final ObjectMapper JSON = new ObjectMapper();

    /** El JSON de dev-data V1002__importacion_demo.sql:11, guardado antes del corte 2. */
    private static final String JSON_VIEJO = "{\"hoja\":0,\"filaDesde\":2,\"columnaCodigo\":0,\"columnaDescripcion\":1,"
        + "\"columnaCosto\":2,\"encabezados\":{\"codigo\":\"Codigo\",\"descripcion\":\"Descripcion\",\"costo\":\"Precio\"}}";

    private static final String JSON_COMPLETO = "{\"hoja\":1,\"filaDesde\":3,\"columnaCodigo\":0,\"columnaDescripcion\":1,"
        + "\"columnaCosto\":5,\"encabezados\":{\"codigo\":\"Código\",\"descripcion\":\"Artículo\",\"costo\":\"Lista (unidad)\","
        + "\"moneda\":\"Mon.\",\"sinIva\":\"Sin IVA\",\"conIva\":\"Con IVA\",\"iva\":\"% IVA\",\"ajuste1\":\"Desc 1\","
        + "\"ajuste2\":\"Desc 2\",\"ajuste3\":\"Desc 3\",\"codigoBarras\":\"EAN\",\"unidad\":\"Un.\",\"rubro\":\"Familia\"},"
        + "\"hojas\":[0,1,4],\"columnaMoneda\":4,\"columnaSinIva\":7,\"columnaConIva\":8,\"columnaIva\":9,"
        + "\"columnaAjuste1\":10,\"columnaAjuste2\":11,\"columnaAjuste3\":12,\"columnaCodigoBarras\":13,"
        + "\"columnaUnidad\":14,\"columnaRubro\":15}";

    @Test
    @DisplayName("el JSON viejo de V1002 se lee con hojas y todos los campos nuevos en null")
    void leeElJsonViejo() throws Exception {
        MapeoLista mapeo = JSON.readValue(JSON_VIEJO, MapeoLista.class);

        assertThat(mapeo.hoja()).isZero();
        assertThat(mapeo.filaDesde()).isEqualTo(2);
        assertThat(mapeo.columnaCodigo()).isZero();
        assertThat(mapeo.columnaDescripcion()).isEqualTo(1);
        assertThat(mapeo.columnaCosto()).isEqualTo(2);
        assertThat(mapeo.encabezados()).isEqualTo(new MapeoLista.Encabezados("Codigo", "Descripcion", "Precio"));
        assertThat(mapeo.hojas()).isNull();
        assertThat(mapeo.columnaMoneda()).isNull();
        assertThat(mapeo.monedaFija()).isNull();
        assertThat(mapeo.columnaSinIva()).isNull();
        assertThat(mapeo.columnaConIva()).isNull();
        assertThat(mapeo.columnaIva()).isNull();
        assertThat(mapeo.columnaAjuste1()).isNull();
        assertThat(mapeo.columnaAjuste2()).isNull();
        assertThat(mapeo.columnaAjuste3()).isNull();
        assertThat(mapeo.columnaCodigoBarras()).isNull();
        assertThat(mapeo.columnaUnidad()).isNull();
        assertThat(mapeo.columnaRubro()).isNull();
        assertThat(mapeo.hojasElegidas()).containsExactly(0);
    }

    @Test
    @DisplayName("al serializar el mapeo viejo no inventa campos y conserva los viejos")
    void noInventaCampos() throws Exception {
        MapeoLista mapeo = JSON.readValue(JSON_VIEJO, MapeoLista.class);

        JsonNode escrito = JSON.readTree(JSON.writeValueAsString(mapeo));

        assertThat(escrito).isEqualTo(JSON.readTree(JSON_VIEJO));
        assertThat(escrito.has("hojas")).isFalse();
        assertThat(escrito.has("columnaMoneda")).isFalse();
        assertThat(escrito.get("encabezados").has("moneda")).isFalse();
    }

    @Test
    @DisplayName("el constructor de 6 argumentos sigue existiendo y deja los campos nuevos en null")
    void constructorViejo() {
        MapeoLista mapeo = new MapeoLista(1, 3, 0, null, 2, null);

        assertThat(mapeo.hoja()).isEqualTo(1);
        assertThat(mapeo.columnaDescripcion()).isNull();
        assertThat(mapeo.hojas()).isNull();
        assertThat(mapeo.columnaAjuste1()).isNull();
        assertThat(mapeo.hojasElegidas()).containsExactly(1);
        assertThat(new MapeoLista.Encabezados("a", "b", "c").moneda()).isNull();
    }

    @Test
    @DisplayName("claves desconocidas (stock, marca, una clave futura) se leen sin error y se ignoran")
    void ignoraClavesDesconocidas() throws Exception {
        String conClavesExtra = JSON_VIEJO.substring(0, JSON_VIEJO.length() - 1)
            + ",\"columnaStock\":5,\"columnaMarca\":6,\"claveFutura\":{\"x\":1}}";

        MapeoLista mapeo = JSON.readValue(conClavesExtra, MapeoLista.class);

        assertThat(mapeo).isEqualTo(JSON.readValue(JSON_VIEJO, MapeoLista.class));
        assertThat(JSON.writeValueAsString(mapeo)).doesNotContain("columnaStock", "columnaMarca", "claveFutura");
    }

    @Test
    @DisplayName("el JSON completo con los campos nuevos hace ida y vuelta")
    void idaYVuelta() throws Exception {
        MapeoLista mapeo = JSON.readValue(JSON_COMPLETO, MapeoLista.class);

        assertThat(mapeo.hojas()).containsExactly(0, 1, 4);
        assertThat(mapeo.hojasElegidas()).containsExactly(0, 1, 4);
        assertThat(mapeo.columnaMoneda()).isEqualTo(4);
        assertThat(mapeo.columnaSinIva()).isEqualTo(7);
        assertThat(mapeo.columnaConIva()).isEqualTo(8);
        assertThat(mapeo.columnaIva()).isEqualTo(9);
        assertThat(mapeo.columnaAjuste1()).isEqualTo(10);
        assertThat(mapeo.columnaAjuste2()).isEqualTo(11);
        assertThat(mapeo.columnaAjuste3()).isEqualTo(12);
        assertThat(mapeo.columnaCodigoBarras()).isEqualTo(13);
        assertThat(mapeo.columnaUnidad()).isEqualTo(14);
        assertThat(mapeo.columnaRubro()).isEqualTo(15);
        assertThat(mapeo.encabezados().conIva()).isEqualTo("Con IVA");
        assertThat(mapeo.encabezados().rubro()).isEqualTo("Familia");
        assertThat(JSON.readTree(JSON.writeValueAsString(mapeo))).isEqualTo(JSON.readTree(JSON_COMPLETO));
    }

    @Test
    @DisplayName("la moneda fija se guarda como texto USD y se lee como enum")
    void monedaFijaEnElJson() throws Exception {
        String json = JSON_VIEJO.substring(0, JSON_VIEJO.length() - 1) + ",\"monedaFija\":\"USD\"}";

        MapeoLista mapeo = JSON.readValue(json, MapeoLista.class);

        assertThat(mapeo.monedaFija()).isEqualTo(Moneda.USD);
        assertThat(JSON.readTree(JSON.writeValueAsString(mapeo)).get("monedaFija").asText()).isEqualTo("USD");
    }

    @Test
    @DisplayName("conEncabezados lee tambien los textos de las columnas nuevas y conserva el resto del mapeo")
    void conEncabezadosDeLasColumnasNuevas() throws Exception {
        MapeoLista mapeo = JSON.readValue(JSON_COMPLETO, MapeoLista.class).conEncabezados(
            fila(2, "Código", "Artículo", "Sinónimo", "Stock", "Mon.", "Lista (unidad)", "Bulto", "Sin IVA", "Con IVA"));

        assertThat(mapeo.encabezados().codigo()).isEqualTo("Código");
        assertThat(mapeo.encabezados().costo()).isEqualTo("Lista (unidad)");
        assertThat(mapeo.encabezados().moneda()).isEqualTo("Mon.");
        assertThat(mapeo.encabezados().sinIva()).isEqualTo("Sin IVA");
        assertThat(mapeo.encabezados().conIva()).isEqualTo("Con IVA");
        // Las columnas 9 a 15 no existen en esa fila: sin texto.
        assertThat(mapeo.encabezados().iva()).isNull();
        assertThat(mapeo.encabezados().rubro()).isNull();
        // El resto del mapeo no se pierde.
        assertThat(mapeo.hojas()).containsExactly(0, 1, 4);
        assertThat(mapeo.columnaMoneda()).isEqualTo(4);
        assertThat(mapeo.columnaRubro()).isEqualTo(15);
        assertThat(mapeo.hoja()).isEqualTo(1);
    }

    @Test
    @DisplayName("conEncabezados con filaDesde = 1 deja los encabezados en null y conserva los campos nuevos")
    void conEncabezadosSinFilaConservaLosNuevos() throws Exception {
        MapeoLista mapeo = new MapeoLista(0, 1, 0, 1, 2, null, List.of(0, 2), 4, null, null, null, null,
            null, null, null, null, null, null);

        MapeoLista sinEncabezados = mapeo.conEncabezados(fila(1, "a", "b", "c"));

        assertThat(sinEncabezados.encabezados()).isNull();
        assertThat(sinEncabezados.hojas()).containsExactly(0, 2);
        assertThat(sinEncabezados.columnaMoneda()).isEqualTo(4);
    }

    private static MapeoLista mapeoConIva(MapeoLista.Encabezados encabezados) {
        return new MapeoLista(0, 2, 0, 1, 2, encabezados, null, null, null, 7, 8, null, null, null, null, null, null,
            null);
    }

    private static MapeoLista.Encabezados encabezados(String sinIva, String conIva) {
        return new MapeoLista.Encabezados("Código", "Artículo", "Lista", null, sinIva, conIva, null, null, null, null,
            null, null, null);
    }

    @Test
    @DisplayName("advertenciasContra compara el encabezado del costo con IVA: antes Con IVA y ahora Total")
    void advierteElCambioDelConIva() {
        MapeoLista guardado = mapeoConIva(encabezados("Sin IVA", "Con IVA"));
        MapeoLista actual = mapeoConIva(encabezados("Sin IVA", "Total"));

        assertThat(actual.advertenciasContra(guardado)).containsExactly(
            "El encabezado de la columna del costo con IVA cambió: antes decía «Con IVA» y ahora dice «Total». Revisá el mapeo.");
    }

    @Test
    @DisplayName("advertenciasContra avisa cada columna nueva que cambio, con su nombre")
    void advierteCadaColumnaNueva() {
        MapeoLista.Encabezados antes = new MapeoLista.Encabezados("Código", "Artículo", "Lista", "Mon.", "Sin IVA",
            "Con IVA", "% IVA", "Desc 1", "Desc 2", "Desc 3", "EAN", "Un.", "Familia");
        MapeoLista.Encabezados ahora = new MapeoLista.Encabezados("Código", "Artículo", "Lista", "Moneda", "Neto",
            "Total", "IVA", "D1", "D2", "D3", "Barras", "Unidad", "Rubro");
        MapeoLista guardado = new MapeoLista(0, 2, 0, 1, 2, antes, null, 4, null, 7, 8, 9, 10, 11, 12, 13, 14, 15);
        MapeoLista actual = new MapeoLista(0, 2, 0, 1, 2, ahora, null, 4, null, 7, 8, 9, 10, 11, 12, 13, 14, 15);

        assertThat(actual.advertenciasContra(guardado)).extracting(a -> a.substring(0, a.indexOf(" cambió")))
            .containsExactly(
                "El encabezado de la columna de la moneda",
                "El encabezado de la columna del costo sin IVA",
                "El encabezado de la columna del costo con IVA",
                "El encabezado de la columna del % de IVA",
                "El encabezado de la columna del descuento o recargo 1",
                "El encabezado de la columna del descuento o recargo 2",
                "El encabezado de la columna del descuento o recargo 3",
                "El encabezado de la columna del código de barras",
                "El encabezado de la columna de la unidad",
                "El encabezado de la columna del rubro");
    }

    @Test
    @DisplayName("una columna nueva que cambio de lugar, o sin texto guardado, no advierte")
    void columnaNuevaMovidaNoAdvierte() {
        MapeoLista guardado = mapeoConIva(encabezados("Sin IVA", "Con IVA"));
        MapeoLista.Encabezados otroTexto = encabezados("Neto", "Total");
        MapeoLista conIvaEnOtraColumna = new MapeoLista(0, 2, 0, 1, 2, otroTexto, null, null, null, 7, 9, null, null,
            null, null, null, null, null);
        MapeoLista guardadoSinTexto = mapeoConIva(encabezados(null, null));

        // Sin IVA en el mismo lugar: advierte solo ese. Con IVA se movio a la columna 9: no se compara.
        assertThat(conIvaEnOtraColumna.advertenciasContra(guardado)).hasSize(1)
            .allMatch(a -> a.contains("costo sin IVA"));
        assertThat(mapeoConIva(otroTexto).advertenciasContra(guardadoSinTexto)).isEmpty();
    }

    @Test
    @DisplayName("si el costo sin IVA es la misma columna del costo, no avisa dos veces lo mismo")
    void sinIvaIgualAlCostoNoDuplica() {
        MapeoLista.Encabezados antes = new MapeoLista.Encabezados("Código", "Artículo", "Lista", null, "Lista",
            "Con IVA", null, null, null, null, null, null, null);
        MapeoLista.Encabezados ahora = new MapeoLista.Encabezados("Código", "Artículo", "Precio", null, "Precio",
            "Con IVA", null, null, null, null, null, null, null);
        MapeoLista guardado = new MapeoLista(0, 2, 0, 1, 2, antes, null, null, null, 2, 8, null, null, null, null, null,
            null, null);
        MapeoLista actual = new MapeoLista(0, 2, 0, 1, 2, ahora, null, null, null, 2, 8, null, null, null, null, null,
            null, null);

        assertThat(actual.advertenciasContra(guardado)).containsExactly(
            "El encabezado de la columna del costo cambió: antes decía «Lista» y ahora dice «Precio». Revisá el mapeo.");
    }

    @Test
    @DisplayName("los Encabezados viejos de 3 textos siguen funcionando con advertenciasContra")
    void encabezadosViejos() {
        MapeoLista actual = mapeo(2, 0, 1, 2, null).conEncabezados(fila(1, "Codigo", "Detalle", "Costo"));

        assertThat(actual.advertenciasContra(guardado("Codigo", "Detalle", "Precio"))).hasSize(1);
        assertThat(actual.advertenciasContra(guardado("Codigo", "Detalle", "Costo"))).isEmpty();
    }
}
