package com.ferreteria.ventas.model;

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
 * R-043: los casos canonicos de totales viven en un JSON que leen este test y la spec de Vitest
 * (frontend/src/app/features/mostrador/centavos.spec.ts). Java valida total, neto e IVA; el frontend
 * solo el total. Los importes van como string para no pasar por double.
 */
class CasosCompartidosTotalesTest {

    private static JsonNode casos;

    @BeforeAll
    static void leerJson() throws IOException {
        try (InputStream in = CasosCompartidosTotalesTest.class.getResourceAsStream("/ventas/casos-totales.json")) {
            assertThat(in).as("ventas/casos-totales.json en el classpath de test").isNotNull();
            casos = new ObjectMapper().readTree(in);
        }
    }

    private static BigDecimal bd(JsonNode nodo, String campo) {
        assertThat(nodo.get(campo)).as("campo %s de %s", campo, nodo).isNotNull();
        assertThat(nodo.get(campo).isTextual()).as("%s va como string", campo).isTrue();
        return new BigDecimal(nodo.get(campo).asText());
    }

    /** Los descuentos son opcionales en el JSON: sin ellos valen 0 %. */
    private static BigDecimal opcional(JsonNode nodo, String campo) {
        return nodo.has(campo) ? bd(nodo, campo) : BigDecimal.ZERO;
    }

    private static RenglonVenta renglon(JsonNode caso, BigDecimal descuentoGlobal) {
        return RenglonVenta.de(bd(caso, "precio"), bd(caso, "cantidad"), bd(caso, "alicuota"),
                opcional(caso, "descuento"), descuentoGlobal);
    }

    @Test
    @DisplayName("el JSON trae los 5 casos de renglon del diseno, C1, C3, el renglon de C2, los de descuentos y los del descuento de la forma")
    void elJsonTieneLosCasos() {
        assertThat(casos.get("renglones").size()).isEqualTo(11);
        assertThat(casos.get("tickets").size()).isEqualTo(13);
    }

    @Test
    @DisplayName("cada renglon del JSON da el total, el neto y el IVA esperados")
    void cadaRenglon() {
        for (JsonNode caso : casos.get("renglones")) {
            RenglonVenta r = renglon(caso, BigDecimal.ZERO);

            assertThat(r.total()).as("total de %s", caso.get("nombre").asText()).isEqualByComparingTo(bd(caso, "total"));
            assertThat(r.neto()).as("neto de %s", caso.get("nombre").asText()).isEqualByComparingTo(bd(caso, "neto"));
            assertThat(r.iva()).as("iva de %s", caso.get("nombre").asText()).isEqualByComparingTo(bd(caso, "iva"));
        }
    }

    @Test
    @DisplayName("cada ticket del JSON (incluido el de los tres primeros, 2485,50) da los totales esperados")
    void cadaTicket() {
        for (JsonNode ticket : casos.get("tickets")) {
            List<RenglonVenta> renglones = new ArrayList<>();
            for (JsonNode nombre : ticket.get("renglones")) {
                renglones.add(renglon(porNombre(nombre.asText()), opcional(ticket, "descuentoGlobal")));
            }

            String id = ticket.get("nombre").asText();
            if (ticket.has("recargo")) {
                renglones = RecargoVenta.repartir(renglones, bd(ticket, "recargo"));
                for (int i = 0; i < renglones.size(); i++) {
                    assertThat(renglones.get(i).recargo()).as("recargo del renglon %d de %s", i, id)
                            .isEqualByComparingTo(new BigDecimal(ticket.get("recargoPorRenglon").get(i).asText()));
                }
            }

            TotalesVenta totales = TotalesVenta.de(renglones);

            assertThat(renglones).as("renglones de %s", id).isNotEmpty();
            assertThat(totales.total()).as("total de %s", id).isEqualByComparingTo(bd(ticket, "total"));
            assertThat(totales.neto()).as("neto de %s", id).isEqualByComparingTo(bd(ticket, "neto"));
            assertThat(totales.iva()).as("iva de %s", id).isEqualByComparingTo(bd(ticket, "iva"));
        }
    }

    @Test
    @DisplayName("el ticket de los tres primeros renglones suma 2485,50")
    void ticketDeLosTresPrimeros() {
        JsonNode ticket = null;
        for (JsonNode candidato : casos.get("tickets")) {
            if (candidato.get("nombre").asText().equals("tres-primeros")) {
                ticket = candidato;
            }
        }

        assertThat(ticket).isNotNull();
        assertThat(bd(ticket, "total")).isEqualByComparingTo("2485.50");
    }

    private static JsonNode porNombre(String nombre) {
        for (JsonNode caso : casos.get("renglones")) {
            if (caso.get("nombre").asText().equals(nombre)) {
                return caso;
            }
        }
        throw new AssertionError("No hay un renglon llamado " + nombre + " en casos-totales.json");
    }
}
