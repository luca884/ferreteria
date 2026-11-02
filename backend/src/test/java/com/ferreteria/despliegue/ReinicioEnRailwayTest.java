package com.ferreteria.despliegue;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Con {@code -XX:+ExitOnOutOfMemoryError} (Dockerfile) la JVM se cierra con un codigo de error si se queda sin memoria, y
 * Railway la reinicia. Con pocos reintentos, varias caidas seguidas podrian dejar el backend apagado; 10 es lo que
 * Railway usa por defecto y vale en cualquier plan (tope-columnas-listas, L7).
 */
class ReinicioEnRailwayTest {

    @Test
    void railwayReiniciaElBackendSiSeCaeConDiezReintentos() throws IOException {
        JsonNode deploy = new ObjectMapper().readTree(Path.of("railway.json").toFile()).path("deploy");

        assertThat(deploy.path("restartPolicyType").asText()).isEqualTo("ON_FAILURE");
        assertThat(deploy.path("restartPolicyMaxRetries").asInt()).isGreaterThanOrEqualTo(10);
    }
}
