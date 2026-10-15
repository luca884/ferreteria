package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Los casos del precio final viven en un JSON que leen este test y la spec de Vitest
 * (frontend/src/app/core/formato/precio-final.spec.ts): el formulario de articulo muestra el precio en vivo y
 * tiene que dar exactamente lo mismo que PrecioCalculator.
 */
class CasosCompartidosPrecioTest {

    private static JsonNode casos;

    @BeforeAll
    static void leerJson() throws IOException {
        try (InputStream in = CasosCompartidosPrecioTest.class.getResourceAsStream("/catalogo/casos-precio.json")) {
            assertThat(in).as("catalogo/casos-precio.json en el classpath de test").isNotNull();
            casos = new ObjectMapper().readTree(in).get("casos");
        }
    }

    private static BigDecimal bd(JsonNode caso, String campo) {
        JsonNode nodo = caso.get(campo);
        return nodo == null || nodo.isNull() ? null : new BigDecimal(nodo.asText());
    }

    @Test
    @DisplayName("cada caso del JSON da el precio esperado, con escala 2")
    void cadaCaso() {
        assertThat(casos.size()).isGreaterThanOrEqualTo(20);
        for (JsonNode caso : casos) {
            BigDecimal precio = PrecioCalculator.precioFinal(bd(caso, "costo"), bd(caso, "margen"),
                    AlicuotaIva.valueOf(caso.get("alicuota").asText()), bd(caso, "multiplo"));

            assertThat(precio).as(caso.get("nombre").asText()).isEqualTo(bd(caso, "precio"));
        }
    }
}
