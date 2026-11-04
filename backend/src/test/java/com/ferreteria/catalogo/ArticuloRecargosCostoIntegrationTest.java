package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ferreteria.catalogo.model.AlicuotaIva;
import com.ferreteria.catalogo.model.PrecioCalculator;
import com.ferreteria.catalogo.model.RecargoCosto;
import com.ferreteria.catalogo.model.RecargosDeCosto;
import com.ferreteria.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Recargos del costo por la API del articulo (CU-13, T5): alta, edicion, respuesta, desglose, validacion, el Vendedor
 * que no los ve y el aislamiento entre ferreterias. Ferreterias demo Tornillo (A) y Tuerca (B).
 */
class ArticuloRecargosCostoIntegrationTest extends IntegrationTestBase {

    private static final String URL = "/api/articulos";

    private String duenoA;
    private String vendedorA;
    private String duenoB;

    @BeforeEach
    void tokens() throws Exception {
        duenoA = login("dueno@tornillo.demo");
        vendedorA = login("vendedor@tornillo.demo");
        duenoB = login("dueno@tuerca.demo");
    }

    // ---- helpers ----

    /** Costo 1000, margen propio 30, IVA 21, sin rubro. */
    private ObjectNode pedido() {
        return objectMapper.createObjectNode()
                .put("codigoInterno", unico("RC"))
                .put("descripcion", "Articulo con recargos")
                .put("unidad", "UNIDAD")
                .put("costo", new BigDecimal("1000"))
                .put("margenPorcentaje", new BigDecimal("30"));
    }

    private static ObjectNode recargo(ObjectNode pedido, String nombre, String porcentaje) {
        ArrayNode lista = pedido.has("recargosCosto") ? (ArrayNode) pedido.get("recargosCosto")
                : pedido.putArray("recargosCosto");
        lista.addObject().put("nombre", nombre).put("porcentaje", new BigDecimal(porcentaje));
        return pedido;
    }

