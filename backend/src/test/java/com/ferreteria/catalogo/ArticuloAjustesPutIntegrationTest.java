package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * El PUT del articulo no toca los ajustes ni la base de lista (T-222, R-216 y R-213): ni los borra por omision ni
 * los acepta si vienen en el cuerpo; el alta tampoco los admite; la respuesta trae la base; y un articulo que pierde
 * el proveedor es "sin lista" (decision 46). CUIT 30-78405001-1; casa propia de usuarios (t222).
 */
class ArticuloAjustesPutIntegrationTest extends AjustesTestBase {

    private static final String URL = "/api/articulos";
    private static final String SIN_LISTA =
            "Este artículo no tiene lista de proveedor. Los ajustes se usan solo con artículos que vienen de una lista.";

    private Long a;
    private Long proveedorA;
    private String duenoA;

    @BeforeEach
    void sembrar() throws Exception {
        a = ferreteria("30-78405001-1", "Put ajustes A");
        borrarDatosDe(a);
        proveedorA = proveedor(a, "Proveedor A");
        duenoA = login(usuario(a, "dueno-t222a@test.local", "DUENO"));
    }

    @AfterEach
    void limpiar() {
        borrarDatosDe(a);
    }

    // ---- helpers ----

    /** Lista 1.000, bonificacion 10, ajustes -5 y +3: costo 880,65 y precio 1.390. */
    private Long conDosAjustes(String codigo) {
        return importado(a, codigo, proveedorA, "1000.00", false, valores("10", null, null), valores("-5", "3", null),
                "880.65", "1390.00");
    }

    /** Un PUT valido para ese articulo con el costo y el proveedor dados. */
    private ObjectNode put(String codigo, String costo, Long proveedorId) {
        ObjectNode p = objectMapper.createObjectNode()
                .put("codigoInterno", codigo)
                .put("descripcion", "Articulo " + codigo)
                .put("unidad", "UNIDAD")
                .put("costo", new BigDecimal(costo))
                .put("margenPorcentaje", new BigDecimal("30.00"));
        if (proveedorId != null) {
            p.put("proveedorId", proveedorId);
        }
        return p;
    }

    private ResultActions enviar(Long id, ObjectNode pedido) throws Exception {
        return putJson(URL + "/" + id, duenoA, conLaVersionVigente(id, objectMapper.writeValueAsString(pedido)));
    }

