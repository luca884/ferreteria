package com.ferreteria.catalogo.service;

import com.ferreteria.common.config.RelojConfig;
import com.ferreteria.ferreteria.repository.FerreteriaRepository;
import com.ferreteria.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Recalculo diario de los precios en dolares. Un tick por minuto, en hora de Buenos Aires: por cada ferreteria activa
 * fija su propio {@code TenantContext}, le pide al servicio que recalcule el dia (que solo corre a la hora configurada de
 * esa ferreteria y una vez por dia) y lo limpia en un finally. Un fallo en una ferreteria se registra y no frena a las
 * demas. Recorre las ferreterias con {@code FerreteriaRepository}, que no es de tenant; despues cada una usa solo
 * metodos {@code *Scoped}, asi que no hace falta {@code @UnscopedTenantAccess}.
 */
@Component
public class RecalculoDiarioJob {

    private static final Logger log = LoggerFactory.getLogger(RecalculoDiarioJob.class);

    private final FerreteriaRepository ferreterias;
    private final RecalculoPorCotizacionService recalculoPorCotizacionService;
    private final Clock reloj;

    public RecalculoDiarioJob(
            FerreteriaRepository ferreterias, RecalculoPorCotizacionService recalculoPorCotizacionService, Clock reloj) {
        this.ferreterias = ferreterias;
        this.recalculoPorCotizacionService = recalculoPorCotizacionService;
        this.reloj = reloj;
    }

    @Scheduled(cron = "0 * * * * *", zone = "America/Argentina/Buenos_Aires")
    public void ejecutar() {
        try {
            // La hora de la ferreteria es de Buenos Aires, sea cual sea la zona del reloj.
            LocalDateTime ahora = LocalDateTime.ofInstant(reloj.instant(), RelojConfig.BUENOS_AIRES);
            for (Long ferreteriaId : ferreterias.idsActivas()) {
                recalcular(ferreteriaId, ahora);
            }
        } catch (RuntimeException e) {
            // Una excepcion que sale de un @Scheduled no mata al scheduler, pero asi queda registrada.
            log.error("Fallo el recalculo diario de precios en dolares", e);
        }
    }

    private void recalcular(Long ferreteriaId, LocalDateTime ahora) {
        TenantContext.setCurrentFerreteriaId(ferreteriaId);
        try {
            recalculoPorCotizacionService.recalcularDelDia(ahora).ifPresent(resultado -> log.info(
                    "Recalculo diario de la ferreteria {}: {} articulos con el dolar en {}",
                    ferreteriaId, resultado.articulosRecalculados(), resultado.cotizacion()));
        } catch (RuntimeException e) {
            log.error("Fallo el recalculo diario de la ferreteria {}", ferreteriaId, e);
        } finally {
            TenantContext.clear();
        }
    }
}
