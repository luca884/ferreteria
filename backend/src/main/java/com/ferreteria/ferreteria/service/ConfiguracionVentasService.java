package com.ferreteria.ferreteria.service;

import com.ferreteria.ferreteria.api.TopeConsumidorFinal;
import com.ferreteria.ferreteria.model.ConfiguracionVentas;
import com.ferreteria.ferreteria.model.Ferreteria;
import com.ferreteria.ferreteria.repository.FerreteriaRepository;
import com.ferreteria.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/** Lee y guarda la configuracion de ventas de la ferreteria del JWT, nunca de un parametro del cliente (GDS-32). */
@Service
public class ConfiguracionVentasService implements TopeConsumidorFinal {

    private final FerreteriaRepository ferreteriaRepository;

    public ConfiguracionVentasService(FerreteriaRepository ferreteriaRepository) {
        this.ferreteriaRepository = ferreteriaRepository;
    }

    @Transactional(readOnly = true)
    public ConfiguracionVentas actual() {
        return new ConfiguracionVentas(ferreteriaDelToken().getTopeConsumidorFinal());
    }

    @Transactional
    public ConfiguracionVentas guardar(ConfiguracionVentas nueva) {
        Ferreteria ferreteria = ferreteriaDelToken();
        ferreteria.cambiarTopeConsumidorFinal(nueva.topeConsumidorFinal());
        return new ConfiguracionVentas(ferreteria.getTopeConsumidorFinal());
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal tope() {
        return ferreteriaDelToken().getTopeConsumidorFinal();
    }

    private Ferreteria ferreteriaDelToken() {
        Long ferreteriaId = TenantContext.requireFerreteriaId();
        // Un token valido siempre tiene su ferreteria: si falta es un estado corrupto, no un 404.
        return ferreteriaRepository.findById(ferreteriaId)
                .orElseThrow(() -> new IllegalStateException("No existe la ferreteria " + ferreteriaId));
    }
}
