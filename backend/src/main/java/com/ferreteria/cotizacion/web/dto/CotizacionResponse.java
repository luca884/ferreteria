package com.ferreteria.cotizacion.web.dto;

import java.time.Instant;
import java.util.List;

/**
 * La cotizacion que ve la ferreteria del token. {@code ultimaRespuesta} es el momento de la ultima respuesta exitosa de
 * cualquier API (vacio si todavia no hay ninguna) y {@code desactualizada}, si pasaron mas de 1 hora desde entonces.
 * No lleva ningun dato de otras ferreterias.
 */
public record CotizacionResponse(
        boolean activa,
        boolean desactualizada,
        Instant ultimaRespuesta,
        List<TipoCotizacionResponse> visibles,
        PreciosResponse precios) {}
