package com.ferreteria.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.AnnotatedElementUtils;

/**
 * R-007: al arrancar, si la zona de la JVM no es la de Buenos Aires se avisa con un WARN (con las dos zonas) y NUNCA se aborta.
 * Se prueba {@code verificar(ZoneId)} directamente y se captura el log con un {@link ListAppender} de logback.
 */
class ChequeoZonaHorariaTest {

    private static final ZoneId BUENOS_AIRES = ZoneId.of("America/Argentina/Buenos_Aires");

    private final ChequeoZonaHoraria chequeo = new ChequeoZonaHoraria();
    private final ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ChequeoZonaHoraria.class);
    private ListAppender<ILoggingEvent> eventos;

    @BeforeEach
    void capturarElLog() {
        eventos = new ListAppender<>();
        eventos.start();
        logger.addAppender(eventos);
    }

    @AfterEach
    void soltarElLog() {
        logger.detachAppender(eventos);
    }

    @Test
    @DisplayName("R-007: con la JVM en UTC avisa UNA vez, en WARN, con las dos zonas y cómo arreglarlo")
    void utcAvisaConLasDosZonas() {
        chequeo.verificar(ZoneId.of("UTC"));

        List<ILoggingEvent> avisos = eventos.list;
        assertThat(avisos).hasSize(1);
        assertThat(avisos.get(0).getLevel()).isEqualTo(Level.WARN);
        assertThat(avisos.get(0).getFormattedMessage()).contains("UTC").contains("America/Argentina/Buenos_Aires")
                .contains("TZ=America/Argentina/Buenos_Aires").contains("-Duser.timezone=America/Argentina/Buenos_Aires");
    }

    @Test
    @DisplayName("R-007: con la JVM en Buenos Aires no avisa nada")
    void buenosAiresNoAvisa() {
        chequeo.verificar(BUENOS_AIRES);

        assertThat(eventos.list).isEmpty();
    }

    @Test
    @DisplayName("R-007: nunca lanza, tampoco con la zona equivocada (no frena el arranque)")
    void noLanza() {
        assertThatCode(() -> chequeo.verificar(ZoneId.of("UTC"))).doesNotThrowAnyException();
        assertThatCode(() -> chequeo.verificar(BUENOS_AIRES)).doesNotThrowAnyException();
        assertThat(eventos.list).hasSize(1); // el de UTC: el chequeo corrió de verdad
    }

    @Test
    @DisplayName("R-007: un alias de la misma zona (America/Buenos_Aires) se compara por id y avisa: falso positivo aceptado")
    void unAliasAvisa() {
        chequeo.verificar(ZoneId.of("America/Buenos_Aires"));

        assertThat(eventos.list).hasSize(1);
        assertThat(eventos.list.get(0).getFormattedMessage()).contains("America/Buenos_Aires");
    }

    @Test
    @DisplayName("R-007: el chequeo corre al terminar el arranque: alArrancar escucha ApplicationReadyEvent")
    void escuchaElArranque() throws NoSuchMethodException {
        EventListener escucha = AnnotatedElementUtils.findMergedAnnotation(
                ChequeoZonaHoraria.class.getDeclaredMethod("alArrancar"), EventListener.class);

        assertThat(escucha).isNotNull();
        assertThat(escucha.classes()).containsExactly(ApplicationReadyEvent.class);
    }
}
