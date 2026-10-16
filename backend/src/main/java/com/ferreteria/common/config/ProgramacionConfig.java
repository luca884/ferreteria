package com.ferreteria.common.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Prende las tareas programadas (@Scheduled). Se apaga con {@code app.programacion.habilitada=false}, que es lo que
 * hace el perfil de test: ahi ninguna tarea corre sola ni llama a una API externa.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "app.programacion.habilitada", havingValue = "true", matchIfMissing = true)
public class ProgramacionConfig {
}
