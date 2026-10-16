package com.ferreteria.catalogo.model;

import java.util.ArrayList;
import java.util.List;

/**
 * La zona de datos de la lista: de las filas leidas de las hojas elegidas, cuales son de datos, cuales se
 * saltean (titulos y encabezados repetidos) y cuales se ignoran (R-102, R-106). Puro: no mira la base ni
 * el archivo, solo las filas ya leidas y el mapeo.
 *
 * <p>Dentro de la zona, cada fila es una de tres cosas:
 * <ul>
 *   <li><b>ignorada</b>: todas las columnas mapeadas estan vacias. No suma a nada;</li>
 *   <li><b>salteada</b>: un titulo, como los "Familia: NNN - Nombre" de M-S (texto en el codigo, sin descripcion y con
 *       la celda del costo vacia) o los de LISTA 336 (texto en la moneda, sin codigo ni costo), o un encabezado repetido. No es un
 *       error ni un articulo: solo se cuenta;</li>
 *   <li><b>de datos</b>: cualquier otra. Una fila con codigo y descripcion pero sin costo sigue siendo de datos: el
 *       planificador la marca como error, como en CU-04.</li>
 * </ul>
 * Sin columna de descripcion no se puede decir que un codigo suelto sea un titulo, asi que esa fila queda de datos.
 */
public final class ZonaDeDatos {

    private ZonaDeDatos() {
    }

    /**
     * @param filas       las filas de datos, por hoja (en el orden del mapeo) y por numero de fila
     * @param salteadas   cuantas filas de titulo o de encabezado repetido se saltearon
     * @param ignoradas   cuantas filas sin nada en las columnas mapeadas
     * @param advertencias avisos generales de la lectura (por ejemplo, una hoja sin encabezado)
     */
    public record Resultado(List<FilaLeida> filas, int salteadas, int ignoradas, List<AvisoDeFila> advertencias) {

        public Resultado {
            filas = List.copyOf(filas);
            advertencias = List.copyOf(advertencias);
        }
    }

    private enum Tipo { DATOS, SALTEADA, IGNORADA }

    public static Resultado clasificar(LecturaLista lectura, MapeoLista mapeo) {
        List<FilaLeida> datos = new ArrayList<>();
        List<AvisoDeFila> advertencias = new ArrayList<>();
        int salteadas = 0;
        int ignoradas = 0;
        for (int hoja : mapeo.hojasElegidas()) {
            List<FilaLeida> filasDeLaHoja = lectura.filasDeHoja(hoja);
            int desde = primeraFilaDeDatos(lectura, hoja, filasDeLaHoja, mapeo, advertencias);
            for (FilaLeida fila : filasDeLaHoja) {
                if (fila.numero() < desde) {
                    continue;
                }
                switch (tipo(fila, mapeo)) {
                    case DATOS -> datos.add(fila);
                    case SALTEADA -> salteadas++;
                    case IGNORADA -> ignoradas++;
                }
            }
        }
        return new Resultado(datos, salteadas, ignoradas, advertencias);
    }

    /**
     * Donde arrancan los datos de una hoja: despues de la primera fila cuyo codigo y costo coinciden con los textos
     * de encabezado guardados en el mapeo (decision 34: un solo mapeo para todas las hojas, y cada hoja puede
     * empezar en otra fila). Si ninguna coincide, o el mapeo no guarda textos, se usa {@code filaDesde}; en el
     * primer caso se suma una advertencia con el nombre de la hoja.
     */
    private static int primeraFilaDeDatos(LecturaLista lectura, int hoja, List<FilaLeida> filas, MapeoLista mapeo,
                                          List<AvisoDeFila> advertencias) {
        if (!tieneTextosDeEncabezado(mapeo)) {
            return mapeo.filaDesde();
        }
        for (FilaLeida fila : filas) {
            if (esEncabezado(fila, mapeo)) {
                return fila.numero() + 1;
            }
        }
        String nombre = hoja < lectura.hojas().size() ? lectura.hojas().get(hoja) : String.valueOf(hoja + 1);
        advertencias.add(new AvisoDeFila(hoja, null, null, "En la hoja «" + nombre.strip()
            + "» no se encontró el encabezado de la lista: se leen los datos desde la fila " + mapeo.filaDesde() + "."));
        return mapeo.filaDesde();
    }

