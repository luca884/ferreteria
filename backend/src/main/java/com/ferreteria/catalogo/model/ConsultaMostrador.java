package com.ferreteria.catalogo.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Lo que se escribio (o escaneo) en el buscador del mostrador, listo para consultar: el codigo exacto
 * y un patron LIKE por palabra. Las palabras se piden todas (AND) y en cualquier orden.
 *
 * @param codigo    q sin espacios ni saltos de linea al borde, para la coincidencia exacta; null si q estaba vacio o en blanco
 * @param patrones  siempre {@link #MAXIMO_PALABRAS} patrones LIKE (%palabra%, con \, % y _ escapados); los sin usar son null
 */
public record ConsultaMostrador(String codigo, List<String> patrones) {

    /** Cantidad de palabras que se buscan por separado. */
    public static final int MAXIMO_PALABRAS = 5;

    public static ConsultaMostrador de(String q) {
        List<String> patrones = new ArrayList<>(Collections.nCopies(MAXIMO_PALABRAS, null));
        if (q == null || q.isBlank()) {
            return new ConsultaMostrador(null, Collections.unmodifiableList(patrones));
        }
        String codigo = q.trim();
        String[] palabras = codigo.split("\\s+");
        for (int i = 0; i < palabras.length && i < MAXIMO_PALABRAS; i++) {
            boolean ultimoSlot = i == MAXIMO_PALABRAS - 1;
            // Pasadas las 5 palabras, el resto va junto en el ultimo slot: se busca como una subcadena
            // contigua ("w5 w6 w7"). Es mas estricto que pedir cada palabra, pero no descarta ninguna.
            String palabra = ultimoSlot ? String.join(" ", Arrays.asList(palabras).subList(i, palabras.length)) : palabras[i];
            patrones.set(i, FiltroArticulos.patronDeBusqueda(palabra));
        }
        return new ConsultaMostrador(codigo, Collections.unmodifiableList(patrones));
    }

    /** True si no hay nada que buscar. */
    public boolean vacia() {
        return codigo == null;
    }

    /** El patron de la palabra i (0 a 4); null si esa palabra no se uso. */
    public String patron(int i) {
        return patrones.get(i);
    }
}
