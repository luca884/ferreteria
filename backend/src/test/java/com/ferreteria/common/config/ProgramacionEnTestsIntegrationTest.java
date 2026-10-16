package com.ferreteria.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.cotizacion.service.ActualizacionCotizacionesJob;
import com.ferreteria.support.IntegrationTestBase;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

/**
 * Ningun test toca la red ni corre tareas solas (R-009): application-test.yml apaga la programacion y apunta las dos
 * fuentes a 127.0.0.1:9, donde no escucha nadie.
 */
class ProgramacionEnTestsIntegrationTest extends IntegrationTestBase {

    @Autowired
    Environment entorno;

    @Autowired
    ApplicationContext contexto;

    @Test
    @DisplayName("la programacion esta apagada y las dos URL de las fuentes apuntan a 127.0.0.1:9")
    void propiedadesDeTest() {
        assertThat(entorno.getProperty("app.programacion.habilitada")).isEqualTo("false");
        assertThat(entorno.getProperty("app.cotizacion.dolarapi.url")).isEqualTo("http://127.0.0.1:9");
        assertThat(entorno.getProperty("app.cotizacion.bluelytics.url")).isEqualTo("http://127.0.0.1:9");
    }

    @Test
    @DisplayName("el job de cotizaciones existe como bean pero no hay scheduler que lo dispare")
    void elJobExistePeroNoCorre() {
        assertThat(contexto.getBeansOfType(ActualizacionCotizacionesJob.class)).hasSize(1);
        assertThat(contexto.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    }

    @Test
    @DisplayName("el contexto completo tiene su Clock")
    void hayUnReloj() {
        assertThat(contexto.getBeansOfType(Clock.class)).hasSize(1);
    }
}
