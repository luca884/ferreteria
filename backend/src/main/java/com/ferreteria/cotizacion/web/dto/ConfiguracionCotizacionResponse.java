package com.ferreteria.cotizacion.web.dto;

import com.ferreteria.cotizacion.model.CatalogoCotizaciones;
import com.ferreteria.cotizacion.model.ConfiguracionCotizacion;
import com.ferreteria.cotizacion.model.LadoCotizacion;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * La configuracion de cotizacion de la ferreteria. {@code vigente} y {@code vigenteDesde} son el dolar con el que se
 * calcularon sus precios la ultima vez (vacios si todavia no se fijo ninguno): un cambio de {@code dolarPrecios} o de
 * {@code ladoPrecios} no los toca hasta el proximo recalculo.
 */
public record ConfiguracionCotizacionResponse(
        boolean activa,
        String dolarPrecios,
        LadoCotizacion ladoPrecios,
        List<String> visibles,
        String horaRecalculo,
        BigDecimal vigente,
        Instant vigenteDesde) {

    public static ConfiguracionCotizacionResponse de(ConfiguracionCotizacion c) {
        return new ConfiguracionCotizacionResponse(c.isActiva(),
                CatalogoCotizaciones.codigo(CatalogoCotizaciones.USD, c.getCasaPrecios()), c.getLadoPrecios(),
                c.getVisibles(), c.getHoraRecalculo().format(HORA), c.getVigente(), c.getVigenteDesde());
    }

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
}
