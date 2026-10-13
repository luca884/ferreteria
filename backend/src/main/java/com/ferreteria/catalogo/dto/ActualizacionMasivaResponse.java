package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.ConteosMasivos;

/** El resultado de aplicar: los contadores del plan y cuantas entradas de historial se escribieron. */
public record ActualizacionMasivaResponse(ConteosMasivos conteos, int entradasDeHistorial) {
}
