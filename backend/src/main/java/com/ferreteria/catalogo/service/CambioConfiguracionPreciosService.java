package com.ferreteria.catalogo.service;

import com.ferreteria.catalogo.dto.ArticulosQueCambianResponse;
import com.ferreteria.catalogo.dto.ConfiguracionPreciosGuardadaResponse;
import com.ferreteria.ferreteria.model.ConfiguracionPrecios;
import com.ferreteria.ferreteria.service.ConfiguracionPreciosService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El Dueño cambia el margen por defecto o el multiplo de redondeo de su ferreteria y se recalculan los precios que
 * dependen de ellos. Vive en el catalogo porque el modulo ferreteria no conoce a los articulos.
 */
@Service
public class CambioConfiguracionPreciosService {

    /** Cuantos ejemplos de precio de hoy y nuevo se muestran antes de confirmar. */
    static final int EJEMPLOS = 5;

    private final ConfiguracionPreciosService configuracionPreciosService;
    private final RecalculoPreciosService recalculoPreciosService;

    public CambioConfiguracionPreciosService(
            ConfiguracionPreciosService configuracionPreciosService, RecalculoPreciosService recalculoPreciosService) {
        this.configuracionPreciosService = configuracionPreciosService;
        this.recalculoPreciosService = recalculoPreciosService;
    }

    /** Guarda y recalcula en la misma transaccion: si el recalculo falla (409 sin cotizacion), no queda nada guardado. */
    @Transactional
    public ConfiguracionPreciosGuardadaResponse guardar(ConfiguracionPrecios nueva, Long usuarioId) {
        ConfiguracionPrecios anterior = configuracionPreciosService.guardar(nueva);
        RecalculoPreciosService.ResultadoRecalculo resultado =
                recalculoPreciosService.porCambioDeConfiguracion(anterior, nueva, usuarioId);
        return new ConfiguracionPreciosGuardadaResponse(nueva.margenDefectoPorcentaje(), nueva.redondeoMultiplo(),
                resultado.articulosRecalculados(), resultado.avisos());
    }

    /**
     * Cuantos articulos se recalcularian con la configuracion nueva y hasta {@value #EJEMPLOS} de los que cambian de
     * precio: el aviso antes de confirmar.
     */
    @Transactional(readOnly = true)
    public ArticulosQueCambianResponse articulosQueCambian(ConfiguracionPrecios nueva) {
        ConfiguracionPrecios actual = configuracionPreciosService.actual();
        return new ArticulosQueCambianResponse(
                recalculoPreciosService.contarPorCambioDeConfiguracion(actual, nueva),
                recalculoPreciosService.ejemplosPorCambioDeConfiguracion(actual, nueva, EJEMPLOS));
    }
}
