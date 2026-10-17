package com.ferreteria.common.config;

import com.ferreteria.common.HoraArgentina;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.ZoneId;

/**
 * El reloj del sistema, inyectable para poder fijarlo en los tests. Lleva la zona de Buenos Aires para las cuentas de
 * hora local (la hora del recalculo diario); las fechas que se guardan en la base siguen siendo {@code Instant}.
 */
@Configuration
public class RelojConfig {

    public static final ZoneId BUENOS_AIRES = HoraArgentina.ZONA;

    @Bean
    public Clock reloj() {
        return Clock.system(BUENOS_AIRES);
    }
}
