package com.ferreteria.ferreteria.service;

import com.ferreteria.ferreteria.model.ConfiguracionPrecios;
import com.ferreteria.ferreteria.model.Ferreteria;
import com.ferreteria.ferreteria.repository.FerreteriaRepository;
import com.ferreteria.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lee y guarda la configuracion de precios de la ferreteria del JWT, nunca de un parametro del cliente. */
@Service
public class ConfiguracionPreciosService {

    private final FerreteriaRepository ferreteriaRepository;

    public ConfiguracionPreciosService(FerreteriaRepository ferreteriaRepository) {
        this.ferreteriaRepository = ferreteriaRepository;
    }

    @Transactional(readOnly = true)
    public ConfiguracionPrecios actual() {
        return configuracionDe(ferreteriaDelToken());
    }

    /**
     * Guarda el margen por defecto y el multiplo de redondeo de la ferreteria del token y devuelve los que tenia, para
     * que el catalogo sepa que precios recalcular. No recalcula nada por su cuenta.
     */
    @Transactional
    public ConfiguracionPrecios guardar(ConfiguracionPrecios nueva) {
        Ferreteria ferreteria = ferreteriaDelToken();
        ConfiguracionPrecios anterior = configuracionDe(ferreteria);
        ferreteria.cambiarConfiguracionPrecios(nueva.margenDefectoPorcentaje(), nueva.redondeoMultiplo());
        return anterior;
    }

    private Ferreteria ferreteriaDelToken() {
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        // Un token valido siempre tiene su ferreteria: si falta es un estado corrupto, no un 404.
        return ferreteriaRepository.findById(ferreteriaId)
                .orElseThrow(() -> new IllegalStateException("No existe la ferreteria " + ferreteriaId));
    }

    private static ConfiguracionPrecios configuracionDe(Ferreteria ferreteria) {
        return new ConfiguracionPrecios(ferreteria.getMargenDefectoPorcentaje(), ferreteria.getRedondeoMultiplo());
    }
}
