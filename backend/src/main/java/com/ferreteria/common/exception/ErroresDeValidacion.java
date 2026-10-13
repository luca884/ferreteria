package com.ferreteria.common.exception;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Junta los mensajes de validacion por campo. Un campo con varias violaciones queda con UN solo mensaje,
 * elegido con una prioridad explicita (no por orden alfabetico, que cambiaria si cambia un texto): si
 * alguna es de rango ({@code DecimalMin} o {@code DecimalMax}) gana la primera de rango, porque dice
 * lo mas util ("debe estar entre 0 y 100") frente a "puede tener como maximo 2 decimales" ({@code Digits});
 * si no, gana la primera que llego. Hibernate Validator no fija el orden de las violaciones, por eso la
 * regla no puede depender de el salvo cuando ninguna es de rango.
 */
public final class ErroresDeValidacion {

    private static final Set<String> CODIGOS_DE_RANGO = Set.of("DecimalMin", "DecimalMax");

    private final Map<String, String> mensajes = new LinkedHashMap<>();
    private final Map<String, String> codigos = new LinkedHashMap<>();

    /**
     * @param campo   ruta del campo
     * @param codigo  nombre de la anotacion que fallo (por ejemplo {@code DecimalMax}); puede ser null
     * @param mensaje texto para el usuario; null cuenta como vacio
     */
    public void agregar(String campo, String codigo, String mensaje) {
        boolean yaTieneMensaje = mensajes.containsKey(campo);
        if (!yaTieneMensaje || (esDeRango(codigo) && !esDeRango(codigos.get(campo)))) {
            mensajes.put(campo, mensaje == null ? "" : mensaje);
            codigos.put(campo, codigo);
        }
    }

    /** Campo a mensaje, en el orden en que se vio cada campo por primera vez. */
    public Map<String, String> mensajes() {
        return mensajes;
    }

    private static boolean esDeRango(String codigo) {
        return codigo != null && CODIGOS_DE_RANGO.contains(codigo);
    }
}
