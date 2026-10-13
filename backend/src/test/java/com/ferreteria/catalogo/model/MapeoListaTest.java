package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

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
}
