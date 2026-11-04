package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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
    @DisplayName("el JSON trae los casos en dolares con su cotizacion")
    void hayCasosEnDolares() {
        long conCotizacion = 0;
        for (JsonNode caso : casos) {
            if (caso.has("cotizacion")) {
                conCotizacion++;
            }
        }
        assertThat(conCotizacion).isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("cada caso del JSON da el precio esperado, con escala 2")
    void cadaCaso() {
        assertThat(casos.size()).isGreaterThanOrEqualTo(20);
        for (JsonNode caso : casos) {
            // La cotizacion es opcional: si el caso no la trae, vale 1 (pesos).
            BigDecimal cotizacion = caso.has("cotizacion") ? bd(caso, "cotizacion") : BigDecimal.ONE;
            BigDecimal precio = PrecioCalculator.precioFinal(bd(caso, "costo"), recargos(caso), bd(caso, "margen"),
                    AlicuotaIva.valueOf(caso.get("alicuota").asText()), bd(caso, "multiplo"), cotizacion, null);

            assertThat(precio).as(caso.get("nombre").asText()).isEqualTo(bd(caso, "precio"));
        }
    }

    /** Los recargos del costo (CU-13) son opcionales: sin el campo, el articulo no tiene ninguno. */
    private static RecargosDeCosto recargos(JsonNode caso) {
        List<RecargoCosto> lista = new ArrayList<>();
        if (caso.has("recargos")) {
            for (JsonNode recargo : caso.get("recargos")) {
                lista.add(new RecargoCosto(recargo.get("nombre").asText(), new BigDecimal(recargo.get("porcentaje").asText())));
            }
        }
        return new RecargosDeCosto(lista);
    }

    @Test
    @DisplayName("los casos con recargos traen el costo real y el JSON incluye el de la factura (4.44 % sobre 235971.56)")
    void casosConRecargos() {
        long conRecargos = 0;
        for (JsonNode caso : casos) {
            if (caso.has("costoReal")) {
                conRecargos++;
                assertThat(recargos(caso).aplicar(bd(caso, "costo"))).as(caso.get("nombre").asText())
                        .isEqualTo(bd(caso, "costoReal"));
            }
        }
        assertThat(conRecargos).isGreaterThanOrEqualTo(6);
        assertThat(new RecargosDeCosto(List.of(new RecargoCosto("Embalaje y carga", new BigDecimal("4.44"))))
                .aplicar(new BigDecimal("235971.56"))).isEqualTo(new BigDecimal("246448.70"));
    }
}
