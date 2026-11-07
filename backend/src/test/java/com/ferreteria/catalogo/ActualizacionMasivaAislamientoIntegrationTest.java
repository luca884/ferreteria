package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Aislamiento de la actualizacion masiva con {@code importacionId} y {@code COSTO_MONTO} (regla 7, R-220, T-227):
 * una importacion de otra ferreteria o inexistente da 404, el filtro vacio y el filtro por importacion no tocan los
 * articulos de la otra ferreteria, el Vendedor recibe 403 y un {@code ferreteriaId} del cuerpo se ignora. Con
 * {@code CAMBIAR_RUBRO}, un rubro destino de otra ferreteria o inexistente da 404 y no cambia nada.
 * CUIT 30-78407001-1 (A) y 30-78407002-2 (B).
 */
class ActualizacionMasivaAislamientoIntegrationTest extends AjustesTestBase {

    private static final String VISTA_PREVIA = "/api/actualizacion-masiva/vista-previa";
    private static final String APLICAR = "/api/actualizacion-masiva";
    private static final String NO_ENCONTRADA = "Importación no encontrada";

    private Long a;
    private Long b;
    private String duenoA;
    private String vendedorA;
    private Long importacionA1;
    private Long importacionB9;
    private List<Long> deA;
    private List<Long> deB;