    private JsonNode cuerpo(ResultActions resultado) throws Exception {
        return objectMapper.readTree(resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode crear(ObjectNode pedido) throws Exception {
        return cuerpo(postJson(URL, duenoA, pedido.toString()).andExpect(status().isCreated()));
    }

    private ResultActions editar(String token, long id, ObjectNode pedido) throws Exception {
        return putJson(URL + "/" + id, token, conLaVersionVigente(id, pedido.toString()));
    }

    /** El precio con costo 1000, margen 30, IVA 21 y el multiplo de la ferreteria A, con esos recargos. */
    private BigDecimal calculado(RecargoCosto... recargos) {
        BigDecimal multiplo = jdbc.queryForObject(
                "select f.redondeo_multiplo from ferreteria f join usuario u on u.ferreteria_id = f.id "
                        + "where u.email = 'dueno@tornillo.demo'", BigDecimal.class);
        return PrecioCalculator.precioFinal(new BigDecimal("1000"), new RecargosDeCosto(List.of(recargos)),
                new BigDecimal("30"), AlicuotaIva.IVA_21, multiplo, BigDecimal.ONE, null);
    }

    // ---- alta y respuesta ----

    @Test
    @DisplayName("el alta con recargos los devuelve en orden, calcula el precio con el costo real y el desglose trae sus pasos")
    void altaConRecargos() throws Exception {
        JsonNode creado = crear(recargo(recargo(pedido(), "Embalaje y carga", "4.44"), "Flete", "-1.5"));

        assertThat(creado.get("recargosCosto")).hasSize(2);
        assertThat(creado.at("/recargosCosto/0/nombre").asText()).isEqualTo("Embalaje y carga");
        assertThat(creado.at("/recargosCosto/1/porcentaje").decimalValue()).isEqualByComparingTo("-1.5");
        assertThat(creado.get("precioFinal").decimalValue()).isEqualByComparingTo(calculado(
                new RecargoCosto("Embalaje y carga", new BigDecimal("4.44")), new RecargoCosto("Flete", new BigDecimal("-1.5"))));

        JsonNode desglose = getJson(URL + "/" + creado.get("id").asLong() + "/desglose", duenoA);

        assertThat(desglose.get("recargosCosto")).hasSize(2);
        assertThat(tiposDe(desglose.get("pasos"))).containsSubsequence("COSTO", "RECARGO", "RECARGO", "COSTO_REAL", "MARGEN");
        assertThat(desglose.at("/pasos/1/importe").decimalValue()).isEqualByComparingTo("44.40");
        assertThat(desglose.at("/pasos/3/importe").decimalValue()).isEqualByComparingTo("1029.40");
        assertThat(desglose.get("precioFinal").decimalValue()).isEqualByComparingTo(creado.get("precioFinal").decimalValue());
    }

    private static List<String> tiposDe(JsonNode pasos) {
        return pasos.findValuesAsText("tipo");
    }

    // ---- edicion ----

    @Test
    @DisplayName("un PUT sin recargosCosto conserva los de hoy; con una lista los reemplaza y con una vacia los borra")
    void edicion() throws Exception {
        long id = crear(recargo(pedido(), "Embalaje y carga", "4.44")).get("id").asLong();

        JsonNode sinClave = cuerpo(editar(duenoA, id, pedido().put("costo", new BigDecimal("1000"))).andExpect(status().isOk()));
        assertThat(sinClave.get("recargosCosto")).hasSize(1);

        JsonNode reemplazados = cuerpo(editar(duenoA, id, recargo(pedido(), "Flete", "2")).andExpect(status().isOk()));
        assertThat(reemplazados.at("/recargosCosto/0/nombre").asText()).isEqualTo("Flete");
        assertThat(reemplazados.get("precioFinal").decimalValue())
                .isEqualByComparingTo(calculado(new RecargoCosto("Flete", new BigDecimal("2"))));

        ObjectNode sinRecargos = pedido();
        sinRecargos.putArray("recargosCosto");
        JsonNode borrados = cuerpo(editar(duenoA, id, sinRecargos).andExpect(status().isOk()));
        assertThat(borrados.get("recargosCosto")).isEmpty();
        assertThat(borrados.get("precioFinal").decimalValue()).isEqualByComparingTo(calculado());
    }

    // ---- validacion ----

    @Test
    @DisplayName("un recargo invalido (en 0, sin nombre, mas de 5) da 400 en recargosCosto y no crea nada")
    void invalidos() throws Exception {
        postJson(URL, duenoA, recargo(pedido(), "Flete", "0").toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.recargosCosto")
                        .value("El porcentaje del recargo debe estar entre -99,99 y 99,99, y no puede ser 0."));
        postJson(URL, duenoA, recargo(pedido(), " ", "3").toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.recargosCosto").value("El nombre del recargo es obligatorio."));
        ObjectNode seis = pedido();
        for (int i = 0; i < 6; i++) {
            recargo(seis, "R" + i, "1");
        }
        postJson(URL, duenoA, seis.toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.recargosCosto").value("Un artículo tiene hasta 5 recargos del costo."));
    }

    // ---- permiso y aislamiento ----

    @Test
    @DisplayName("el Vendedor no ve los recargos en la lectura ni en la lista, y no edita (403)")
    void vendedor() throws Exception {
        long id = crear(recargo(pedido(), "Embalaje y carga", "4.44")).get("id").asLong();

        assertThat(getJson(URL + "/" + id, vendedorA).has("recargosCosto")).isFalse();
        editar(vendedorA, id, recargo(pedido(), "Flete", "9")).andExpect(status().isForbidden());
        getResult(URL + "/" + id + "/desglose", vendedorA).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("otra ferreteria no ve ni edita los recargos ni el desglose: 404 y no cambia nada")
    void otraFerreteria() throws Exception {
        long id = crear(recargo(pedido(), "Embalaje y carga", "4.44")).get("id").asLong();

        getResult(URL + "/" + id + "/desglose", duenoB).andExpect(status().isNotFound());
        editar(duenoB, id, recargo(pedido(), "Flete", "9")).andExpect(status().isNotFound());

        assertThat(jdbc.queryForObject("select recargos_costo from articulo where id = ?", String.class, id))
                .contains("Embalaje y carga").doesNotContain("Flete");
    }
}