    private JsonNode leer(ResultActions resultado) throws Exception {
        return objectMapper.readTree(resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private ResultActions editarAjustes(Long id, String json) throws Exception {
        return putJson(URL + "/" + id + "/ajustes", duenoA, conLaVersionVigente(id, json));
    }

    // ---- R-216 ----

    @Test
    @DisplayName("un PUT que edita solo la descripcion deja los ajustes y el costo")
    void editarLaDescripcion() throws Exception {
        Long id = conDosAjustes("PA-DESC");
        ObjectNode p = put("PA-DESC", "880.65", proveedorA).put("descripcion", "Otra descripcion");

        JsonNode r = leer(enviar(id, p).andExpect(status().isOk()));

        assertThat(r.get("descripcion").asText()).isEqualTo("Otra descripcion");
        assertThat(r.get("ajuste1").decimalValue()).isEqualByComparingTo("-5");
        assertThat(r.get("ajuste2").decimalValue()).isEqualByComparingTo("3");
        assertThat(r.get("costo").decimalValue()).isEqualByComparingTo("880.65");
        assertThat(r.get("precioFinal").decimalValue()).isEqualByComparingTo("1390");
    }

    @Test
    @DisplayName("un PUT con ajuste1 = 10 en el cuerpo lo ignora")
    void losAjustesDelCuerpoSeIgnoran() throws Exception {
        Long id = conDosAjustes("PA-IGN");
        ObjectNode p = put("PA-IGN", "880.65", proveedorA).put("ajuste1", 10).put("ajuste3", 7);

        JsonNode r = leer(enviar(id, p).andExpect(status().isOk()));

        assertThat(r.get("ajuste1").decimalValue()).isEqualByComparingTo("-5");
        assertThat(r.get("ajuste2").decimalValue()).isEqualByComparingTo("3");
        assertThat(r.get("ajuste3").isNull()).isTrue();
        assertThat(((BigDecimal) filaDe(id).get("ajuste1"))).isEqualByComparingTo("-5");
    }

    @Test
    @DisplayName("un PUT que cambia el rubro recalcula el precio con el margen nuevo sobre el costo guardado y deja los ajustes")
    void cambiarElRubro() throws Exception {
        Long id = conDosAjustes("PA-RUBRO");
        Long rubro = rubro(a, "Rubro 40", "40.00");
        ObjectNode p = put("PA-RUBRO", "880.65", proveedorA).put("rubroId", rubro);
        p.remove("margenPorcentaje");

        JsonNode r = leer(enviar(id, p).andExpect(status().isOk()));

        // 880,65 x 1,40 x 1,21 = 1.491,8 -> 1.500
        assertThat(r.get("precioFinal").decimalValue()).isEqualByComparingTo("1500");
        assertThat(r.get("costo").decimalValue()).isEqualByComparingTo("880.65");
        assertThat(r.get("ajuste1").decimalValue()).isEqualByComparingTo("-5");
        assertThat(r.get("ajuste2").decimalValue()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("un PUT que cambia el costo a mano a 900,00 deja los ajustes, y el desglose ya no coincide")
    void cambiarElCostoAMano() throws Exception {
        Long id = conDosAjustes("PA-COSTO");

        JsonNode r = leer(enviar(id, put("PA-COSTO", "900.00", proveedorA)).andExpect(status().isOk()));

        assertThat(r.get("costo").decimalValue()).isEqualByComparingTo("900.00");
        assertThat(r.get("ajuste1").decimalValue()).isEqualByComparingTo("-5");
        JsonNode desglose = getJson(URL + "/" + id + "/desglose", duenoA);
        assertThat(desglose.get("coincide").asBoolean()).isFalse();
        assertThat(desglose.get("costoDeLista").decimalValue()).isEqualByComparingTo("880.65");
    }

    @Test
    @DisplayName("un PUT no toca la base guardada (bonificaciones, IVA de la lista ni precio de lista)")
    void laBaseNoSeToca() throws Exception {
        Long id = conDosAjustes("PA-BASE");
        var antes = filaDe(id);

        enviar(id, put("PA-BASE", "880.65", proveedorA).put("descripcion", "Cambiada")).andExpect(status().isOk());

        var despues = filaDe(id);
        for (String columna : new String[] {"precio_lista_proveedor", "bonificacion1", "bonificacion2",
                "bonificacion3", "lista_incluye_iva"}) {
            assertThat(despues.get(columna)).as(columna).isEqualTo(antes.get(columna));
        }
    }

    @Test
    @DisplayName("un POST de alta con ajustes los ignora y el articulo queda sin ajustes")
    void elAltaIgnoraLosAjustes() throws Exception {
        ObjectNode p = put("PA-ALTA", "100.00", null).put("ajuste1", 10).put("ajuste2", -5);

        JsonNode creado = leer(postJson(URL, duenoA, objectMapper.writeValueAsString(p)).andExpect(status().isCreated()));

        assertThat(creado.get("ajuste1").isNull()).isTrue();
        assertThat(creado.get("ajuste2").isNull()).isTrue();
        assertThat(creado.get("listaIncluyeIva").isNull()).isTrue();
        assertThat(filaDe(creado.get("id").asLong()).get("ajuste1")).isNull();
    }

    @Test
    @DisplayName("GET /{id} trae ajustes, bonificaciones y listaIncluyeIva; el listado paginado sigue funcionando")
    void lecturaConLaBase() throws Exception {
        Long id = conDosAjustes("PA-GET");
        jdbc.update("update articulo set lista_incluye_iva = true where id = ?", id);

        JsonNode r = getJson(URL + "/" + id, duenoA);

        assertThat(r.get("ajuste1").decimalValue()).isEqualByComparingTo("-5");
        assertThat(r.get("ajuste2").decimalValue()).isEqualByComparingTo("3");
        assertThat(r.get("ajuste3").isNull()).isTrue();
        assertThat(r.get("bonificacion1").decimalValue()).isEqualByComparingTo("10");
        assertThat(r.get("bonificacion2").isNull()).isTrue();
        assertThat(r.get("listaIncluyeIva").asBoolean()).isTrue();
        JsonNode pagina = getJson(URL + "?q=PA-GET", duenoA);
        assertThat(pagina.get("items")).hasSize(1);
        assertThat(pagina.get("items").get(0).get("id").asLong()).isEqualTo(id);
    }

    // ---- decision 46: articulo sin proveedor ----

    @Test
    @DisplayName("al sacarle el proveedor con un PUT: editar ajustes da 409 con el texto unico y el desglose no tiene pasos de lista")
    void sinProveedorEsSinLista() throws Exception {
        Long id = conDosAjustes("PA-SINP");
        enviar(id, put("PA-SINP", "880.65", null)).andExpect(status().isOk());

        editarAjustes(id, "{\"ajuste1\":-5}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is(SIN_LISTA)));
        JsonNode desglose = getJson(URL + "/" + id + "/desglose", duenoA);
        assertThat(desglose.get("tieneLista").asBoolean()).isFalse();
        assertThat(desglose.get("motivoSinLista").asText()).isEqualTo(SIN_LISTA);
        desglose.get("pasos").forEach(paso -> assertThat(paso.get("tipo").asText())
                .isNotIn("LISTA", "SIN_IVA", "BONIFICACION", "AJUSTE"));
        // Los ajustes guardados se siguen viendo: solo se bloquea editarlos.
        assertThat(getJson(URL + "/" + id, duenoA).get("ajuste1").decimalValue()).isEqualByComparingTo("-5");
    }

    @Test
    @DisplayName("si se le vuelve a poner un proveedor y conserva la base guardada, la edicion se habilita")
    void conBaseSeHabilita() throws Exception {
        Long id = conDosAjustes("PA-VUELVE");
        enviar(id, put("PA-VUELVE", "880.65", null)).andExpect(status().isOk());
        enviar(id, put("PA-VUELVE", "880.65", proveedorA)).andExpect(status().isOk());

        JsonNode r = leer(editarAjustes(id, "{\"ajuste1\":-5}").andExpect(status().isOk()));

        assertThat(r.get("costo").decimalValue()).isEqualByComparingTo("855.00");
        assertThat(getJson(URL + "/" + id + "/desglose", duenoA).get("tieneLista").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("un articulo sin proveedor y sin base (anterior a V7) sigue en 409 aunque se le ponga un proveedor")
    void sinBaseSigueSinLista() throws Exception {
        Long id = importado(a, "PA-SINBASE", proveedorA, "1000.00", false, NADA, NADA, "900.00", "1420.00");
        jdbc.update("update articulo set proveedor_id = null, lista_incluye_iva = null where id = ?", id);
        enviar(id, put("PA-SINBASE", "900.00", proveedorA)).andExpect(status().isOk());

        editarAjustes(id, "{\"ajuste1\":-5}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is(SIN_LISTA)));
        assertThat(getJson(URL + "/" + id + "/desglose", duenoA).get("tieneLista").asBoolean()).isFalse();
    }
}
