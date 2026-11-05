package com.ferreteria.catalogo.service.lectura;

import com.ferreteria.catalogo.model.Celda;
import com.ferreteria.catalogo.model.FilaLeida;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Arma la tabla de un PDF con texto: de las palabras con su posicion salen las filas (una por linea, con todas las
 * paginas seguidas en la hoja 0) y las columnas. Puro: no abre el PDF.
 *
 * <p>Las columnas salen de los huecos verticales que dejan las lineas de la tabla: las que tienen dos o mas bloques de
 * texto, separados por mas de media letra. Un hueco aguanta que lo tapen unas pocas lineas (el 1 %, como las notas de
 * condiciones de M-S). Despues:
 * <ul>
 *   <li>un hueco angosto como un espacio separa columnas solo si a su izquierda hay textos distintos (los codigos de
 *       M-S, pegados a la descripcion); si todas las lineas repiten ahi la misma palabra, es el espacio entre dos
 *       palabras de la misma celda;</li>
 *   <li>una columna con texto en menos del 5 % de las lineas (el encabezado de cada pagina, mas ancho que sus valores)
 *       se une con la vecina mas cercana;</li>
 *   <li>una palabra que arranca en un hueco sigue en la columna de la anterior si va pegada a ella (la cola de una
 *       descripcion larga) y, si no, abre la columna siguiente (un importe largo alineado a la derecha); tambien sigue
 *       si la anterior ya pisaba la columna siguiente (un titulo que atraviesa columnas);</li>
 *   <li>una columna que solo trae el signo de moneda se une con el importe de su derecha.</li>
 * </ul>
 * Una linea de un solo bloque (titulo, rubro, encabezado o pie de pagina) va entera a la primera columna, asi la zona
 * de datos la saltea como titulo.
 */
final class TablaDePdf {

    /** Lineas de la tabla que pueden tapar un hueco sin borrarlo. */
    private static final double FRACCION_TOLERADA = 0.01;
    /** Una columna con texto en menos lineas que esta fraccion se une con su vecina. */
    private static final double FRACCION_COLUMNA_DEBIL = 0.05;
    /** Mas que esto entre dos palabras (en tamanos de letra) separa dos bloques de una linea. */
    private static final double SEPARACION_DE_BLOQUES = 0.5;
    /** Dos palabras cuyas bases difieren en hasta esto (en tamanos de letra) son de la misma linea. */
    private static final double MISMA_LINEA = 0.3;
    /** Dos tramos con texto separados por hasta esto (en puntos) son de la misma columna. */
    private static final double TRAMOS_JUNTOS = 0.5;
    private static final Pattern SIGNO_DE_MONEDA = Pattern.compile("US\\$|U\\$S|\\$");

    private TablaDePdf() {
    }

    private static final class Columna {
        private double inicio;
        private double fin;
        private int lineas;

        private Columna(double inicio, double fin, int lineas) {
            this.inicio = inicio;
            this.fin = fin;
            this.lineas = lineas;
        }
    }

    static List<FilaLeida> armar(List<PalabraPdf> palabras) {
        List<List<PalabraPdf>> lineas = lineas(palabras);
        if (lineas.size() > LimitesDeLista.MAXIMO_FILAS_LEIDAS) {
            throw LimitesDeLista.demasiadasFilas();
        }
        List<List<PalabraPdf>> deTabla = lineas.stream().filter(TablaDePdf::esDeTabla).toList();
        List<Columna> columnas = unirDebiles(unirHuecosSinVariedad(columnas(deTabla), deTabla), deTabla.size());
        controlarTamano(lineas.size(), deTabla.size(), columnas.size());

        List<List<String>> celdas = new ArrayList<>(lineas.size());
        List<List<String>> celdasDeTabla = new ArrayList<>();
        for (List<PalabraPdf> linea : lineas) {
            if (columnas.isEmpty() || !esDeTabla(linea)) {
                celdas.add(List.of(texto(linea)));
            } else {
                List<String> deLaLinea = repartir(linea, columnas);
                celdas.add(deLaLinea);
                celdasDeTabla.add(deLaLinea);
            }
        }
        unirSignosDeMoneda(celdasDeTabla);

        List<FilaLeida> filas = new ArrayList<>(celdas.size());
        for (int i = 0; i < celdas.size(); i++) {
            filas.add(new FilaLeida(0, i + 1, celdas.get(i).stream().map(texto -> new Celda(texto, null)).toList()));
        }
        return filas;
    }

