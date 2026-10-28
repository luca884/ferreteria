package com.ferreteria.support;

import java.time.Instant;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * El {@link RelojControlable} como {@code Clock} principal del contexto, solo para los tests que lo importan
 * ({@code @Import(RelojControlableConfig.class)}): el bean {@code reloj} de {@code RelojConfig} sigue existiendo, pero se
 * inyecta este. En {@code main} no hay ningún {@code @Primary} de reloj. Arranca el miércoles 2026-10-28 a las 10:15:30 de
 * Buenos Aires; cada test lo fija a la hora que necesita.
 */
@TestConfiguration(proxyBeanMethods = false)
public class RelojControlableConfig {

    public static final Instant INICIO = Instant.parse("2026-10-28T13:15:30Z");

    @Bean
    @Primary
    public RelojControlable relojControlable() {
        return new RelojControlable(INICIO);
    }
}
