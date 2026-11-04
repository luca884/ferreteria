package com.ferreteria.cotizacion.web.dto;

import com.ferreteria.cotizacion.model.CatalogoCotizaciones;
import com.ferreteria.cotizacion.model.ConfiguracionCotizacion;
import com.ferreteria.cotizacion.model.DolarAConfirmar;
import com.ferreteria.cotizacion.model.LadoCotizacion;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * La configuracion de cotizacion de la ferreteria. {@code vigente} y {@code vigenteDesde} son el dolar con el que se
 * calcularon sus precios la ultima vez (vacios si todavia no se fijo ninguno): un cambio de {@code dolarPrecios} o de
 * {@code ladoPrecios} no los toca hasta el proximo recalculo. {@code aConfirmar} es el dolar nuevo que se aleja mas
 * del umbral de la ferreteria ({@code umbralDesvio}) del vigente y espera la confirmacion del Dueno (auditoria H09), o null si no hay ninguno.
 */
public record ConfiguracionCotizacionResponse(
        boolean activa,
        String dolarPrecios,
        LadoCotizacion ladoPrecios,
        List<String> visibles,
        String horaRecalculo,
        BigDecimal umbralDesvio,
        BigDecimal vigente,
        Instant vigenteDesde,
        AConfirmar aConfirmar) {

    /** El aviso tal cual se le muestra al Dueno, con los valores para el boton Confirmar. */
    public record AConfirmar(BigDecimal nuevo, BigDecimal vigente, BigDecimal desvioPorcentaje, String mensaje) {
    }

    public static ConfiguracionCotizacionResponse de(ConfiguracionCotizacion c, DolarAConfirmar aConfirmar) {
        return new ConfiguracionCotizacionResponse(c.isActiva(),
                CatalogoCotizaciones.codigo(CatalogoCotizaciones.USD, c.getCasaPrecios()), c.getLadoPrecios(),
                c.getVisibles(), c.getHoraRecalculo().format(HORA), c.getUmbralDesvioPorcentaje(), c.getVigente(), c.getVigenteDesde(),
                aConfirmar == null ? null : new AConfirmar(aConfirmar.nuevo(), aConfirmar.vigente(),
                        aConfirmar.desvioPorcentaje(), aConfirmar.mensaje()));
    }

    private static final DateTimeFormatter HORA = DateTimeFormatter.ofPattern("HH:mm");
}
