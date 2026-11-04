package com.ferreteria.catalogo.service;

import com.ferreteria.catalogo.dto.RecalculoCotizacionResponse;
import com.ferreteria.cotizacion.api.DolarDePrecios;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Recalculo de los precios de los articulos en dolares de la ferreteria del request (TenantContext). No consulta tickets,
 * ventas ni stock y no se frena por tickets abiertos (decision 16): si un ticket queda con un precio viejo,
 * PRECIOS_CAMBIADOS lo actualiza y avisa al cobrar. Solo depende del puerto {@code cotizacion.api.DolarDePrecios}.
 */
@Service
public class RecalculoPorCotizacionService {

    private static final Logger log = LoggerFactory.getLogger(RecalculoPorCotizacionService.class);

    private final DolarDePrecios dolarDePrecios;
    private final RecalculoPreciosService recalculoPreciosService;

    public RecalculoPorCotizacionService(DolarDePrecios dolarDePrecios, RecalculoPreciosService recalculoPreciosService) {
        this.dolarDePrecios = dolarDePrecios;
        this.recalculoPreciosService = recalculoPreciosService;
    }

    /**
     * «Recalcular ahora» (solo el Dueno): fija el ultimo dolar guardado como vigente y recalcula con el, con el historial
     * a nombre de {@code usuarioId}. Con la cotizacion apagada o sin ningun valor guardado responde 409 (la
     * ConflictException del puerto) sin tocar nada. Un dolar que se aleja mas del umbral de la ferreteria del vigente tambien da 409 con el
     * aviso, salvo que el Dueno lo confirme con {@code confirmaDesvio} (auditoria H09).
     */
    @Transactional
    public RecalculoCotizacionResponse recalcularAhora(Long usuarioId, boolean confirmaDesvio) {
        BigDecimal dolar = dolarDePrecios.fijarUltima(confirmaDesvio);
        return respuesta(dolar, recalculoPreciosService.porCotizacion(dolar, usuarioId));
    }

    /**
     * Recalculo diario de la ferreteria del request. Reclama el dia PRIMERO y en la misma transaccion que el
     * recalculo: si este falla, el reclamo se revierte y se reintenta al minuto siguiente. Vacio si todavia no es la hora
     * de la ferreteria, si hoy ya se hizo, si la cotizacion esta apagada o si no hay ningun valor guardado. El historial
     * queda sin usuario («Automatico»).
     */
    @Transactional
    public Optional<RecalculoCotizacionResponse> recalcularDelDia(LocalDateTime ahoraBuenosAires) {
        // Va primero: reclamarRecalculoDelDia limpia el contexto de persistencia, y asi no hay entidades cargadas antes.
        return dolarDePrecios.reclamarRecalculoDelDia(ahoraBuenosAires)
                .map(dolar -> respuesta(dolar, recalculoPreciosService.porCotizacion(dolar, null)))
                // El diario no tiene pantalla: los articulos salteados (S7) quedan en el log.
                .map(respuesta -> {
                    respuesta.avisos().forEach(aviso -> log.warn("Recalculo diario de precios: {}", aviso));
                    return respuesta;
                });
    }

    private static RecalculoCotizacionResponse respuesta(BigDecimal dolar, RecalculoPreciosService.ResultadoPorCotizacion resultado) {
        return new RecalculoCotizacionResponse(dolar, resultado.articulosRecalculados(), resultado.avisos());
    }
}
