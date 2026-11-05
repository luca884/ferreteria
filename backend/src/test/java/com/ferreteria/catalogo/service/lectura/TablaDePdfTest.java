package com.ferreteria.catalogo.service.lectura;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import com.ferreteria.catalogo.model.Celda;
import com.ferreteria.catalogo.model.FilaLeida;
import com.ferreteria.common.exception.BadRequestException;
import java.time.Duration;
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

    // ---- tope de columnas y de celdas (hallazgo 4 de la revision del 01/11) ----

    /** Linea {@code numero} (50 por pagina) con una palabra en cada una de las {@code columnas} indicadas. */
    private static List<PalabraPdf> lineaEnColumnas(int numero, List<Integer> columnas) {
        List<PalabraPdf> palabras = new ArrayList<>();
        for (int columna : columnas) {
            double x = 10 + columna * 40;
            String texto = "c" + columna;
            palabras.add(new PalabraPdf(1 + numero / 50, x, x + texto.length() * ANCHO_LETRA,
                10 + (numero % 50) * 15, TAMANO, texto));
        }
        return palabras;
    }

    private static List<Integer> primeras(int cantidad) {
        return java.util.stream.IntStream.range(0, cantidad).boxed().toList();
    }

    @Test
    void doscientasColumnasSeArman() {
        List<PalabraPdf> palabras = new ArrayList<>();
        palabras.addAll(lineaEnColumnas(0, primeras(200)));
        palabras.addAll(lineaEnColumnas(1, primeras(200)));

        List<FilaLeida> filas = TablaDePdf.armar(palabras);

        assertThat(filas.get(1).celdas()).hasSize(200);
        assertThat(filas.get(1).celda(199).texto()).isEqualTo("c199");
    }

    @Test
    void masDeDoscientasColumnasRechazanLaListaAntesDeArmarla() {
        List<PalabraPdf> palabras = new ArrayList<>();
        palabras.addAll(lineaEnColumnas(0, primeras(201)));
        palabras.addAll(lineaEnColumnas(1, primeras(201)));

        assertThatThrownBy(() -> TablaDePdf.armar(palabras))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("La lista tiene datos en más de 200 columnas. Dejá solo las columnas que usás.");
    }

    @Test
    void masDeUnMillonDeCeldasRechazanLaListaAntesDeArmarla() {
        // 5.001 lineas x 200 columnas, con 12 palabras por linea que van rotando: cada columna tiene texto en el
        // 6 % de las lineas (no es debil) y el PDF tiene 60.000 palabras, muy por debajo del tope de 500.000.
        List<PalabraPdf> palabras = new ArrayList<>();
        for (int i = 0; i < 5_001; i++) {
            int desde = (i * 12) % 200;
            palabras.addAll(lineaEnColumnas(i, java.util.stream.IntStream.range(0, 12)
                .map(j -> (desde + j) % 200).sorted().boxed().toList()));
        }

        assertThatThrownBy(() -> TablaDePdf.armar(palabras))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("La lista es demasiado grande para leerla. Dividila en archivos más chicos.");
    }

    /** 40 lineas con «aa» en x=10 y «cc» en x=100, y un texto extra en las lineas indicadas (una columna debil). */
    private static List<PalabraPdf> dosColumnasFuertesCon(Object... lineaXYTexto) {
        List<PalabraPdf> palabras = new ArrayList<>();
        for (int l = 0; l < 40; l++) {
            List<Object> partes = new ArrayList<>(List.of(10, "aa", 100, "cc"));
            for (int i = 0; i < lineaXYTexto.length; i += 3) {
                if ((int) lineaXYTexto[i] == l) {
                    partes.addAll(List.of(lineaXYTexto[i + 1], lineaXYTexto[i + 2]));
                }
            }
            List<PalabraPdf> deLaLinea = linea(1, 100 + l * 15, partes.toArray());
            palabras.addAll(deLaLinea);
        }
        return palabras;
    }

    @Test
    void unaColumnaDebilAIgualDistanciaDeSusVecinasSeUneConLaDeLaIzquierda() {
        // «bb» en 55..65: 35 hasta «aa» (termina en 20) y 35 hasta «cc» (empieza en 100); esta en 1 de 40 lineas.
        List<FilaLeida> filas = TablaDePdf.armar(dosColumnasFuertesCon(5, 55, "bb"));

        assertThat(filas).allSatisfy(fila -> assertThat(fila.celdas()).hasSize(2));
        assertThat(textos(filas.get(5))).containsExactly("aa bb", "cc");
        assertThat(textos(filas.get(0))).containsExactly("aa", "cc");
    }

    @Test
    void unaDebilQueAbsorbeAOtraSigueSiendoDebilYSeVuelveAUnir() {
        // «w1» (40..50) esta a 7 de «w2» (57..67) y a 20 de «aa»: se une con «w2», que queda en 40..67, sigue debil y
        // ahora esta a 20 de «aa» y a 33 de «cc», asi que se une con «aa». El hueco de 7 es mas ancho que el angosto (5).
        List<FilaLeida> filas = TablaDePdf.armar(dosColumnasFuertesCon(3, 40, "w1", 7, 57, "w2"));

        assertThat(filas).allSatisfy(fila -> assertThat(fila.celdas()).hasSize(2));
        assertThat(textos(filas.get(3))).containsExactly("aa w1", "cc");
        assertThat(textos(filas.get(7))).containsExactly("aa w2", "cc");
    }

    @Test
    void unaDebilEnLaPrimeraYOtraEnLaUltimaColumnaSeUnenConSuUnicaVecina() {
        List<FilaLeida> filas = TablaDePdf.armar(dosColumnasFuertesCon(2, -40, "ff", 9, 150, "zz"));

        assertThat(filas).allSatisfy(fila -> assertThat(fila.celdas()).hasSize(2));
        assertThat(textos(filas.get(2))).containsExactly("ff aa", "cc");
        assertThat(textos(filas.get(9))).containsExactly("aa", "cc zz");
    }

    @Test
    void unHuecoAngostoConPalabrasDistintasQueTerminanJustoEnElBordeSigueSeparandoColumnas() {
        // Como los codigos de M-S: el codigo termina justo en el fin de su columna y la descripcion arranca justo en el
        // inicio de la suya, a un espacio comun. Los codigos son distintos, asi que son dos columnas.
        List<PalabraPdf> palabras = new ArrayList<>();
        palabras.addAll(linea(1, 100, 10, "A1 Tornillo", 200, "100"));
        palabras.addAll(linea(1, 115, 10, "B2 Tuerca", 200, "200"));
        palabras.addAll(linea(1, 130, 10, "C3 Arandela", 200, "300"));

        List<FilaLeida> filas = TablaDePdf.armar(palabras);

        assertThat(textos(filas.get(0))).containsExactly("A1", "Tornillo", "100");
        assertThat(textos(filas.get(1))).containsExactly("B2", "Tuerca", "200");
    }

    /**
     * El peor PDF del tope de palabras para los huecos angostos: 4 lineas con 125.000 palabras cada una, a la misma x
     * en todas, separadas por huecos mas anchos que el de tramos juntos y mas angostos que el de bloques, y un hueco
     * ancho en el medio. Cada hueco angosto es una columna candidata (unas 125.000) que hay que mirar.
     */
    private static List<PalabraPdf> huecosAngostosEnCuatroLineas(boolean mismaPalabra) {
        int porLinea = LectorPdf.MAXIMO_PALABRAS / 4;
        List<PalabraPdf> palabras = new ArrayList<>(LectorPdf.MAXIMO_PALABRAS);
        for (int l = 0; l < 4; l++) {
            for (int j = 0; j < porLinea; j++) {
                double x = j * 2.8 + (j >= porLinea / 2 ? 50 : 0);
                palabras.add(new PalabraPdf(0, x, x + 2, l * 12, 2, mismaPalabra ? "a" : "p" + l + "-" + j));
            }
        }
        return palabras;
    }

    @Test
    void huecosAngostosConLaMismaPalabraEnElTopeDePalabrasSeArmanEnPocoTiempo() {
        List<PalabraPdf> palabras = huecosAngostosEnCuatroLineas(true);

        List<FilaLeida> filas = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> TablaDePdf.armar(palabras));

        int mitad = LectorPdf.MAXIMO_PALABRAS / 8;
        String unaMitad = String.join(" ", Collections.nCopies(mitad, "a"));
        assertThat(filas).hasSize(4);
        assertThat(filas).allSatisfy(fila -> assertThat(textos(fila)).containsExactly(unaMitad, unaMitad));
    }

    @Test
    void muchasColumnasDebilesEnElTopeDePalabrasSeUnenEnPocoTiempo() {
        // 100 lineas y 250.000 columnas: cada columna tiene texto en 2 lineas (mas que el 1 % tolerado, menos que el 5 %
        // de una columna que no es debil), con palabras distintas y huecos anchos. Todas son debiles y terminan en una.
        int columnas = LectorPdf.MAXIMO_PALABRAS / 2;
        List<PalabraPdf> palabras = new ArrayList<>(LectorPdf.MAXIMO_PALABRAS);
        for (int c = 0; c < columnas; c++) {
            double x = c * 3.0;
            for (int l : new int[] {(2 * c) % 100, (2 * c + 1) % 100}) {
                palabras.add(new PalabraPdf(0, x, x + 2, l * 12, 10, "c" + c));
            }
        }

        List<FilaLeida> filas = assertTimeoutPreemptively(Duration.ofSeconds(2), () -> TablaDePdf.armar(palabras));

        assertThat(filas).hasSize(100);
        assertThat(filas).allSatisfy(fila -> assertThat(fila.celdas()).hasSize(1));
        assertThat(textos(filas.get(0)).get(0)).startsWith("c0 c50 c100 ");
    }

    @Test
    void huecosAngostosConPalabrasDistintasEnElTopeDePalabrasSeRechazanEnPocoTiempo() {
        List<PalabraPdf> palabras = huecosAngostosEnCuatroLineas(false);

        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThatThrownBy(() -> TablaDePdf.armar(palabras))
            .isInstanceOf(BadRequestException.class)
            .hasMessage("La lista tiene datos en más de 200 columnas. Dejá solo las columnas que usás."));
    }
}