    private static boolean tieneTextosDeEncabezado(MapeoLista mapeo) {
        MapeoLista.Encabezados textos = mapeo.encabezados();
        return textos != null && (!SinonimosDeColumnas.normalizar(textos.codigo()).isEmpty()
            || !SinonimosDeColumnas.normalizar(textos.costo()).isEmpty());
    }

    private static Tipo tipo(FilaLeida fila, MapeoLista mapeo) {
        if (esEncabezado(fila, mapeo)) {
            return Tipo.SALTEADA;
        }
        if (columnasMapeadas(mapeo).stream().allMatch(columna -> fila.celda(columna).vacia())) {
            return Tipo.IGNORADA;
        }
        return esTitulo(fila, mapeo) ? Tipo.SALTEADA : Tipo.DATOS;
    }

    /** Sin costo numerico ni descripcion, y texto en el codigo (M-S) o en la moneda de una fila sin codigo (LISTA 336). */
    private static boolean esTitulo(FilaLeida fila, MapeoLista mapeo) {
        // Un texto en el costo, aunque no sea un numero, es una fila con un costo mal cargado: no es un titulo.
        boolean sinCosto = fila.celda(mapeo.columnaCosto()).vacia();
        boolean descripcionMapeada = mapeo.columnaDescripcion() != null;
        boolean sinDescripcion = !descripcionMapeada || fila.celda(mapeo.columnaDescripcion()).vacia();
        if (!sinCosto || !sinDescripcion) {
            return false;
        }
        boolean textoEnCodigo = !fila.celda(mapeo.columnaCodigo()).vacia();
        boolean textoEnMoneda = mapeo.columnaMoneda() != null && !fila.celda(mapeo.columnaMoneda()).vacia();
        return (descripcionMapeada && textoEnCodigo) || (!textoEnCodigo && textoEnMoneda);
    }

    /**
     * La fila tiene, en las columnas del codigo y del costo, los textos de encabezado guardados (sin distinguir
     * mayusculas, tildes ni puntuacion). Se exigen los dos que el mapeo guarda; si falta uno, alcanza con el otro.
     */
    private static boolean esEncabezado(FilaLeida fila, MapeoLista mapeo) {
        if (!tieneTextosDeEncabezado(mapeo)) {
            return false;
        }
        MapeoLista.Encabezados textos = mapeo.encabezados();
        return coincide(textos.codigo(), fila.celda(mapeo.columnaCodigo()))
            && coincide(textos.costo(), fila.celda(mapeo.columnaCosto()));
    }

    private static boolean coincide(String guardado, Celda celda) {
        String esperado = SinonimosDeColumnas.normalizar(guardado);
        return esperado.isEmpty() || esperado.equals(SinonimosDeColumnas.normalizar(celda.texto()));
    }

    /** Todas las columnas del mapeo, las del corte 2 que vengan incluidas. */
    private static List<Integer> columnasMapeadas(MapeoLista mapeo) {
        List<Integer> columnas = new ArrayList<>();
        columnas.add(mapeo.columnaCodigo());
        columnas.add(mapeo.columnaCosto());
        for (Integer opcional : new Integer[] {mapeo.columnaDescripcion(), mapeo.columnaMoneda(), mapeo.columnaSinIva(),
            mapeo.columnaConIva(), mapeo.columnaIva(), mapeo.columnaAjuste1(), mapeo.columnaAjuste2(),
            mapeo.columnaAjuste3(), mapeo.columnaCodigoBarras(), mapeo.columnaUnidad(), mapeo.columnaRubro()}) {
            if (opcional != null) {
                columnas.add(opcional);
            }
        }
        return columnas;
    }
}
