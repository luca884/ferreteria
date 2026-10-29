package com.ferreteria.despliegue;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ferreteria.facturacion.reintento.ProgramacionDelReintento;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlProcessor.MatchStatus;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle;
import org.springframework.core.io.ClassPathResource;
import org.springframework.util.PropertyPlaceholderHelper;

/**
 * El apagado ordenado en Railway (CU-07, rama 3 de facturacion, S6). En un deploy, Railway le manda SIGTERM al contenedor
 * viejo y, pasados {@code deploy.drainingSeconds} de backend/railway.json, SIGKILL. Con el SIGTERM, Spring Boot primero
 * corre la fase de apagado ordenado del servidor web ({@code server.shutdown=graceful}), que espera los pedidos en curso
 * hasta {@code spring.lifecycle.timeout-per-shutdown-phase}; en esa misma fase, y a la par, espera la pasada en curso del
 * job de reintento fiscal (rama 7, {@code ProgramacionDelReintento}), que por eso no suma termino. Despues destruye los beans de a uno: el ejecutor fiscal espera
 * los pedidos de CAE en curso ({@code app.facturacion.venta.espera-al-apagar}) y el de las tareas programadas espera la que
 * este corriendo ({@code spring.task.scheduling.shutdown.await-termination-period}). Las dos esperas van una detras de la
 * otra (los dos ejecutores esperan en su destroy, no en la fase del ciclo de vida), asi que se suman.
 *
 * <p>Los tiempos son los de produccion: el documento sin perfil de application.yml y application-prod.yml, con el valor
 * por defecto de cada variable. Si en Railway se sube alguno por variable, hay que subir tambien drainingSeconds.
 */
class ApagadoOrdenadoTest {

    /** Lo que queda despues de las esperas: cerrar el pool de conexiones, los logs y la salida de la JVM. */
    private static final Duration MARGEN = Duration.ofSeconds(10);

    private static final PropertyPlaceholderHelper DEFAULTS = new PropertyPlaceholderHelper("${", "}", ":", true);

    @Test
    @DisplayName("el servidor web se apaga ordenado y las tareas programadas en curso se esperan")
    void elServidorYLasTareasProgramadasEsperanLoQueEstaEnCurso() {
        assertThat(propiedadDeProd("server.shutdown")).isEqualTo("graceful");
        assertThat(propiedadDeProd("spring.task.scheduling.shutdown.await-termination")).isEqualTo("true");
    }

    @Test
    @DisplayName("drainingSeconds de railway.json cubre la fase web, la espera del ejecutor fiscal, la de las tareas programadas y un margen")
    void elDrenajeDeRailwayCubreElApagadoOrdenado() throws IOException {
        Duration faseWeb = duracionDeProd("spring.lifecycle.timeout-per-shutdown-phase");
        Duration ejecutorFiscal = duracionDeProd("app.facturacion.venta.espera-al-apagar");
        Duration tareasProgramadas = duracionDeProd("spring.task.scheduling.shutdown.await-termination-period");
        Duration necesario = faseWeb.plus(ejecutorFiscal).plus(tareasProgramadas).plus(MARGEN);

        JsonNode drenaje = new ObjectMapper().readTree(Path.of("railway.json").toFile()).path("deploy").path("drainingSeconds");

        assertThat(drenaje.isIntegralNumber()).as("deploy.drainingSeconds en railway.json: " + drenaje).isTrue();
        assertThat(Duration.ofSeconds(drenaje.asLong()))
            .as("fase web %s + ejecutor fiscal %s + tareas programadas %s + margen %s", faseWeb, ejecutorFiscal,
                tareasProgramadas, MARGEN)
            .isGreaterThanOrEqualTo(necesario);
    }

    @Test
    @DisplayName("la pasada en curso del job de reintento se espera en la fase del servidor web, a la par: no suma término")
    void elJobDeReintentoSeEsperaEnLaFaseWebYNoSumaTermino() {
        ProgramacionDelReintento programacion = new ProgramacionDelReintento(null, Duration.ofSeconds(60));

        assertThat(programacion.getPhase()).isEqualTo(WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE);
    }

    private static Duration duracionDeProd(String clave) {
        String valor = propiedadDeProd(clave);
        assertThat(valor).as(clave).isNotBlank();
        return DurationStyle.detectAndParse(valor);
    }

    /** El valor con que arranca prod: el de application-prod.yml o, si no lo trae, el de application.yml, con su default. */
    private static String propiedadDeProd(String clave) {
        Properties propiedades = yaml("application.yml");
        propiedades.putAll(yaml("application-prod.yml"));
        String valor = propiedades.getProperty(clave);
        return valor == null ? null : DEFAULTS.replacePlaceholders(valor, variable -> null);
    }

    /** Los documentos sin perfil y los del perfil prod; los de otro perfil (dev) no cuentan. */
    private static Properties yaml(String archivo) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(archivo));
        factory.setDocumentMatchers(documento -> {
            Object perfil = documento.get("spring.config.activate.on-profile");
            return perfil == null || "prod".equals(perfil) ? MatchStatus.FOUND : MatchStatus.NOT_FOUND;
        });
        return factory.getObject();
    }
}