    /**
     * Antes de repartir: cada columna sale de un texto, asi que mas de 200 son datos mas alla de la columna 200; y
     * cada linea de la tabla arma una celda por columna (las demas, una sola).
     */
    private static void controlarTamano(int lineas, int lineasDeTabla, int columnas) {
        if (columnas > LimitesDeLista.MAXIMO_COLUMNAS) {
            throw LimitesDeLista.demasiadasColumnas();
        }
        long celdas = (long) lineasDeTabla * Math.max(columnas, 1) + (lineas - lineasDeTabla);
        if (celdas > LimitesDeLista.MAXIMO_CELDAS) {
            throw LimitesDeLista.demasiadoGrande();
        }
    }

    /**
     * Las lineas en orden de lectura: por pagina, de arriba abajo, y cada una de izquierda a derecha. Tambien las usa
     * {@link FacturaDePdf}, con la misma tolerancia de linea base.
     */
    static List<List<PalabraPdf>> lineas(List<PalabraPdf> palabras) {
        List<PalabraPdf> ordenadas = new ArrayList<>(palabras);
        ordenadas.sort(Comparator.comparingInt(PalabraPdf::pagina)
            .thenComparingDouble(PalabraPdf::base)
            .thenComparingDouble(PalabraPdf::izquierda));
        List<List<PalabraPdf>> lineas = new ArrayList<>();
        List<PalabraPdf> actual = null;
        for (PalabraPdf palabra : ordenadas) {
            if (actual == null || !mismaLinea(actual.get(0), palabra)) {
                actual = new ArrayList<>();
                lineas.add(actual);
            }
            actual.add(palabra);
        }
        lineas.forEach(linea -> linea.sort(Comparator.comparingDouble(PalabraPdf::izquierda)));
        return lineas;
    }

    private static boolean mismaLinea(PalabraPdf primera, PalabraPdf palabra) {
        return primera.pagina() == palabra.pagina()
            && Math.abs(palabra.base() - primera.base()) <= MISMA_LINEA * tamano(primera, palabra);
    }

