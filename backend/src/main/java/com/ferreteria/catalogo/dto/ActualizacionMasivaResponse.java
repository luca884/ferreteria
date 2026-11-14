package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.CambioMasivo;
import com.ferreteria.catalogo.model.ConteosMasivos;

import java.util.List;

/**
 * El resultado de aplicar: los contadores del plan, cuantas entradas de historial se escribieron y el detalle de
 * lo que cambio, que es lo que muestra el pop-up del resultado. {@code cambios} trae como mucho las primeras
 * 1.000 filas y {@code detalleRecortado} avisa si se cortó (los contadores son siempre exactos).
 */
public record ActualizacionMasivaResponse(
        ConteosMasivos conteos,
        int entradasDeHistorial,
        List<CambioMasivo> cambios,
        boolean detalleRecortado) {
}
