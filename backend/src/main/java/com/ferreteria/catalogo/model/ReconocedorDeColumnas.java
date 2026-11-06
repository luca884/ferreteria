package com.ferreteria.catalogo.model;

import com.ferreteria.catalogo.model.SinonimosDeColumnas.Campo;
import com.ferreteria.catalogo.model.SinonimosDeColumnas.Coincidencia;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Reconoce el encabezado y las columnas de una lista (R-107). Es una SUGERENCIA: nunca una decision, y solo
 * depende del contenido del archivo. Puro y determinista.
 *
 * <p>En cada hoja revisa las primeras 30 filas, cada una sola o junto con la siguiente (un encabezado de dos
 * filas, como el de M-S: "Precio X" y debajo "Unidad."). Una columna reconoce un campo si el texto de una de las
 * dos filas, o el de las dos juntas, es un sinonimo ({@link SinonimosDeColumnas}); el texto de las dos juntas
 * manda sobre los sueltos. Entre varias candidatas gana la que reconoce mas campos y, a igual cantidad, la de
 * sinonimos mas prioritarios; si empatan, la de una sola fila y la mas arriba. La primera fila de datos es la
 * ultima del encabezado mas uno.
 */
public final class ReconocedorDeColumnas {

    /** Cuantas filas de cada hoja se revisan. */
    static final int FILAS_REVISADAS = 30;

    private static final int PESO_POR_CAMPO = 1_000;

    private ReconocedorDeColumnas() {
    }

    /**
     * Una hoja del archivo. {@code filaEncabezado} es la ULTIMA fila del encabezado (1-based), o null si no se
     * reconoce ninguna columna en la hoja.
     */
    public record HojaReconocida(int indice, String nombre, Integer filaEncabezado) {

        /** La fila siguiente al encabezado, o null si no hay encabezado. */
        public Integer primeraFilaDeDatos() {
            return filaEncabezado == null ? null : filaEncabezado + 1;
        }
    }

    /**
     * Lo que se propone. Todo es nullable. {@code hoja} es la hoja de la que salen las columnas y {@code hojas}
     * las que tienen el encabezado reconocido (codigo y costo).
     */
    public record Propuesta(
            Integer hoja,
            Integer filaDesde,
            Integer columnaCodigo,
            Integer columnaDescripcion,
            Integer columnaCosto,
            Integer columnaMoneda,
            Integer columnaSinIva,
            Integer columnaConIva,
            Integer columnaIva,
            Integer columnaAjuste1,
            Integer columnaAjuste2,
            Integer columnaAjuste3,
            Integer columnaCodigoBarras,
            Integer columnaUnidad,
            Integer columnaRubro,
            List<Integer> hojas) {

        static Propuesta vacia() {
            return new Propuesta(null, null, null, null, null, null, null, null, null, null, null, null, null, null,
                null, List.of());
        }
    }

    public record Reconocimiento(List<HojaReconocida> hojas, Propuesta propuesta) {}

    /** La mejor lectura del encabezado de una hoja. */
    private record Candidato(int ultimaFilaDelEncabezado, Map<Campo, Integer> columnas, long puntaje) {

        boolean tieneCodigoYCosto() {
            return columnas.containsKey(Campo.CODIGO) && columnas.containsKey(Campo.COSTO);
        }
    }

    public static Reconocimiento reconocer(LecturaLista lectura) {
        List<HojaReconocida> hojas = new ArrayList<>();
        List<Candidato> mejores = new ArrayList<>();
        for (int indice = 0; indice < lectura.hojas().size(); indice++) {
            Optional<Candidato> mejor = mejorCandidato(lectura.filasDeHoja(indice));
            mejores.add(mejor.orElse(null));
            hojas.add(new HojaReconocida(indice, lectura.hojas().get(indice),
                mejor.map(Candidato::ultimaFilaDelEncabezado).orElse(null)));
        }
        return new Reconocimiento(hojas, proponer(mejores));
    }

    private static Propuesta proponer(List<Candidato> mejores) {
        List<Integer> completas = new ArrayList<>();
        for (int i = 0; i < mejores.size(); i++) {
            if (mejores.get(i) != null && mejores.get(i).tieneCodigoYCosto()) {
                completas.add(i);
            }
        }
        int elegida = -1;
        if (!completas.isEmpty()) {
            elegida = completas.get(0);
        } else {
            long mayorPuntaje = 0;
            for (int i = 0; i < mejores.size(); i++) {
                if (mejores.get(i) != null && mejores.get(i).puntaje() > mayorPuntaje) {
                    mayorPuntaje = mejores.get(i).puntaje();
                    elegida = i;
                }
            }
        }
        if (elegida < 0) {
            return Propuesta.vacia();
        }
        Candidato candidato = mejores.get(elegida);
        Map<Campo, Integer> c = candidato.columnas();
        return new Propuesta(elegida, candidato.ultimaFilaDelEncabezado() + 1, c.get(Campo.CODIGO),
            c.get(Campo.DESCRIPCION), c.get(Campo.COSTO), c.get(Campo.MONEDA), c.get(Campo.SIN_IVA),
            c.get(Campo.CON_IVA), c.get(Campo.IVA), c.get(Campo.AJUSTE1), c.get(Campo.AJUSTE2), c.get(Campo.AJUSTE3),
            c.get(Campo.CODIGO_BARRAS), c.get(Campo.UNIDAD), c.get(Campo.RUBRO), List.copyOf(completas));
    }

