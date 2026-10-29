package com.ferreteria.facturacion.arca;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registra {@link PropiedadesArca} y la valida al arrancar contra el presupuesto de una llamada del motor
 * ({@code app.facturacion.motor.presupuesto-llamada}), que está fuera de {@code app.facturacion.arca}: si no entra, la
 * aplicación no arranca (C6, S5). El cableado del cliente en los ambientes es de la rama 6, T6.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PropiedadesArca.class)
public class ConfiguracionArca {

    public ConfiguracionArca(PropiedadesArca propiedades,
            @Value("${app.facturacion.motor.presupuesto-llamada}") Duration presupuestoLlamada) {
        propiedades.validarContra(presupuestoLlamada);
    }
}