    @BeforeEach
    void sembrar() throws Exception {
        a = ferreteria("30-78407001-1", "Aislamiento masiva A");
        b = ferreteria("30-78407002-2", "Aislamiento masiva B");
        borrarDatosDe(a, b);
        duenoA = login(usuario(a, "dueno-t227a@test.local", "DUENO"));
        vendedorA = login(usuario(a, "vendedor-t227a@test.local", "VENDEDOR"));
        usuario(b, "dueno-t227b@test.local", "DUENO");
        Long proveedorA = proveedor(a, "Proveedor A");
        Long proveedorB = proveedor(b, "Proveedor B");
        importacionA1 = importacion(a, proveedorA, "i1.csv");
        importacionB9 = importacion(b, proveedorB, "i9.csv");
        deA = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            deA.add(tocado(manual(a, "A" + i, "100.00", "160.00"), importacionA1));
        }
        deB = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            deB.add(tocado(manual(b, "B" + i, "100.00", "160.00"), importacionB9));
        }
    }

    @AfterEach
    void limpiar() {
        borrarDatosDe(a, b);
    }

    // ---- helpers ----

    private Long importacion(Long ferreteriaId, Long proveedorId, String archivo) {
        Long usuarioId = usuarioId(ferreteriaId.equals(a) ? "dueno-t227a@test.local" : "dueno-t227b@test.local");
        jdbc.update("insert into importacion_lista (ferreteria_id, proveedor_id, usuario_id, archivo, suben, bajan, "
                + "nuevos, sin_cambio, no_estan, con_error, estado) values (?, ?, ?, ?, 0, 0, 0, 0, 0, 0, 'APLICADA')",
                ferreteriaId, proveedorId, usuarioId, archivo);
        return jdbc.queryForObject("select max(id) from importacion_lista where ferreteria_id = ? and archivo = ?",
                Long.class, ferreteriaId, archivo);
    }

    private Long tocado(Long articuloId, Long importacionId) {
        jdbc.update("update articulo set ultima_importacion_id = ? where id = ?", importacionId, articuloId);
        return articuloId;
    }

    private ObjectNode pedido(String modo, String valor, Long importacionId) {
        ObjectNode pedido = objectMapper.createObjectNode();
        pedido.put("modo", modo);
        pedido.put("valor", new BigDecimal(valor));
        if (importacionId != null) {
            pedido.put("importacionId", importacionId);
        }
        return pedido;
    }

    private List<Map<String, Object>> filasDe(List<Long> ids) {
        List<Map<String, Object>> filas = new ArrayList<>();
        ids.forEach(id -> filas.add(filaDe(id)));
        return filas;
    }

    private long historialDe(List<Long> ids) {
        return ids.stream().mapToLong(this::historialDelArticulo).sum();
    }

    /** Nada de B cambio: ni sus articulos ni su historial. */
    private void bIntactoSegun(List<Map<String, Object>> antes) {
        assertThat(filasDe(deB)).isEqualTo(antes);
        assertThat(historialDeLaFerreteria(b)).isZero();
    }

    // ---- importacion ajena o inexistente ----

    @Test
    @DisplayName("una importacion de B pedida por el Dueno de A: 404 en la vista previa y en aplicar, y nada cambia")
    void importacionAjenaDa404() throws Exception {
        List<Map<String, Object>> antesA = filasDe(deA);
        List<Map<String, Object>> antesB = filasDe(deB);
        ObjectNode pedido = pedido("COSTO_MONTO", "100", importacionB9);

        postJson(VISTA_PREVIA, duenoA, pedido.toString()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is(NO_ENCONTRADA)));
        postJson(APLICAR, duenoA, pedido.deepCopy().put("huella", "abc").toString()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is(NO_ENCONTRADA)));

        assertThat(filasDe(deA)).isEqualTo(antesA);
        bIntactoSegun(antesB);
        assertThat(historialDeLaFerreteria(a)).isZero();
    }

    @Test
    @DisplayName("con una importacion ajena y valor 0 gana el 404 al 400, en los dos endpoints")
    void elNotFoundGanaAlCero() throws Exception {
        postJson(VISTA_PREVIA, duenoA, pedido("COSTO_MONTO", "0", importacionB9).toString())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message", is(NO_ENCONTRADA)));
        postJson(APLICAR, duenoA, pedido("COSTO_MONTO", "0", importacionB9).put("huella", "abc").toString())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message", is(NO_ENCONTRADA)));
    }

    @Test
    @DisplayName("un importacionId inexistente (999999) da 404 en los dos endpoints")
    void importacionInexistenteDa404() throws Exception {
        postJson(VISTA_PREVIA, duenoA, pedido("COSTO_PORCENTAJE", "10", 999_999L).toString())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message", is(NO_ENCONTRADA)));
        postJson(APLICAR, duenoA, pedido("COSTO_PORCENTAJE", "10", 999_999L).put("huella", "abc").toString())
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message", is(NO_ENCONTRADA)));
    }

    // ---- rubro destino de CAMBIAR_RUBRO (CU-05, #14) ----

    @Test
    @DisplayName("cambiar el rubro a un rubro de B: 404 del rubro en la vista previa y en aplicar, y nada cambia")
    void rubroDestinoAjenoDa404() throws Exception {
        Long rubroB = rubro(b, "Rubro de B", "60");
        Long rubroA = rubro(a, "Rubro de A", "40");
        List<Map<String, Object>> antesA = filasDe(deA);
        List<Map<String, Object>> antesB = filasDe(deB);

        for (Long destino : new Long[] {rubroB, 999_999L}) {
            ObjectNode pedido = objectMapper.createObjectNode().put("modo", "CAMBIAR_RUBRO").put("rubroDestinoId", destino);
            postJson(VISTA_PREVIA, duenoA, pedido.toString()).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message", is("Rubro no encontrado")));
            postJson(APLICAR, duenoA, pedido.deepCopy().put("huella", "abc").toString()).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message", is("Rubro no encontrado")));
        }

        assertThat(filasDe(deA)).isEqualTo(antesA);
        bIntactoSegun(antesB);
        assertThat(historialDeLaFerreteria(a)).isZero();
        // Con un rubro propio la misma vista previa anda: el 404 es por el rubro ajeno, no por el modo.
        postJson(VISTA_PREVIA, duenoA, objectMapper.createObjectNode().put("modo", "CAMBIAR_RUBRO")
                .put("rubroDestinoId", rubroA).toString())
                .andExpect(status().isOk()).andExpect(jsonPath("$.conteos.total", is(3)));
    }

    // ---- alcance ----

    @Test
    @DisplayName("A tiene 3 tocados por I1 y B 5 por I9: el Dueno de A aplica +100 con I1, total 3 y los 5 de B intactos y sin historial")
    void soloSeTocaLoDeLaFerreteriaDelToken() throws Exception {
        List<Map<String, Object>> antesB = filasDe(deB);
        ObjectNode pedido = pedido("COSTO_MONTO", "100", importacionA1);
        JsonNode previa = objectMapper.readTree(postJson(VISTA_PREVIA, duenoA, pedido.toString())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(previa.at("/conteos/total").asInt()).isEqualTo(3);

        JsonNode respuesta = objectMapper.readTree(postJson(APLICAR, duenoA,
                pedido.deepCopy().put("huella", previa.get("huella").asText()).toString())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(respuesta.at("/conteos/total").asInt()).isEqualTo(3);
        for (Long id : deA) {
            assertThat(precioCosto(id)).isEqualByComparingTo("200.00");
        }
        bIntactoSegun(antesB);
        assertThat(historialDe(deA)).isEqualTo(3);
    }

    @Test
    @DisplayName("COSTO_MONTO +100 con el filtro vacio de A: los articulos de B no cambian ni tienen historial")
    void filtroVacioNoTocaALosDeB() throws Exception {
        List<Map<String, Object>> antesB = filasDe(deB);
        ObjectNode pedido = pedido("COSTO_MONTO", "100", null);
        JsonNode previa = objectMapper.readTree(postJson(VISTA_PREVIA, duenoA, pedido.toString())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(previa.at("/conteos/total").asInt()).isEqualTo(3);

        postJson(APLICAR, duenoA, pedido.deepCopy().put("huella", previa.get("huella").asText()).toString())
                .andExpect(status().isCreated());

        bIntactoSegun(antesB);
    }

    @Test
    @DisplayName("un ferreteriaId de B en el cuerpo se ignora: solo cuenta la ferreteria del token")
    void unFerreteriaIdDelCuerpoSeIgnora() throws Exception {
        List<Map<String, Object>> antesB = filasDe(deB);
        ObjectNode pedido = pedido("COSTO_MONTO", "100", importacionA1).put("ferreteriaId", b);
        JsonNode previa = objectMapper.readTree(postJson(VISTA_PREVIA, duenoA, pedido.toString())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(previa.at("/conteos/total").asInt()).isEqualTo(3);

        postJson(APLICAR, duenoA, pedido.deepCopy().put("huella", previa.get("huella").asText()).toString())
                .andExpect(status().isCreated());

        bIntactoSegun(antesB);
        assertThat(historialDe(deA)).isEqualTo(3);
    }

    // ---- permisos ----

    @Test
    @DisplayName("el Vendedor recibe 403 en los dos endpoints, tambien con una importacion ajena, y nada cambia")
    void elVendedorRecibe403() throws Exception {
        List<Map<String, Object>> antesA = filasDe(deA);
        List<Map<String, Object>> antesB = filasDe(deB);

        for (Long importacion : new Long[] {importacionA1, importacionB9, null}) {
            postJson(VISTA_PREVIA, vendedorA, pedido("COSTO_MONTO", "100", importacion).toString())
                    .andExpect(status().isForbidden());
            postJson(APLICAR, vendedorA, pedido("COSTO_MONTO", "100", importacion).put("huella", "abc").toString())
                    .andExpect(status().isForbidden());
        }

        assertThat(filasDe(deA)).isEqualTo(antesA);
        bIntactoSegun(antesB);
        assertThat(historialDeLaFerreteria(a)).isZero();
    }

    private BigDecimal precioCosto(Long id) {
        return jdbc.queryForObject("select costo from articulo where id = ?", BigDecimal.class, id);
    }
}
