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
 * Los casos del costo de lista, el precio y los pasos del desglose viven en un JSON que leen este test y la spec de
 * Vitest (frontend/src/app/core/formato/cadena-de-precio.spec.ts): la pantalla calcula en vivo y tiene que dar
 * exactamente lo mismo que el backend.
 */
class CasosCompartidosCadenaTest {

    private static JsonNode casos;

    @BeforeAll
    static void leerJson() throws IOException {
        try (InputStream in = CasosCompartidosCadenaTest.class.getResourceAsStream("/catalogo/casos-cadena-precio.json")) {
            assertThat(in).as("catalogo/casos-cadena-precio.json en el classpath de test").isNotNull();
            casos = new ObjectMapper().readTree(in).get("casos");
        }
    }

    private static BigDecimal bd(JsonNode nodo) {
        return nodo == null || nodo.isNull() ? null : new BigDecimal(nodo.asText());
    }

    private static List<BigDecimal> lista(JsonNode nodo) {
        List<BigDecimal> valores = new ArrayList<>();
        for (JsonNode valor : nodo) {
            valores.add(bd(valor));
        }
        return valores;
    }

    /** Los recargos del costo (CU-13) de un caso; sin la clave, ninguno. */
    private static RecargosDeCosto recargos(JsonNode caso) {
        List<RecargoCosto> lista = new ArrayList<>();
        for (JsonNode recargo : caso.path("recargos")) {
            lista.add(new RecargoCosto(recargo.get("nombre").asText(), bd(recargo.get("porcentaje"))));
        }
        return new RecargosDeCosto(lista);
    }

    @Test
    @DisplayName("el JSON trae los siete casos de R-219, los de R-209 y los de R-212, con dolares incluidos")
    void hayCasos() {
        assertThat(casos.size()).isGreaterThanOrEqualTo(24);
        long enDolares = 0;
        for (JsonNode caso : casos) {
            if (caso.get("moneda").asText().equals("USD")) {
                enDolares++;
            }
        }
        assertThat(enDolares).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("hay casos de R-210 donde el costo guardado NO coincide con el de la lista")
    void hayCasosQueNoCoinciden() {
        long noCoinciden = 0;
        for (JsonNode caso : casos) {
            if (!caso.get("coincide").asBoolean()) {
                noCoinciden++;
                assertThat(bd(caso.get("costoGuardado"))).isNotEqualByComparingTo(bd(caso.get("costo")));
            }
        }
        assertThat(noCoinciden).isGreaterThanOrEqualTo(3);
    }

    @Test
    @DisplayName("cada caso da el costo, el precio y los importes de los pasos esperados")
    void cadaCaso() {
        for (JsonNode caso : casos) {
            String nombre = caso.get("nombre").asText();
            AlicuotaIva alicuota = AlicuotaIva.valueOf(caso.get("alicuota").asText());
            List<BigDecimal> bonificaciones = lista(caso.get("bonificaciones"));
            List<BigDecimal> ajustes = lista(caso.get("ajustes"));
            boolean usd = caso.get("moneda").asText().equals("USD");
            BigDecimal cotizacion = usd ? bd(caso.get("cotizacion")) : BigDecimal.ONE;
            Bonificaciones laBonificacion = new Bonificaciones(bonificaciones.get(0), bonificaciones.get(1), bonificaciones.get(2));
            Ajustes losAjustes = new Ajustes(ajustes.get(0), ajustes.get(1), ajustes.get(2));

            BigDecimal costo = CostoDeLista.costo(bd(caso.get("lista")), alicuota, caso.get("incluyeIva").asBoolean(),
                laBonificacion, losAjustes);
            // El costo guardado puede ser distinto del de la lista (a mano o por un masivo): el precio sale del guardado.
            BigDecimal costoGuardado = caso.has("costoGuardado") ? bd(caso.get("costoGuardado")) : costo;
            RecargosDeCosto recargos = recargos(caso);
            BigDecimal precio = PrecioCalculator.precioFinal(costoGuardado, recargos, bd(caso.get("margen")), alicuota,
                bd(caso.get("multiplo")), cotizacion, null);

            assertThat(costo).as(nombre + ": costo").isEqualTo(bd(caso.get("costo")));
            assertThat(precio).as(nombre + ": precio").isEqualTo(bd(caso.get("precio")));

            CadenaDePrecio cadena = CadenaDePrecio.de(new EntradaCadena(true, bd(caso.get("lista")),
                caso.get("incluyeIva").asBoolean(), alicuota, laBonificacion, losAjustes, costoGuardado,
                usd ? Moneda.USD : Moneda.ARS, usd ? cotizacion : null, false, bd(caso.get("margen")), null, null,
                bd(caso.get("multiplo")), precio), recargos);

            JsonNode esperados = caso.get("pasos");
            assertThat(cadena.pasos()).as(nombre + ": cantidad de pasos").hasSize(esperados.size());
            for (int i = 0; i < esperados.size(); i++) {
                assertThat(cadena.pasos().get(i).tipo().name()).as(nombre + ": tipo del paso " + i)
                    .isEqualTo(esperados.get(i).get("tipo").asText());
                assertThat(cadena.pasos().get(i).importe()).as(nombre + ": importe del paso " + i)
                    .isEqualTo(bd(esperados.get(i).get("importe")));
            }
            assertThat(cadena.costoDeLista()).as(nombre + ": costo de la lista").isEqualTo(costo);
            assertThat(cadena.coincide()).as(nombre + ": coincide").isEqualTo(caso.get("coincide").asBoolean());
        }
    }
}
