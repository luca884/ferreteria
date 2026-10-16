package com.ferreteria.cotizacion.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * Consulta las APIs de cotizacion cada 30 minutos, UNA vez para todo el sistema (la cotizacion es global). No conoce
 * ferreterias ni {@code TenantContext}. Solo actualiza la cotizacion mostrada: no toca ningun precio.
 */
@Component
public class ActualizacionCotizacionesJob {

    private static final Logger log = LoggerFactory.getLogger(ActualizacionCotizacionesJob.class);

    private final ActualizadorDeCotizaciones actualizador;

    public ActualizacionCotizacionesJob(ActualizadorDeCotizaciones actualizador) {
        this.actualizador = actualizador;
    }

    @Scheduled(fixedDelay = 30, timeUnit = TimeUnit.MINUTES)
    public void actualizar() {
        try {
            actualizador.actualizar();
        } catch (RuntimeException e) {
            // Una excepcion que sale de un @Scheduled no mata al scheduler, pero asi queda registrada y la proxima corrida sigue.
            log.error("Fallo la actualizacion de cotizaciones", e);
        }
    }
}
