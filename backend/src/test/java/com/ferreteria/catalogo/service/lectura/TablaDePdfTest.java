package com.ferreteria.catalogo.service.lectura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.catalogo.model.Celda;
import com.ferreteria.catalogo.model.FilaLeida;
import com.ferreteria.common.exception.BadRequestException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class TablaDePdfTest {

    /** Letra de 10 pt: cada caracter mide 5 pt y entre palabras hay 3 pt (menos que la separacion de bloques). */
    private static final double TAMANO = 10;
    private static final double ANCHO_LETRA = 5;
    private static final double ESPACIO = 3;

    /** Una linea de la pagina: pares (x donde arranca el texto, texto). Las palabras del texto van seguidas. */
    private static List<PalabraPdf> linea(int pagina, double base, Object... xYTexto) {
        List<PalabraPdf> palabras = new ArrayList<>();
        for (int i = 0; i < xYTexto.length; i += 2) {
            double x = ((Number) xYTexto[i]).doubleValue();
            for (String palabra : ((String) xYTexto[i + 1]).split(" ")) {
                double derecha = x + palabra.length() * ANCHO_LETRA;
                palabras.add(new PalabraPdf(pagina, x, derecha, base, TAMANO, palabra));
                x = derecha + ESPACIO;
            }
        }
        return palabras;
    }

    /** Un texto alineado a la derecha en {@code borde}. */
    private static double aLaDerecha(double borde, String texto) {
        return borde - texto.length() * ANCHO_LETRA - (texto.split(" ").length - 1) * (ESPACIO - ANCHO_LETRA);
    }

    private static List<String> textos(FilaLeida fila) {
        return fila.celdas().stream().map(Celda::texto).toList();
    }

    @Test
    void cadaLineaEsUnaFilaDeLaHojaCeroYCadaPalabraVaASuColumna() {
        List<PalabraPdf> palabras = new ArrayList<>();
        palabras.addAll(linea(1, 100, 10, "Codigo", 60, "Descripcion", 200, "Precio"));
        palabras.addAll(linea(1, 115, 10, "A-1", 60, "Tornillo largo", 200, "12,50"));
        palabras.addAll(linea(1, 130, 10, "A-2", 60, "Tuerca", 200, "100"));

        List<FilaLeida> filas = TablaDePdf.armar(palabras);

        assertThat(filas).extracting(FilaLeida::hoja).containsOnly(0);
        assertThat(filas).extracting(FilaLeida::numero).containsExactly(1, 2, 3);
        assertThat(textos(filas.get(0))).containsExactly("Codigo", "Descripcion", "Precio");
        assertThat(textos(filas.get(1))).containsExactly("A-1", "Tornillo largo", "12,50");
        assertThat(textos(filas.get(2))).containsExactly("A-2", "Tuerca", "100");
    }

    @Test
    void lasPalabrasDeUnaLineaSeOrdenanPorPosicionAunqueVenganDesordenadas() {
        List<PalabraPdf> palabras = new ArrayList<>(linea(1, 100, 10, "A-1", 60, "Tornillo", 200, "12,50"));
        palabras.addAll(linea(1, 115, 10, "A-2", 60, "Tuerca", 200, "100"));
        List<PalabraPdf> desordenadas = new ArrayList<>(palabras);
        Collections.reverse(desordenadas);

        assertThat(TablaDePdf.armar(desordenadas)).isEqualTo(TablaDePdf.armar(palabras));
    }

    @Test
    void unaLineaDeUnSoloBloqueVaEnteraALaPrimeraColumna() {
        List<PalabraPdf> palabras = new ArrayList<>();
        palabras.addAll(linea(1, 100, 200, "CATALOGO DE PRECIOS"));
        palabras.addAll(linea(1, 115, 10, "A-1", 60, "Tornillo", 200, "12,50"));
        palabras.addAll(linea(1, 130, 10, "ACCESORIOS DE RIEGO"));
        palabras.addAll(linea(1, 145, 10, "A-2", 60, "Tuerca", 200, "100"));

        List<FilaLeida> filas = TablaDePdf.armar(palabras);

        assertThat(textos(filas.get(0))).containsExactly("CATALOGO DE PRECIOS");
        assertThat(textos(filas.get(2))).containsExactly("ACCESORIOS DE RIEGO");
        assertThat(textos(filas.get(3))).containsExactly("A-2", "Tuerca", "100");
    }

    @Test
    void lasPaginasSeLeenSeguidasConLaMismaNumeracion() {
        List<PalabraPdf> palabras = new ArrayList<>();
        palabras.addAll(linea(2, 100, 10, "A-2", 60, "Tuerca", 200, "100"));
        palabras.addAll(linea(1, 100, 10, "A-1", 60, "Tornillo", 200, "12,50"));
        palabras.addAll(linea(1, 115, 10, "A-3", 60, "Arandela", 200, "5"));

        List<FilaLeida> filas = TablaDePdf.armar(palabras);

        assertThat(filas).extracting(FilaLeida::numero).containsExactly(1, 2, 3);
        assertThat(filas).extracting(fila -> fila.celda(0).texto()).containsExactly("A-1", "A-3", "A-2");
    }

    @Test
    void palabrasConLaBaseApenasCorridaSonDeLaMismaLinea() {
        List<PalabraPdf> palabras = new ArrayList<>();
        palabras.addAll(linea(1, 100, 10, "A-1", 60, "Tornillo"));
        palabras.addAll(linea(1, 101, 200, "12,50"));
        palabras.addAll(linea(1, 115, 10, "A-2", 60, "Tuerca", 200, "100"));

        List<FilaLeida> filas = TablaDePdf.armar(palabras);

        assertThat(filas).hasSize(2);
        assertThat(textos(filas.get(0))).containsExactly("A-1", "Tornillo", "12,50");
    }

    @Test
    void unHuecoQueTapanPocasLineasSigueSeparandoColumnas() {
        // Como en M-S: el codigo y la descripcion quedan a un espacio comun, y dos notas de varios bloques tapan
        // ese hueco. Con 200 filas, dos lineas (el 1 %) no alcanzan para borrarlo. Las descripciones varian, como en
        // una lista real: si no, el espacio entre dos palabras que todas repiten en el mismo lugar tambien es un hueco.
        List<String> descripciones =
            List.of("Pasador Cerrojo", "Bisagra Munich comun", "Tarugo nylon", "Candado de bronce 40mm");
        List<PalabraPdf> palabras = new ArrayList<>();
        palabras.addAll(linea(1, 10, 0, "Pago Contado efectivo o cheque", 300, "Descuento"));
        palabras.addAll(linea(1, 25, 0, "Pago a partir de 20 dias", 300, "Neto"));
        for (int i = 0; i < 200; i++) {
            palabras.addAll(linea(1, 40 + i * 15, 0, "PA0" + (3000 + i), 38, descripciones.get(i % 4), 300, "1,532.44"));
        }

        List<FilaLeida> filas = TablaDePdf.armar(palabras);

        assertThat(textos(filas.get(2))).containsExactly("PA03000", "Pasador Cerrojo", "1,532.44");
        assertThat(textos(filas.get(201))).containsExactly("PA03199", "Candado de bronce 40mm", "1,532.44");
    }

    @Test
    void unHuecoAngostoConLaMismaPalabraALaIzquierdaEnTodasLasLineasEsUnEspacioDeLaCelda() {
        // Descripciones que empiezan igual: el espacio despues de «Articulo» cae en el mismo lugar en todas las lineas.
        List<PalabraPdf> palabras = new ArrayList<>();
        for (int i = 1; i <= 40; i++) {
            palabras.addAll(linea(1, 10 + i * 15, 0, "A-" + i, 50, "Articulo " + i, 300, i + ",00"));
        }

        List<FilaLeida> filas = TablaDePdf.armar(palabras);

        assertThat(textos(filas.get(39))).containsExactly("A-40", "Articulo 40", "40,00");
    }

    @Test
    void unTextoQuePisaElBordeDeLaColumnaSiguienteSigueEnSuColumna() {
        // Como en M-S: «Familia: 001 - Pasadores» comparte el renglon con «Unidad.» y «x Caja» del encabezado, asi que
        // la linea es de varios bloques; «001» ya pisa la columna de la descripcion y lo que sigue pegado es del titulo.
        List<String> descripciones = List.of("Pasador Cerrojo", "Bisagra Munich", "Tarugo nylon", "Candado bronce");
        List<PalabraPdf> palabras = new ArrayList<>(
            linea(1, 10, 0, "Familia: 001 - Pasadores", 300, "Unidad.", 350, "x Caja"));
        for (int i = 0; i < 100; i++) {
            palabras.addAll(linea(1, 25 + i * 15, 0, "PA0" + (3000 + i), 38, descripciones.get(i % 4), 300, "1,532.44",
                350, "12"));
        }

        List<FilaLeida> filas = TablaDePdf.armar(palabras);

        assertThat(textos(filas.get(0))).containsExactly("Familia: 001 - Pasadores", "", "Unidad.", "x Caja");
        assertThat(textos(filas.get(1))).containsExactly("PA03000", "Pasador Cerrojo", "1,532.44", "12");
    }

    @Test
    void laColaDeUnTextoLargoQuedaEnSuColumnaAunqueEntreEnElHueco() {
        List<PalabraPdf> palabras = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            palabras.addAll(linea(1, 10 + i * 15, 0, "A-" + (100 + i), 50, "Tornillo", 300, "12,50"));
        }
        palabras.addAll(linea(1, 1600, 0, "A-999", 50, "Tornillo autoperforante con arandela y cabeza", 300, "99,00"));

        List<FilaLeida> filas = TablaDePdf.armar(palabras);

        assertThat(textos(filas.get(100)))
            .containsExactly("A-999", "Tornillo autoperforante con arandela y cabeza", "99,00");
        assertThat(textos(filas.get(0))).containsExactly("A-100", "Tornillo", "12,50");
    }

    @Test
    void unImporteLargoQueEmpiezaEnElHuecoVaASuColumna() {
        // Importes alineados a la derecha: el largo arranca antes que los demas, en el hueco de la columna anterior.
        List<PalabraPdf> palabras = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            palabras.addAll(linea(1, 10 + i * 15, 0, "A-" + (100 + i), 50, "Tornillo", aLaDerecha(400, "5.757,00"),
                "5.757,00"));
        }
        palabras.addAll(linea(1, 1600, 0, "A-999", 50, "Clavos", aLaDerecha(400, "11.567.560,00"), "11.567.560,00"));

        List<FilaLeida> filas = TablaDePdf.armar(palabras);

        assertThat(textos(filas.get(100))).containsExactly("A-999", "Clavos", "11.567.560,00");
    }

    @Test
    void unaColumnaDebilSeUneConLaVecinaMasCercana() {
        // Como en LISTA 336: el encabezado «Bulto (u.)» de cada pagina es mas ancho que los valores de su columna
        // y, sin unirse, quedaria partido en dos columnas.
        List<PalabraPdf> palabras = new ArrayList<>();
        for (int pagina = 1; pagina <= 10; pagina++) {
            palabras.addAll(linea(pagina, 10, 0, "Codigo", 50, "Articulo", 300, "Bulto", 330, "(u.)"));
            for (int i = 0; i < 20; i++) {
                palabras.addAll(linea(pagina, 25 + i * 15, 0, "A-" + (100 + i), 50, "Tornillo", 335, "-"));
            }
        }

        List<FilaLeida> filas = TablaDePdf.armar(palabras);

        assertThat(textos(filas.get(0))).containsExactly("Codigo", "Articulo", "Bulto (u.)");
        assertThat(textos(filas.get(1))).containsExactly("A-100", "Tornillo", "-");
    }

    @Test
    void elSignoDeMonedaEnSuPropiaColumnaSeUneConElImporte() {
        List<PalabraPdf> palabras = new ArrayList<>(linea(1, 10, 0, "Codigo", 50, "Descripcion", 330, "Precio"));
        for (int i = 0; i < 20; i++) {
            palabras.addAll(linea(1, 25 + i * 15, 0, "A-" + (100 + i), 50, "Tornillo", 300, "$", 330, "1,532.44"));
        }

        List<FilaLeida> filas = TablaDePdf.armar(palabras);

        assertThat(textos(filas.get(0))).containsExactly("Codigo", "Descripcion", "Precio");
        assertThat(textos(filas.get(1))).containsExactly("A-100", "Tornillo", "$ 1,532.44");
    }

    @Test
    void unSignoDeMonedaPegadoAlImportePorUnEspacioNoParteLaCelda() {
        // Como en LISTA 336: «$ 5.757,00» alineado a la derecha. El espacio entre el signo y los digitos queda en el
        // mismo lugar en casi todas las filas y parece un hueco; el importe mas largo arranca antes y queda entero.
        List<PalabraPdf> palabras = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            palabras.addAll(linea(1, 10 + i * 15, 0, "A-" + (100 + i), 50, "Tornillo", aLaDerecha(400, "$ 5.757,00"),
                "$ 5.757,00"));
        }
        palabras.addAll(linea(1, 1600, 0, "A-999", 50, "Clavos", aLaDerecha(400, "US$ 662.286,00"),
            "US$ 662.286,00"));

        List<FilaLeida> filas = TablaDePdf.armar(palabras);

        assertThat(textos(filas.get(0))).containsExactly("A-100", "Tornillo", "$ 5.757,00");
        assertThat(textos(filas.get(100))).containsExactly("A-999", "Clavos", "US$ 662.286,00");
    }

    @Test
    void sinPalabrasNoHayFilas() {
        assertThat(TablaDePdf.armar(List.of())).isEmpty();
    }

    @Test
    void masLineasQueElTopeEsUnaListaConDemasiadasFilas() {
        List<PalabraPdf> palabras = new ArrayList<>();
        for (int i = 0; i <= LimitesDeLista.MAXIMO_FILAS_LEIDAS; i++) {
            palabras.add(new PalabraPdf(1 + i / 50, 0, 25, 10 + (i % 50) * 15, TAMANO, "A-" + i));
        }

        assertThatThrownBy(() -> TablaDePdf.armar(palabras))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("La lista tiene más de 20.000 filas. Dividila en archivos más chicos.");
    }
}
