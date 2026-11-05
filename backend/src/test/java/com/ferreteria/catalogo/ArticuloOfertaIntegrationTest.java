package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ferreteria.common.HoraArgentina;
import com.ferreteria.support.IntegrationTestBase;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Oferta del articulo (ofertas, S1, S3 y S4): {@code PUT} y {@code DELETE /api/articulos/{id}/oferta} solo para el
 * Dueño, la oferta en las respuestas del articulo y el filtro {@code enOferta}, con aislamiento entre ferreterias.
 */
class ArticuloOfertaIntegrationTest extends IntegrationTestBase {

    private static final String URL = "/api/articulos";
    private static final String TIPO_INVALIDO =
            "Elegí si la oferta es un precio fijo, un descuento en % o un descuento en $.";

    private String duenoA;
    private String vendedorA;
    private String duenoB;
    private LocalDate hoy;

    @BeforeEach
    void tokens() throws Exception {
        duenoA = login("dueno@tornillo.demo");
        vendedorA = login("vendedor@tornillo.demo");
        duenoB = login("dueno@tuerca.demo");
        hoy = HoraArgentina.hoy();
    }

    // ---- helpers ----

    private JsonNode cuerpo(ResultActions resultado) throws Exception {
        return objectMapper.readTree(resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** Un articulo con precio manual 1.000 (no depende del margen ni del multiplo de la ferreteria). */
    private long articulo(String token, String descripcion) throws Exception {
        ObjectNode pedido = objectMapper.createObjectNode()
                .put("codigoInterno", unico("OF"))
                .put("descripcion", descripcion)
                .put("unidad", "UNIDAD")
                .put("costo", new BigDecimal("100"))
                .put("margenPorcentaje", new BigDecimal("30"))
                .put("precioManual", true)
                .put("precioFinal", new BigDecimal("1000"));
        return cuerpo(postJson(URL, token, pedido.toString()).andExpect(status().isCreated())).get("id").asLong();
    }

    private static ObjectNode oferta(com.fasterxml.jackson.databind.ObjectMapper mapper, String tipo, String valor,
            LocalDate hasta) {
        ObjectNode o = mapper.createObjectNode();
        if (tipo != null) {
            o.put("tipo", tipo);
        }
        if (valor != null) {
            o.put("valor", new BigDecimal(valor));
        }
        if (hasta != null) {
            o.put("hasta", hasta.toString());
        }
        return o;
    }

    private ResultActions ponerOferta(String token, long id, String tipo, String valor, LocalDate hasta)
            throws Exception {
        return putJson(URL + "/" + id + "/oferta", token,
                conLaVersionVigente(id, oferta(objectMapper, tipo, valor, hasta).toString()));
    }

    private Map<String, Object> columnasDeOferta(long id) {
        return jdbc.queryForMap("select oferta_tipo, oferta_valor, oferta_hasta, precio_final from articulo where id = ?",
                id);
    }

    private List<Long> idsEnOferta(String token) throws Exception {
        JsonNode pagina = getJson(URL + "?enOferta=true&size=100", token);
        List<Long> ids = new ArrayList<>();
        pagina.get("items").forEach(a -> ids.add(a.get("id").asLong()));
        return ids;
    }

    // ---- poner y sacar ----

    @Test
    @DisplayName("precio fijo: la respuesta trae la oferta vigente con su precio y el precio normal no cambia")
    void precioFijo() throws Exception {
        long id = articulo(duenoA, "Oferta fija");
        long versionAntes = jdbc.queryForObject("select version from articulo where id = ?", Long.class, id);

        JsonNode r = cuerpo(ponerOferta(duenoA, id, "PRECIO_FIJO", "800", null).andExpect(status().isOk()));

        assertThat(r.get("precioFinal").decimalValue()).isEqualByComparingTo("1000");
        assertThat(r.get("oferta").get("tipo").asText()).isEqualTo("PRECIO_FIJO");
        assertThat(r.get("oferta").get("valor").decimalValue()).isEqualByComparingTo("800");
        assertThat(r.get("oferta").get("hasta").isNull()).isTrue();
        assertThat(r.get("oferta").get("vigente").asBoolean()).isTrue();
        assertThat(r.get("oferta").get("precioOferta").decimalValue()).isEqualByComparingTo("800");
        assertThat(r.get("version").asLong()).isGreaterThan(versionAntes);
        Map<String, Object> fila = columnasDeOferta(id);
        assertThat(fila.get("oferta_tipo")).isEqualTo("PRECIO_FIJO");
        assertThat((BigDecimal) fila.get("oferta_valor")).isEqualByComparingTo("800");
        assertThat((BigDecimal) fila.get("precio_final")).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("descuento en % hasta hoy: precio de oferta 900 y la fecha de fin en la respuesta")
    void porcentajeHastaHoy() throws Exception {
        long id = articulo(duenoA, "Oferta porcentaje");

        JsonNode r = cuerpo(ponerOferta(duenoA, id, "DESCUENTO_PORCENTAJE", "10", hoy).andExpect(status().isOk()));

        assertThat(r.get("oferta").get("hasta").asText()).isEqualTo(hoy.toString());
        assertThat(r.get("oferta").get("precioOferta").decimalValue()).isEqualByComparingTo("900");
    }

    @Test
    @DisplayName("sacar la oferta deja las columnas en NULL y la respuesta sin oferta")
    void sacar() throws Exception {
        long id = articulo(duenoA, "Oferta a sacar");
        ponerOferta(duenoA, id, "DESCUENTO_MONTO", "150", null).andExpect(status().isOk());

        JsonNode r = cuerpo(delete(URL + "/" + id + "/oferta", duenoA).andExpect(status().isOk()));

        assertThat(r.get("oferta").isNull()).isTrue();
        Map<String, Object> fila = columnasDeOferta(id);
        assertThat(fila.get("oferta_tipo")).isNull();
        assertThat(fila.get("oferta_valor")).isNull();
        assertThat(fila.get("oferta_hasta")).isNull();
    }

    @Test
    @DisplayName("una oferta vencida vuelve en la respuesta como no vigente y sin precio de oferta")
    void vencida() throws Exception {
        long id = articulo(duenoA, "Oferta vencida");
        ponerOferta(duenoA, id, "PRECIO_FIJO", "800", hoy).andExpect(status().isOk());
        jdbc.update("update articulo set oferta_hasta = ? where id = ?", hoy.minusDays(1), id);

        JsonNode r = getJson(URL + "/" + id, duenoA);

        assertThat(r.get("oferta").get("vigente").asBoolean()).isFalse();
        assertThat(r.get("oferta").get("precioOferta").isNull()).isTrue();
    }

    // ---- validaciones ----

    @Test
    @DisplayName("400 en tipo, valor y hasta con los mensajes de la oferta")
    void validaciones() throws Exception {
        long id = articulo(duenoA, "Oferta invalida");

        ponerOferta(duenoA, id, null, "800", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.tipo").value(TIPO_INVALIDO));
        ponerOferta(duenoA, id, "REGALO", "800", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.tipo").value(TIPO_INVALIDO));
        ponerOferta(duenoA, id, "PRECIO_FIJO", null, null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.valor").value("El valor tiene que ser mayor a 0."));
        ponerOferta(duenoA, id, "PRECIO_FIJO", "1000", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.valor").value("El precio de oferta tiene que ser menor al precio normal."));
        ponerOferta(duenoA, id, "DESCUENTO_PORCENTAJE", "100", null).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.valor").value("El descuento va de 0,01 % a 99,99 %."));
        ponerOferta(duenoA, id, "PRECIO_FIJO", "800", hoy.minusDays(1)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.hasta").value("La fecha de fin no puede ser anterior a hoy."));
        assertThat(columnasDeOferta(id).get("oferta_tipo")).isNull();
    }

    @Test
    @DisplayName("un articulo dado de baja no se pone en oferta (422), pero se le puede sacar la que tenia")
    void dadoDeBaja() throws Exception {
        long id = articulo(duenoA, "Oferta de baja");
        ponerOferta(duenoA, id, "PRECIO_FIJO", "900", null).andExpect(status().isOk());
        jdbc.update("update articulo set activo = false where id = ?", id);

        ponerOferta(duenoA, id, "PRECIO_FIJO", "800", null).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message")
                        .value("\"Oferta de baja\" está dado de baja. Reactivalo para ponerle una oferta."));
        assertThat((BigDecimal) columnasDeOferta(id).get("oferta_valor")).isEqualByComparingTo("900");

        delete(URL + "/" + id + "/oferta", duenoA).andExpect(status().isOk());
        assertThat(columnasDeOferta(id).get("oferta_tipo")).isNull();
    }

    @Test
    @DisplayName("sin la version es 400; con una version vieja, 409")
    void version() throws Exception {
        long id = articulo(duenoA, "Oferta version");
        String sinVersion = oferta(objectMapper, "PRECIO_FIJO", "800", null).toString();
        String vieja = oferta(objectMapper, "PRECIO_FIJO", "800", null).put("version", -1).toString();

        putJson(URL + "/" + id + "/oferta", duenoA, sinVersion).andExpect(status().isBadRequest());
        putJson(URL + "/" + id + "/oferta", duenoA, vieja).andExpect(status().isConflict());
    }

    @Test
    @DisplayName("el Vendedor no pone ni saca ofertas, pero la ve en el articulo")
    void vendedor() throws Exception {
        long id = articulo(duenoA, "Oferta vendedor");

        ponerOferta(vendedorA, id, "PRECIO_FIJO", "800", null).andExpect(status().isForbidden());
        ponerOferta(duenoA, id, "PRECIO_FIJO", "800", null).andExpect(status().isOk());
        delete(URL + "/" + id + "/oferta", vendedorA).andExpect(status().isForbidden());

        JsonNode visto = getJson(URL + "/" + id, vendedorA);
        assertThat(visto.has("costo")).isFalse();
        assertThat(visto.get("oferta").get("precioOferta").decimalValue()).isEqualByComparingTo("800");
        assertThat(idsEnOferta(vendedorA)).contains(id);
    }

    // ---- filtro ----

    @Test
    @DisplayName("enOferta trae solo las vigentes que bajan el precio; sin el filtro, todas")
    void filtro() throws Exception {
        long vigente = articulo(duenoA, "Filtro vigente");
        long sinOferta = articulo(duenoA, "Filtro sin oferta");
        long vencida = articulo(duenoA, "Filtro vencida");
        long porEncima = articulo(duenoA, "Filtro por encima");
        long porcentaje = articulo(duenoA, "Filtro porcentaje");
        long porcentajeQueNoBaja = articulo(duenoA, "Filtro porcentaje que no baja");
        ponerOferta(duenoA, porcentajeQueNoBaja, "DESCUENTO_PORCENTAJE", "10", null).andExpect(status().isOk());
        // 0,01 x 0,90 = 0,009 -> al centavo hacia arriba 0,01: no baja el precio (Oferta.precioSobre da vacio).
        jdbc.update("update articulo set precio_final = 0.01 where id = ?", porcentajeQueNoBaja);
        ponerOferta(duenoA, vigente, "PRECIO_FIJO", "800", hoy).andExpect(status().isOk());
        ponerOferta(duenoA, vencida, "PRECIO_FIJO", "800", hoy).andExpect(status().isOk());
        ponerOferta(duenoA, porEncima, "DESCUENTO_MONTO", "100", null).andExpect(status().isOk());
        ponerOferta(duenoA, porcentaje, "DESCUENTO_PORCENTAJE", "5", null).andExpect(status().isOk());
        jdbc.update("update articulo set oferta_hasta = ? where id = ?", hoy.minusDays(1), vencida);
        // El precio normal bajo despues: el descuento de $ 100 ya no entra en $ 100.
        jdbc.update("update articulo set precio_final = 100 where id = ?", porEncima);

        List<Long> enOferta = idsEnOferta(duenoA);

        assertThat(enOferta).contains(vigente, porcentaje)
                .doesNotContain(sinOferta, vencida, porEncima, porcentajeQueNoBaja);
        assertThat(getJson(URL + "/" + porcentajeQueNoBaja, duenoA).get("oferta").get("vigente").asBoolean()).isFalse();
        JsonNode todos = getJson(URL + "?q=Filtro&size=100", duenoA);
        assertThat(todos.get("totalElements").asLong()).isGreaterThanOrEqualTo(6);
    }

    // ---- aislamiento ----

    @Test
    @DisplayName("otra ferreteria: poner o sacar la oferta da 404 y su filtro no trae las ofertas de esta")
    void aislamiento() throws Exception {
        long id = articulo(duenoA, "Oferta aislada");
        ponerOferta(duenoA, id, "PRECIO_FIJO", "800", null).andExpect(status().isOk());
        String json = oferta(objectMapper, "PRECIO_FIJO", "700", null)
                .put("version", jdbc.queryForObject("select version from articulo where id = ?", Long.class, id))
                .toString();

        putJson(URL + "/" + id + "/oferta", duenoB, json).andExpect(status().isNotFound());
        delete(URL + "/" + id + "/oferta", duenoB).andExpect(status().isNotFound());

        assertThat(idsEnOferta(duenoB)).doesNotContain(id);
        assertThat((BigDecimal) columnasDeOferta(id).get("oferta_valor")).isEqualByComparingTo("800");
    }
}
