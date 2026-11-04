package com.ferreteria.cotizacion.web.dto;

import com.ferreteria.cotizacion.model.LadoCotizacion;

import java.math.BigDecimal;
import java.util.List;

/**
 * Configuracion de cotizacion de la ferreteria (PUT completo). {@code dolarPrecios} es UN codigo {@code MONEDA:casa}
 * de dolar, por ejemplo {@code USD:oficial}; {@code visibles}, los codigos que se muestran; {@code horaRecalculo},
 * {@code HH:mm} de Buenos Aires; {@code umbralDesvio}, el porcentaje (1 a 50) por encima del cual un dolar nuevo espera
 * la confirmacion del Dueno, o null para conservar el guardado. Todo se valida en el servicio, que informa cada error en
 * espanol.
 */
public record ConfiguracionCotizacionRequest(
        Boolean activa,
        String dolarPrecios,
        LadoCotizacion ladoPrecios,
        List<String> visibles,
        String horaRecalculo,
        BigDecimal umbralDesvio) {

    /** Sin {@code umbralDesvio}: conserva el guardado. */
    public ConfiguracionCotizacionRequest(Boolean activa, String dolarPrecios, LadoCotizacion ladoPrecios,
                                          List<String> visibles, String horaRecalculo) {
        this(activa, dolarPrecios, ladoPrecios, visibles, horaRecalculo, null);
    }
}