    private static boolean esDeTabla(List<PalabraPdf> linea) {
        for (int i = 1; i < linea.size(); i++) {
            if (!pegadas(linea.get(i - 1), linea.get(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean pegadas(PalabraPdf anterior, PalabraPdf palabra) {
        return palabra.izquierda() - anterior.derecha() <= SEPARACION_DE_BLOQUES * tamano(anterior, palabra);
    }

    private static double tamano(PalabraPdf una, PalabraPdf otra) {
        return Math.max(1, Math.max(una.tamano(), otra.tamano()));
    }

    /**
     * Los tramos de x con texto en mas lineas de las toleradas. Recorre los bordes de todas las palabras de la tabla
     * contando cuantas lineas tapan cada tramo.
     */
    private static List<Columna> columnas(List<List<PalabraPdf>> deTabla) {
        int toleradas = (int) (FRACCION_TOLERADA * deTabla.size());
        List<double[]> bordes = new ArrayList<>();
        for (List<PalabraPdf> linea : deTabla) {
            for (PalabraPdf palabra : linea) {
                bordes.add(new double[] {palabra.izquierda(), 1});
                bordes.add(new double[] {palabra.derecha(), -1});
            }
        }
        // A igual x, la palabra que empieza va antes que la que termina: dos palabras que se tocan no dejan hueco.
        bordes.sort(Comparator.<double[]>comparingDouble(borde -> borde[0]).thenComparingDouble(borde -> -borde[1]));
        List<Columna> columnas = new ArrayList<>();
        int tapadas = 0;
        double anterior = Double.NaN;
        for (double[] borde : bordes) {
            if (borde[0] > anterior && tapadas > toleradas) {
                Columna ultima = columnas.isEmpty() ? null : columnas.get(columnas.size() - 1);
                if (ultima != null && anterior - ultima.fin <= TRAMOS_JUNTOS) {
                    ultima.fin = borde[0];
                    ultima.lineas = Math.max(ultima.lineas, tapadas);
                } else {
                    columnas.add(new Columna(anterior, borde[0], tapadas));
                }
            }
            tapadas += (int) borde[1];
            anterior = borde[0];
        }
        return columnas;
    }

    /**
     * Une las columnas separadas por un hueco angosto (no mas ancho que la separacion de bloques) cuando a su izquierda
     * todas las lineas tienen la misma palabra. Solo cuentan las lineas con una palabra a cada lado del hueco.
     *
     * <p>Una sola pasada: la columna de la derecha de cada hueco es siempre una de las originales, asi que las palabras
     * que la preceden se juntan una vez al principio ({@link #antesDeCadaColumna}). Mirar todas las palabras por cada
     * hueco tardaba casi dos minutos con un PDF armado a proposito.
     */
    private static List<Columna> unirHuecosSinVariedad(List<Columna> columnas, List<List<PalabraPdf>> deTabla) {
        if (columnas.size() < 2) {
            return columnas;
        }
        double angosto = SEPARACION_DE_BLOQUES * tamanoTipico(deTabla);
        List<List<PalabraPdf>> antesDe = antesDeCadaColumna(columnas, deTabla);
        List<Columna> unidas = new ArrayList<>();
        Columna izquierda = columnas.get(0);
        for (int k = 1; k < columnas.size(); k++) {
            Columna derecha = columnas.get(k);
            if (derecha.inicio - izquierda.fin <= angosto && !variasPalabrasAntesDe(izquierda, antesDe.get(k))) {
                izquierda.fin = derecha.fin;
                izquierda.lineas = Math.max(izquierda.lineas, derecha.lineas);
            } else {
                unidas.add(izquierda);
                izquierda = derecha;
            }
        }
        unidas.add(izquierda);
        return unidas;
    }

    /**
     * Por cada columna, las palabras que van justo antes, en la misma linea, de una que arranca en ella. Las columnas
     * no se tocan entre si y van en orden, asi que la de cada palabra sale por busqueda binaria.
     */
    private static List<List<PalabraPdf>> antesDeCadaColumna(List<Columna> columnas, List<List<PalabraPdf>> deTabla) {
        List<List<PalabraPdf>> antesDe = new ArrayList<>(columnas.size());
        for (int k = 0; k < columnas.size(); k++) {
            antesDe.add(new ArrayList<>(0));
        }
        for (List<PalabraPdf> linea : deTabla) {
            for (int j = 1; j < linea.size(); j++) {
                int k = indiceQueContiene(columnas, linea.get(j).izquierda());
                if (k >= 0) {
                    antesDe.get(k).add(linea.get(j - 1));
                }
            }
        }
        return antesDe;
    }

    /** El indice de la columna con {@code inicio <= x <= fin}, o -1 si x cae en un hueco. */
    private static int indiceQueContiene(List<Columna> columnas, double x) {
        int bajo = 0;
        int alto = columnas.size() - 1;
        int ultimaQueEmpiezaAntes = -1;
        while (bajo <= alto) {
            int medio = (bajo + alto) >>> 1;
            if (columnas.get(medio).inicio <= x) {
                ultimaQueEmpiezaAntes = medio;
                bajo = medio + 1;
            } else {
                alto = medio - 1;
            }
        }
        return ultimaQueEmpiezaAntes >= 0 && x <= columnas.get(ultimaQueEmpiezaAntes).fin ? ultimaQueEmpiezaAntes : -1;
    }

    /** Si entre las palabras que terminan en la columna izquierda hay al menos dos distintas. */
    private static boolean variasPalabrasAntesDe(Columna izquierda, List<PalabraPdf> anteriores) {
        String primera = null;
        for (PalabraPdf anterior : anteriores) {
            if (anterior.derecha() >= izquierda.inicio && anterior.derecha() <= izquierda.fin) {
                if (primera == null) {
                    primera = anterior.texto();
                } else if (!primera.equals(anterior.texto())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** La mediana del tamano de letra de las palabras de la tabla. */
    private static double tamanoTipico(List<List<PalabraPdf>> deTabla) {
        double[] tamanos = deTabla.stream().flatMap(List::stream).mapToDouble(PalabraPdf::tamano).sorted().toArray();
        return tamanos.length == 0 ? 1 : Math.max(1, tamanos[tamanos.length / 2]);
    }

    /**
     * Une cada columna debil con la vecina mas cercana (a igual distancia, con la de la izquierda), empezando siempre por
     * la primera debil. Las de antes de esa no son debiles y una union nunca les baja las lineas, asi que despues de
     * unir se sigue desde la de la derecha: una sola pasada, con las vecinas enlazadas, en lugar de volver a empezar y
     * correr la lista en cada union.
     */
    private static List<Columna> unirDebiles(List<Columna> columnas, int lineasDeTabla) {
        double minimo = FRACCION_COLUMNA_DEBIL * lineasDeTabla;
        int total = columnas.size();
        int[] anterior = new int[total];
        int[] siguiente = new int[total];
        boolean[] unida = new boolean[total];
        for (int i = 0; i < total; i++) {
            anterior[i] = i - 1;
            siguiente[i] = i + 1;
        }
        int quedan = total;
        int i = 0;
        while (i < total && quedan > 1) {
            Columna debil = columnas.get(i);
            if (debil.lineas >= minimo) {
                i = siguiente[i];
                continue;
            }
            int izquierda = anterior[i];
            int derecha = siguiente[i];
            double aLaIzquierda = izquierda >= 0 ? debil.inicio - columnas.get(izquierda).fin : Double.MAX_VALUE;
            double aLaDerecha = derecha < total ? columnas.get(derecha).inicio - debil.fin : Double.MAX_VALUE;
            Columna vecina = columnas.get(aLaIzquierda <= aLaDerecha ? izquierda : derecha);
            vecina.inicio = Math.min(vecina.inicio, debil.inicio);
            vecina.fin = Math.max(vecina.fin, debil.fin);
            vecina.lineas = Math.max(vecina.lineas, debil.lineas);
            unida[i] = true;
            quedan--;
            if (izquierda >= 0) {
                siguiente[izquierda] = derecha;
            }
            if (derecha < total) {
                anterior[derecha] = izquierda;
            }
            i = derecha;
        }
        List<Columna> quedaron = new ArrayList<>(quedan);
        for (int k = 0; k < total; k++) {
            if (!unida[k]) {
                quedaron.add(columnas.get(k));
            }
        }
        return quedaron;
    }

    /** Las palabras de una linea de la tabla, cada una en su columna; nunca vuelve a una columna anterior. */
    private static List<String> repartir(List<PalabraPdf> linea, List<Columna> columnas) {
        List<StringBuilder> textos = new ArrayList<>();
        columnas.forEach(columna -> textos.add(new StringBuilder()));
        int actual = 0;
        PalabraPdf anterior = null;
        for (PalabraPdf palabra : linea) {
            int indice = columnaQueContiene(columnas, palabra.izquierda());
            boolean enHueco = indice < 0;
            if (enHueco) {
                indice = -indice - 1;
            }
            // Pegada a la anterior, sigue en su columna si arranca en un hueco (la cola de un texto largo) o si la
            // anterior ya pisaba esta columna (un titulo que atraviesa columnas, como «Familia: 001 - Pasadores»).
            if (anterior != null && pegadas(anterior, palabra)
                && (enHueco || anterior.derecha() > columnas.get(indice).inicio)) {
                indice = actual;
            }
            actual = Math.max(actual, indice);
            StringBuilder texto = textos.get(actual);
            texto.append(texto.isEmpty() ? "" : " ").append(palabra.texto());
            anterior = palabra;
        }
        // Mutable: unirSignosDeMoneda la cambia en el lugar.
        return new ArrayList<>(textos.stream().map(StringBuilder::toString).toList());
    }

    /**
     * El indice de la columna que contiene a x o, si x cae en un hueco, {@code -(siguiente + 1)}, donde la siguiente es
     * la columna de la derecha del hueco (la ultima si x queda despues de todas).
     */
    private static int columnaQueContiene(List<Columna> columnas, double x) {
        for (int i = 0; i < columnas.size(); i++) {
            if (x < columnas.get(i).inicio) {
                return -(i + 1);
            }
            if (x <= columnas.get(i).fin) {
                return i;
            }
        }
        return -columnas.size();
    }

    /**
     * Une la columna k con la k+1 cuando la k trae signos de moneda y nunca un texto propio al lado de un valor de la
     * k+1: en cada linea esta vacia, es solo el signo, o la k+1 esta vacia. Cambia las celdas en el lugar.
     */
    private static void unirSignosDeMoneda(List<List<String>> lineas) {
        if (lineas.isEmpty()) {
            return;
        }
        int k = 0;
        while (k < lineas.get(0).size() - 1) {
            int columna = k;
            boolean traeSignos = lineas.stream().anyMatch(celdas -> esSigno(celdas.get(columna)));
            boolean sinTextoPropio = lineas.stream().allMatch(celdas -> celdas.get(columna).isEmpty()
                || esSigno(celdas.get(columna)) || celdas.get(columna + 1).isEmpty());
            if (traeSignos && sinTextoPropio) {
                for (List<String> celdas : lineas) {
                    celdas.set(columna + 1, (celdas.get(columna) + " " + celdas.get(columna + 1)).strip());
                    celdas.remove(columna);
                }
            } else {
                k++;
            }
        }
    }

    private static boolean esSigno(String texto) {
        return SIGNO_DE_MONEDA.matcher(texto).matches();
    }

    private static String texto(List<PalabraPdf> linea) {
        return String.join(" ", linea.stream().map(PalabraPdf::texto).toList());
    }
}
