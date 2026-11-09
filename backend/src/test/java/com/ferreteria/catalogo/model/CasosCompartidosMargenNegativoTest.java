package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Los casos del aviso de margen negativo viven en un JSON que leen este test y la spec de Vitest
 * (frontend/src/app/core/formato/margen-negativo.spec.ts): la ficha avisa mientras se escribe el precio manual (#26) y
 * tiene que dar exactamente el mismo texto que MargenNegativo en las vistas previas.
 */
class CasosCompartidosMargenNegativoTest {

    private static JsonNode casos;

    @BeforeAll
    static void leerJson() throws IOException {
        try (InputStream in = CasosCompartidosMargenNegativoTest.class.getResourceAsStream(
                "/catalogo/casos-margen-negativo.json")) {
            assertThat(in).as("catalogo/casos-margen-negativo.json en el classpath de test").isNotNull();
            casos = new ObjectMapper().readTree(in).get("casos");
        }
    }

    private static BigDecimal bd(JsonNode caso, String campo) {
        return new BigDecimal(caso.get(campo).asText());
    }

    /** Los recargos del costo (CU-13) son opcionales: sin el campo, el articulo no tiene ninguno. */
    private static RecargosDeCosto recargos(JsonNode caso) {
        List<RecargoCosto> lista = new ArrayList<>();
        if (caso.has("recargos")) {
            for (JsonNode recargo : caso.get("recargos")) {
                lista.add(new RecargoCosto(recargo.get("nombre").asText(),
                    new BigDecimal(recargo.get("porcentaje").asText())));
            }
        }
        return new RecargosDeCosto(lista);
    }

    @Test
    @DisplayName("el JSON trae casos que avisan y que no, con recargos y en dolares")
    void hayCasosDeCadaTipo() {
        int avisan = 0;
        int conRecargos = 0;
        int enDolares = 0;
        for (JsonNode caso : casos) {
            avisan += caso.get("aviso").isNull() ? 0 : 1;
            conRecargos += caso.has("recargos") ? 1 : 0;
            enDolares += caso.has("cotizacion") ? 1 : 0;
        }
        assertThat(casos.size()).isGreaterThanOrEqualTo(12);
        assertThat(avisan).isGreaterThanOrEqualTo(8);
        assertThat(casos.size() - avisan).isGreaterThanOrEqualTo(4);
        assertThat(conRecargos).isGreaterThanOrEqualTo(2);
        assertThat(enDolares).isGreaterThanOrEqualTo(4);
    }

    /**
     * El JSON trae espacios comunes y la spec del front compara su texto con los espacios raros pasados a comunes
     * (sinEspaciosRaros): aca igual. Los espacios duros del importe (U+00A0) los fija MargenNegativoTest.
     */
    private static String sinEspaciosDuros(String texto) {
        return texto.replace('\u00a0', ' ');
    }

    @Test
    @DisplayName("cada caso del JSON da el aviso esperado, o ninguno")
    void cadaCaso() {
        for (JsonNode caso : casos) {
            BigDecimal cotizacion = caso.has("cotizacion") ? bd(caso, "cotizacion") : BigDecimal.ONE;
            Optional<String> aviso = MargenNegativo.mensaje(bd(caso, "costo"), recargos(caso), cotizacion,
                AlicuotaIva.valueOf(caso.get("alicuota").asText()), bd(caso, "precio"))
                .map(CasosCompartidosMargenNegativoTest::sinEspaciosDuros);

            JsonNode esperado = caso.get("aviso");
            assertThat(aviso).as(caso.get("nombre").asText())
                .isEqualTo(esperado.isNull() ? Optional.empty() : Optional.of(esperado.asText()));
        }
    }
}
