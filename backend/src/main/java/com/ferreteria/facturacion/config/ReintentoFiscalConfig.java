package com.ferreteria.facturacion.config;

import com.ferreteria.facturacion.motor.PoliticaDeReintento;
import com.ferreteria.facturacion.reintento.ProgramacionDelReintento;
import com.ferreteria.facturacion.reintento.ReintentoFiscalJob;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * La programación del job de reintento ({@link ProgramacionDelReintento}, rama 7): existe solo con
 * {@code app.programacion.habilitada} (prendida por defecto; los tests la apagan), como las tareas programadas de
 * {@code ProgramacionConfig}. Corre cada {@code app.facturacion.reintento.cada} ({@link PoliticaDeReintento#cada()}).
 *
 * <p>Su ejecutor es interno y no un bean, igual que no lo es ningún otro {@code ScheduledExecutorService}: en Spring Boot 3.3
 * el scheduler de los {@code @Scheduled} (el del recálculo diario y las cotizaciones) se autoconfigura solo si no hay ningún
 * {@code TaskScheduler} ni {@code ScheduledExecutorService} en el contexto.
 */
@Configuration
public class ReintentoFiscalConfig {

    @Bean
    @ConditionalOnProperty(name = "app.programacion.habilitada", havingValue = "true", matchIfMissing = true)
    public ProgramacionDelReintento programacionDelReintento(ReintentoFiscalJob job, PoliticaDeReintento politica) {
        return new ProgramacionDelReintento(job, politica.cada());
    }
}