    private static Optional<Candidato> mejorCandidato(List<FilaLeida> filasDeLaHoja) {
        List<FilaLeida> revisadas = filasDeLaHoja.stream()
            .filter(fila -> fila.numero() <= FILAS_REVISADAS)
            .sorted(Comparator.comparingInt(FilaLeida::numero))
            .toList();
        Candidato mejor = null;
        for (int i = 0; i < revisadas.size(); i++) {
            FilaLeida fila = revisadas.get(i);
            mejor = elegir(mejor, candidato(fila, null));
            FilaLeida siguiente = i + 1 < revisadas.size() ? revisadas.get(i + 1) : null;
            if (siguiente != null && siguiente.numero() == fila.numero() + 1) {
                mejor = elegir(mejor, candidato(fila, siguiente));
            }
        }
        return Optional.ofNullable(mejor);
    }

    /** La candidata con mas puntaje; a igual puntaje se queda con la que ya estaba (la de una fila y mas arriba). */
    private static Candidato elegir(Candidato actual, Candidato nueva) {
        if (nueva == null) {
            return actual;
        }
        return actual == null || nueva.puntaje() > actual.puntaje() ? nueva : actual;
    }

    /** El encabezado de {@code primera} sola, o de {@code primera} y {@code segunda}; null si no reconoce nada. */
    private static Candidato candidato(FilaLeida primera, FilaLeida segunda) {
        int ancho = Math.max(primera.celdas().size(), segunda == null ? 0 : segunda.celdas().size());
        Map<Campo, Integer> columnas = new EnumMap<>(Campo.class);
        Map<Campo, Integer> prioridades = new EnumMap<>(Campo.class);
        for (int columna = 0; columna < ancho; columna++) {
            Coincidencia coincidencia = coincidenciaDeLaColumna(primera, segunda, columna);
            if (coincidencia == null) {
                continue;
            }
            Campo campo = coincidencia.campo();
            Integer anterior = prioridades.get(campo);
            // A igual prioridad se queda la columna de mas a la izquierda (la que ya estaba).
            if (anterior == null || coincidencia.prioridad() < anterior) {
                prioridades.put(campo, coincidencia.prioridad());
                columnas.put(campo, columna);
            }
        }
        // Una columna sin IVA sola, sin otra de costo, ES el costo: el costo del articulo va sin IVA (QA 05/11, «Precio
        // s/IVA»). Entra con la prioridad mas baja de COSTO: cualquier sinonimo de costo le gana.
        if (columnas.containsKey(Campo.SIN_IVA) && !columnas.containsKey(Campo.CON_IVA)
                && !columnas.containsKey(Campo.COSTO)) {
            columnas.put(Campo.COSTO, columnas.get(Campo.SIN_IVA));
            prioridades.put(Campo.COSTO, SinonimosDeColumnas.cantidad(Campo.COSTO) - 1);
        }
        // El par de IVA vale solo si se reconocen las dos columnas.
        if (!columnas.containsKey(Campo.SIN_IVA) || !columnas.containsKey(Campo.CON_IVA)) {
            columnas.remove(Campo.SIN_IVA);
            columnas.remove(Campo.CON_IVA);
            prioridades.remove(Campo.SIN_IVA);
            prioridades.remove(Campo.CON_IVA);
        }
        if (columnas.isEmpty()) {
            return null;
        }
        long puntaje = 0;
        for (Map.Entry<Campo, Integer> entrada : prioridades.entrySet()) {
            puntaje += PESO_POR_CAMPO + (SinonimosDeColumnas.cantidad(entrada.getKey()) - entrada.getValue());
        }
        int ultima = segunda == null ? primera.numero() : segunda.numero();
        return new Candidato(ultima, columnas, puntaje);
    }

    private static Coincidencia coincidenciaDeLaColumna(FilaLeida primera, FilaLeida segunda, int columna) {
        String arriba = SinonimosDeColumnas.normalizar(primera.celda(columna).texto());
        String abajo = segunda == null ? "" : SinonimosDeColumnas.normalizar(segunda.celda(columna).texto());
        if (!arriba.isEmpty() && !abajo.isEmpty()) {
            Optional<Coincidencia> juntas = SinonimosDeColumnas.buscar(arriba + " " + abajo);
            if (juntas.isPresent()) {
                return juntas.get();
            }
        }
        Optional<Coincidencia> deArriba = arriba.isEmpty() ? Optional.empty() : SinonimosDeColumnas.buscar(arriba);
        if (deArriba.isPresent()) {
            return deArriba.get();
        }
        return abajo.isEmpty() ? null : SinonimosDeColumnas.buscar(abajo).orElse(null);
    }
}
