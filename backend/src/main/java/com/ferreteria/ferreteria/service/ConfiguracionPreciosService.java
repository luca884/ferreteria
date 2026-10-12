package com.ferreteria.ferreteria.service;

import com.ferreteria.ferreteria.model.ConfiguracionPrecios;
import com.ferreteria.ferreteria.repository.FerreteriaRepository;
import com.ferreteria.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lee la configuracion de precios de la ferreteria del JWT, nunca de un parametro del cliente. */
@Service
public class ConfiguracionPreciosService {

    private final FerreteriaRepository ferreteriaRepository;

    public ConfiguracionPreciosService(FerreteriaRepository ferreteriaRepository) {
        this.ferreteriaRepository = ferreteriaRepository;
    }

    @Transactional(readOnly = true)
    public ConfiguracionPrecios actual() {
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        // Un token valido siempre tiene su ferreteria: si falta es un estado corrupto, no un 404.
        return ferreteriaRepository.findById(ferreteriaId)
                .map(f -> new ConfiguracionPrecios(f.getMargenDefectoPorcentaje(), f.getRedondeoMultiplo()))
                .orElseThrow(() -> new IllegalStateException("No existe la ferreteria " + ferreteriaId));
    }
}
