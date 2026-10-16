package com.ferreteria.cotizacion.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

/** El job consulta las APIs una sola vez por corrida, para todo el sistema, cada 30 minutos. */
class ActualizacionCotizacionesJobTest {

    private final ActualizadorDeCotizaciones actualizador = mock(ActualizadorDeCotizaciones.class);
    private final ActualizacionCotizacionesJob job = new ActualizacionCotizacionesJob(actualizador);

    @Test
    @DisplayName("una invocacion del job llama una sola vez al actualizador (consulta global, no una por ferreteria)")
    void unaSolaConsultaGlobal() {
        when(actualizador.actualizar()).thenReturn(Optional.of("DOLARAPI"));

        job.actualizar();

        verify(actualizador, times(1)).actualizar();
    }

    @Test
    @DisplayName("cada invocacion es una consulta nueva")
    void cadaCorridaConsulta() {
        job.actualizar();
        job.actualizar();

        verify(actualizador, times(2)).actualizar();
    }

    @Test
    @DisplayName("una excepcion del actualizador no sale del job: no mata al scheduler")
    void unaExcepcionNoSale() {
        when(actualizador.actualizar()).thenThrow(new IllegalStateException("la base no responde"));

        assertThatCode(job::actualizar).doesNotThrowAnyException();
        verify(actualizador, times(1)).actualizar();
    }

    @Test
    @DisplayName("esta programado con un fixedDelay de 30 minutos")
    void cadaTreintaMinutos() throws NoSuchMethodException {
        Scheduled programado = ActualizacionCotizacionesJob.class.getMethod("actualizar")
                .getAnnotation(Scheduled.class);

        assertThat(programado).isNotNull();
        assertThat(programado.fixedDelay()).isEqualTo(30);
        assertThat(programado.timeUnit()).isEqualTo(TimeUnit.MINUTES);
        assertThat(programado.cron()).isEmpty();
    }
}
