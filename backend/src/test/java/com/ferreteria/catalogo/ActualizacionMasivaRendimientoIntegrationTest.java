package com.ferreteria.catalogo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ferreteria.catalogo.model.FiltroArticulos;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.support.ExplainDelFiltro;
import com.ferreteria.support.IntegrationTestBase;
import com.ferreteria.support.SqlCapturado;
import com.ferreteria.tenant.TenantContext;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Criterio de exito de CU-05 (R-019): vista previa + aplicar sobre 5.000 articulos en menos de 2 minutos.
 * Corre en una ferreteria PROPIA (creada por jdbc, con un Dueno que reusa la contrasena demo) para no
 * ensuciar a los tenants demo. El armado de los datos no se cronometra: se mide solo vista previa + aplicar,
 * pasando por el filtro de seguridad, el planificador y la transaccion real.
 */
class ActualizacionMasivaRendimientoIntegrationTest extends IntegrationTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(ActualizacionMasivaRendimientoIntegrationTest.class);

    private static final Duration LIMITE = Duration.ofSeconds(120);
    private static final int ARTICULOS = 5_000;

    @Autowired
    ArticuloRepository articuloRepository;

    private long contar(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void cincoMilArticulosConCostoMasDiezPorCientoSeVenYSeAplicanEnMenosDeDosMinutos() throws Exception {
        String cuit = "30-72000051-5";
        String email = "dueno@rendimiento-masivo.demo";
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) "
                + "values (?, ?, 'RESPONSABLE_INSCRIPTO')", "Rendimiento masivo " + cuit, cuit);
        long ferreteriaId = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        String hash = jdbc.queryForObject(
                "select password_hash from usuario where email = 'dueno@tornillo.demo'", String.class);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, ?, 'Dueno Rendimiento', 'DUENO')", ferreteriaId, email, hash);
        long usuarioId = jdbc.queryForObject("select id from usuario where email = ?", Long.class, email);
        jdbc.update("insert into proveedor (ferreteria_id, nombre) values (?, 'Proveedor grande')", ferreteriaId);
        long proveedorId = jdbc.queryForObject(
                "select id from proveedor where ferreteria_id = ?", Long.class, ferreteriaId);

        // 5.000 articulos del proveedor, costo 100.00 y precio 160.00 (margen heredado 30 % + IVA 21 %, multiplo 10).
        List<Object[]> filas = new ArrayList<>(ARTICULOS);
        for (int i = 1; i <= ARTICULOS; i++) {
            String codigo = String.format("M-%05d", i);
            filas.add(new Object[] {ferreteriaId, codigo, "Articulo " + codigo, proveedorId});
        }
        jdbc.batchUpdate("insert into articulo (ferreteria_id, codigo_interno, descripcion, proveedor_id, costo, "
                + "alicuota_iva, precio_final, unidad) values (?, ?, ?, ?, 100.00, 'IVA_21', 160.00, 'UNIDAD')", filas);
        assertThat(contar("select count(*) from articulo where ferreteria_id = ?", ferreteriaId)).isEqualTo(ARTICULOS);

        String token = login(email);
        ObjectNode pedido = objectMapper.createObjectNode()
                .put("modo", "COSTO_PORCENTAJE").put("valor", new BigDecimal("10")).put("proveedorId", proveedorId);

        long inicio = System.nanoTime();
        String cuerpoVistaPrevia = postJson("/api/actualizacion-masiva/vista-previa", token, pedido.toString())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long trasVistaPrevia = System.nanoTime();
        JsonNode vistaPrevia = objectMapper.readTree(cuerpoVistaPrevia);
        ObjectNode aplicar = pedido.deepCopy().put("huella", vistaPrevia.get("huella").asText());
        String cuerpoAplicada = postJson("/api/actualizacion-masiva", token, aplicar.toString())
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long fin = System.nanoTime();

        Duration total = Duration.ofNanos(fin - inicio);
        LOG.info("RENDIMIENTO CU-05 (5.000 articulos, COSTO_PORCENTAJE +10, los 5.000 cambian): "
                        + "vista previa {} ms, aplicar {} ms, total {} ms (limite {} ms)",
                Duration.ofNanos(trasVistaPrevia - inicio).toMillis(), Duration.ofNanos(fin - trasVistaPrevia).toMillis(),
                total.toMillis(), LIMITE.toMillis());

        JsonNode aplicada = objectMapper.readTree(cuerpoAplicada);
        assertThat(vistaPrevia.get("conteos").get("total").asInt()).isEqualTo(ARTICULOS);
        assertThat(vistaPrevia.get("conteos").get("modificados").asInt()).isEqualTo(ARTICULOS);
        assertThat(vistaPrevia.get("detalleRecortado").asBoolean()).isTrue();
        assertThat(aplicada.get("conteos").get("modificados").asInt()).isEqualTo(ARTICULOS);
        assertThat(aplicada.get("entradasDeHistorial").asInt()).isEqualTo(ARTICULOS);
        assertThat(contar("select count(*) from articulo where ferreteria_id = ? and costo = 110.00 "
                + "and precio_final = 180.00", ferreteriaId)).isEqualTo(ARTICULOS);
        assertThat(contar("select count(*) from historial_precio where ferreteria_id = ? and origen = 'MASIVO' "
                + "and usuario_id = ?", ferreteriaId, usuarioId)).isEqualTo(ARTICULOS);
        assertThat(total).as("vista previa + aplicar de 5.000 articulos").isLessThan(LIMITE);
    }

    /**
     * Corte 3 (R-222): lo mismo con COSTO_MONTO +10 sobre 5.000 articulos que una misma importacion toco por ultima
     * vez, filtrando por {@code importacionId}. Ferreteria propia, CUIT 30-78408001-1.
     */
    @Test
    void cincoMilArticulosDeUnaImportacionConCostoMontoSeVenYSeAplicanEnMenosDeDosMinutos() throws Exception {
        String cuit = "30-78408001-1";
        String email = "dueno@rendimiento-monto.demo";
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) "
                + "values (?, ?, 'RESPONSABLE_INSCRIPTO')", "Rendimiento monto " + cuit, cuit);
        long ferreteriaId = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        String hash = jdbc.queryForObject(
                "select password_hash from usuario where email = 'dueno@tornillo.demo'", String.class);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, ?, 'Dueno Rendimiento monto', 'DUENO')", ferreteriaId, email, hash);
        long usuarioId = jdbc.queryForObject("select id from usuario where email = ?", Long.class, email);
        jdbc.update("insert into proveedor (ferreteria_id, nombre) values (?, 'Proveedor del monto')", ferreteriaId);
        long proveedorId = jdbc.queryForObject(
                "select id from proveedor where ferreteria_id = ?", Long.class, ferreteriaId);
        jdbc.update("insert into importacion_lista (ferreteria_id, proveedor_id, usuario_id, archivo, suben, bajan, "
                + "nuevos, sin_cambio, no_estan, con_error, estado) values (?, ?, ?, 'grande.xlsx', 0, 0, 0, 0, 0, 0, "
                + "'APLICADA')", ferreteriaId, proveedorId, usuarioId);
        long importacionId = jdbc.queryForObject(
                "select id from importacion_lista where ferreteria_id = ?", Long.class, ferreteriaId);

        // 5.000 articulos de la importacion y otros 5.000 de ninguna (alta manual) que el filtro tiene que dejar afuera.
        List<Object[]> filas = new ArrayList<>(2 * ARTICULOS);
        for (int i = 1; i <= ARTICULOS; i++) {
            String codigo = String.format("I-%05d", i);
            filas.add(new Object[] {ferreteriaId, codigo, "Articulo " + codigo, proveedorId, importacionId});
        }
        for (int i = 1; i <= ARTICULOS; i++) {
            String codigo = String.format("N-%05d", i);
            filas.add(new Object[] {ferreteriaId, codigo, "Articulo " + codigo, proveedorId, null});
        }
        jdbc.batchUpdate("insert into articulo (ferreteria_id, codigo_interno, descripcion, proveedor_id, costo, "
                + "alicuota_iva, precio_final, unidad, ultima_importacion_id) "
                + "values (?, ?, ?, ?, 100.00, 'IVA_21', 160.00, 'UNIDAD', ?)", filas);
        assertThat(contar("select count(*) from articulo where ferreteria_id = ? and ultima_importacion_id = ?",
                ferreteriaId, importacionId)).isEqualTo(ARTICULOS);
        jdbc.execute("analyze table articulo");

        // El filtro usa un indice por importacion (el de ferreteria + importacion o el de la clave foranea), no recorre la
        // tabla ni ordena aparte: EXPLAIN sobre el SQL REAL que genera Hibernate, con los valores de este caso.
        TenantContext.setCurrentFerreteriaId(ferreteriaId);
        List<String> sqlReal;
        try {
            sqlReal = SqlCapturado.durante(() -> articuloRepository.filtrarScoped(
                    new FiltroArticulos(null, null, null, false, importacionId)));
        } finally {
            TenantContext.clear();
        }
        Map<String, Object> valores = new HashMap<>();
        valores.put("fid", ferreteriaId);
        valores.put("proveedorId", null);
        valores.put("rubroId", null);
        valores.put("importacionId", importacionId);
        valores.put("q", null);
        valores.put("incluirInactivos", false);
        List<Map<String, Object>> plan = ExplainDelFiltro.explain(jdbc, sqlReal, valores);
        assertThat(plan).hasSize(1);
        // Con este volumen el optimizador oscila entre ref e index segun las estadisticas muestreadas (el order by a.id
        // empuja a recorrer el indice): la prueba estricta del indice vive en ArticuloRepositoryFiltroImportacionTest.
        // Aca solo se exige que no recorra la tabla entera.
        assertThat(plan.get(0).get("type")).as("tipo de acceso. Plan: " + plan + "\nSQL: " + sqlReal)
                .isNotEqualTo("ALL");
        assertThat(plan.get(0).get("key")).as("usa algun indice").isNotNull();

        String token = login(email);
        ObjectNode pedido = objectMapper.createObjectNode()
                .put("modo", "COSTO_MONTO").put("valor", new BigDecimal("10")).put("importacionId", importacionId);

        long inicio = System.nanoTime();
        String cuerpoVistaPrevia = postJson("/api/actualizacion-masiva/vista-previa", token, pedido.toString())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long trasVistaPrevia = System.nanoTime();
        JsonNode vistaPrevia = objectMapper.readTree(cuerpoVistaPrevia);
        ObjectNode aplicar = pedido.deepCopy().put("huella", vistaPrevia.get("huella").asText());
        String cuerpoAplicada = postJson("/api/actualizacion-masiva", token, aplicar.toString())
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long fin = System.nanoTime();

        Duration total = Duration.ofNanos(fin - inicio);
        LOG.info("RENDIMIENTO CORTE 3 (5.000 articulos de una importacion, COSTO_MONTO +10, los 5.000 cambian): "
                        + "vista previa {} ms, aplicar {} ms, total {} ms (limite {} ms)",
                Duration.ofNanos(trasVistaPrevia - inicio).toMillis(), Duration.ofNanos(fin - trasVistaPrevia).toMillis(),
                total.toMillis(), LIMITE.toMillis());

        JsonNode aplicada = objectMapper.readTree(cuerpoAplicada);
        assertThat(vistaPrevia.get("conteos").get("total").asInt()).isEqualTo(ARTICULOS);
        assertThat(vistaPrevia.get("conteos").get("modificados").asInt()).isEqualTo(ARTICULOS);
        assertThat(vistaPrevia.get("enPesos").asInt()).isEqualTo(ARTICULOS);
        assertThat(aplicada.get("entradasDeHistorial").asInt()).isEqualTo(ARTICULOS);
        assertThat(contar("select count(*) from articulo where ferreteria_id = ? and costo = 110.00 "
                + "and ultima_importacion_id = ?", ferreteriaId, importacionId)).isEqualTo(ARTICULOS);
        assertThat(contar("select count(*) from articulo where ferreteria_id = ? and costo = 100.00 "
                + "and ultima_importacion_id is null", ferreteriaId)).isEqualTo(ARTICULOS);
        assertThat(contar("select count(*) from historial_precio where ferreteria_id = ? and origen = 'MASIVO' "
                + "and usuario_id = ?", ferreteriaId, usuarioId)).isEqualTo(ARTICULOS);
        assertThat(total).as("vista previa + aplicar de 5.000 articulos").isLessThan(LIMITE);
    }
}
