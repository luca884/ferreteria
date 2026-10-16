package com.ferreteria.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.ZoneId;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/** La programacion de tareas se prende por defecto y se apaga con app.programacion.habilitada=false (los tests). */
class ProgramacionConfigTest {

    private final ApplicationContextRunner contexto = new ApplicationContextRunner()
            .withUserConfiguration(ProgramacionConfig.class);

    @Test
    @DisplayName("sin la propiedad la programacion esta prendida: se registra el procesador de @Scheduled")
    void prendidaPorDefecto() {
        contexto.run(c -> assertThat(c).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class));
    }

    @Test
    @DisplayName("con habilitada=true tambien esta prendida")
    void prendidaExplicita() {
        contexto.withPropertyValues("app.programacion.habilitada=true")
                .run(c -> assertThat(c).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class));
    }

    @Test
    @DisplayName("con habilitada=false no se registra el procesador de @Scheduled: ninguna tarea corre sola")
    void apagada() {
        contexto.withPropertyValues("app.programacion.habilitada=false")
                .run(c -> assertThat(c).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class));
    }

    @Test
    @DisplayName("hay un Clock con la zona de Buenos Aires, para las cuentas de hora local")
    void relojDeBuenosAires() {
        new ApplicationContextRunner().withUserConfiguration(RelojConfig.class).run(c -> {
            assertThat(c).hasSingleBean(Clock.class);
            assertThat(c.getBean(Clock.class).getZone()).isEqualTo(ZoneId.of("America/Argentina/Buenos_Aires"));
        });
    }
}
