package com.ferreteria.catalogo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ferreteria.support.IntegrationTestBase;
import com.ferreteria.support.ListasDePrueba;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Criterio de exito de CU-04: una lista de 5.000 filas se previsualiza y se aplica en menos de 2 minutos.
 * Cada escenario corre en su PROPIA ferreteria (creada por jdbc, con un Dueno que reusa la contrasena
 * demo), asi no ensucia a los tenants demo. El armado de los datos y del xlsx no se cronometra: se mide
 * solo vista previa + aplicar, pasando por el filtro de seguridad, el parseo y la transaccion real.
 */
class ImportacionRendimientoIntegrationTest extends IntegrationTestBase {

    private static final Logger LOG = LoggerFactory.getLogger(ImportacionRendimientoIntegrationTest.class);

    private static final Duration LIMITE = Duration.ofSeconds(120);
    private static final int FILAS = 5_000;

    /** Una ferreteria con su Dueno y un proveedor, lista para importar. */
    private record Escenario(long ferreteriaId, String email, long proveedorId, long usuarioId) {}

    private Escenario crearEscenario(String cuit, String email) {
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) "
                + "values (?, ?, 'RESPONSABLE_INSCRIPTO')", "Rendimiento " + cuit, cuit);
        long ferreteriaId = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        String hash = jdbc.queryForObject(
                "select password_hash from usuario where email = 'dueno@tornillo.demo'", String.class);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, ?, 'Dueno Rendimiento', 'DUENO')", ferreteriaId, email, hash);
        long usuarioId = jdbc.queryForObject("select id from usuario where email = ?", Long.class, email);
        jdbc.update("insert into proveedor (ferreteria_id, nombre) values (?, 'Proveedor grande')", ferreteriaId);
        long proveedorId = jdbc.queryForObject(
                "select id from proveedor where ferreteria_id = ?", Long.class, ferreteriaId);
        return new Escenario(ferreteriaId, email, proveedorId, usuarioId);
    }

    /** Articulos del proveedor con codigo P-00001..P-n y el mismo costo, insertados en lotes por jdbc. */
    private void crearArticulos(Escenario e, int cantidad, String costo) {
        List<Object[]> filas = new ArrayList<>(cantidad);
        for (int i = 1; i <= cantidad; i++) {
            String codigo = codigo(i);
            filas.add(new Object[] {e.ferreteriaId(), codigo, codigo, "Articulo " + codigo, e.proveedorId(),
                    new BigDecimal(costo), new BigDecimal("170.00")});
        }
        jdbc.batchUpdate("insert into articulo (ferreteria_id, codigo_interno, codigo_proveedor, descripcion, "
                + "proveedor_id, costo, margen_porcentaje, alicuota_iva, precio_final, unidad) "
                + "values (?, ?, ?, ?, ?, ?, 35.00, 'IVA_21', ?, 'UNIDAD')", filas);
    }

    private static String codigo(int i) {
        return String.format("P-%05d", i);
    }

    private MockMultipartFile xlsx(List<List<Object>> filas) {
        List<List<Object>> conEncabezado = new ArrayList<>(filas.size() + 1);
        conEncabezado.add(List.of("Codigo", "Descripcion", "Costo"));
        conEncabezado.addAll(filas);
        return archivo("lista-grande.xlsx", ListasDePrueba.xlsx(conEncabezado));
    }

    private ObjectNode datos(long proveedorId, Boolean crearNuevos, int[] conteos) {
        ObjectNode datos = objectMapper.createObjectNode().put("proveedorId", proveedorId).put("listaIncluyeIva", false);
        datos.set("mapeo", objectMapper.createObjectNode()
                .put("hoja", 0).put("filaDesde", 2)
                .put("columnaCodigo", 0).put("columnaDescripcion", 1).put("columnaCosto", 2));
        if (crearNuevos != null) {
            datos.put("crearNuevos", crearNuevos);
            datos.set("conteosEsperados", objectMapper.createObjectNode()
                    .put("suben", conteos[0]).put("bajan", conteos[1]).put("nuevos", conteos[2])
                    .put("sinCambio", conteos[3]).put("noEstan", conteos[4]).put("conError", conteos[5]));
        }
        return datos;
    }

    private long contar(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private void verConteos(JsonNode conteos, int... esperados) {
        assertThat(new int[] {
                conteos.get("suben").asInt(), conteos.get("bajan").asInt(), conteos.get("nuevos").asInt(),
                conteos.get("sinCambio").asInt(), conteos.get("noEstan").asInt(), conteos.get("conError").asInt()})
                .containsExactly(esperados);
    }

    @Test
    void cincoMilFilasQueCambianTodasSeVenYSeAplicanEnMenosDeDosMinutos() throws Exception {
        Escenario e = crearEscenario("30-72000001-1", "dueno@rendimiento1.demo");
        crearArticulos(e, FILAS, "100.00");
        List<List<Object>> filas = new ArrayList<>(FILAS);
        for (int i = 1; i <= FILAS; i++) {
            filas.add(List.of(codigo(i), "Articulo " + codigo(i), new BigDecimal("120.00")));
        }
        MockMultipartFile lista = xlsx(filas);
        String token = login(e.email());
        int[] conteos = {FILAS, 0, 0, 0, 0, 0};

        long inicio = System.nanoTime();
        String vistaPrevia = postMultipart("/api/importaciones/vista-previa", token, lista, datos(e.proveedorId(), null, null))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long trasVistaPrevia = System.nanoTime();
        String aplicada = postMultipart("/api/importaciones", token, lista, datos(e.proveedorId(), false, conteos))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long fin = System.nanoTime();

        Duration total = Duration.ofNanos(fin - inicio);
        LOG.info("RENDIMIENTO escenario 1 (5.000 articulos, 5.000 filas que cambian): vista previa {} ms, aplicar {} ms, total {} ms (limite {} ms)",
                Duration.ofNanos(trasVistaPrevia - inicio).toMillis(), Duration.ofNanos(fin - trasVistaPrevia).toMillis(),
                total.toMillis(), LIMITE.toMillis());

        verConteos(objectMapper.readTree(vistaPrevia).get("conteos"), FILAS, 0, 0, 0, 0, 0);
        verConteos(objectMapper.readTree(aplicada).get("conteos"), FILAS, 0, 0, 0, 0, 0);
        assertThat(contar("select count(*) from historial_precio where ferreteria_id = ? and origen = 'IMPORTACION'",
                e.ferreteriaId())).isEqualTo(FILAS);
        assertThat(contar("select count(*) from articulo where ferreteria_id = ? and costo = 120.00 "
                + "and precio_lista_proveedor = 120.00", e.ferreteriaId())).isEqualTo(FILAS);
        assertThat(contar("select count(*) from importacion_lista where ferreteria_id = ?", e.ferreteriaId())).isEqualTo(1);
        assertThat(total).as("vista previa + aplicar de 5.000 filas").isLessThan(LIMITE);
    }

    @Test
    void cuatroMilQuinientosArticulosConCincoMilFilasMezcladasYQuinientosNuevos() throws Exception {
        Escenario e = crearEscenario("30-72000002-2", "dueno@rendimiento2.demo");
        crearArticulos(e, 4_500, "100.00");
        // 1..2000 suben, 2001..4000 bajan, 4001..4500 sin cambio, 4501..5000 nuevos.
        List<List<Object>> filas = new ArrayList<>(FILAS);
        for (int i = 1; i <= FILAS; i++) {
            BigDecimal costo = i <= 2_000 ? new BigDecimal("120.00") : i <= 4_000 ? new BigDecimal("80.00") : new BigDecimal("100.00");
            filas.add(List.of(codigo(i), "Articulo " + codigo(i), costo));
        }
        MockMultipartFile lista = xlsx(filas);
        String token = login(e.email());
        int[] conteos = {2_000, 2_000, 500, 500, 0, 0};

        long inicio = System.nanoTime();
        String vistaPrevia = postMultipart("/api/importaciones/vista-previa", token, lista, datos(e.proveedorId(), null, null))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long trasVistaPrevia = System.nanoTime();
        String aplicada = postMultipart("/api/importaciones", token, lista, datos(e.proveedorId(), true, conteos))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        long fin = System.nanoTime();

        Duration total = Duration.ofNanos(fin - inicio);
        LOG.info("RENDIMIENTO escenario 2 (4.500 articulos + 500 nuevos, 5.000 filas): vista previa {} ms, aplicar {} ms, total {} ms (limite {} ms)",
                Duration.ofNanos(trasVistaPrevia - inicio).toMillis(), Duration.ofNanos(fin - trasVistaPrevia).toMillis(),
                total.toMillis(), LIMITE.toMillis());

        verConteos(objectMapper.readTree(vistaPrevia).get("conteos"), 2_000, 2_000, 500, 500, 0, 0);
        verConteos(objectMapper.readTree(aplicada).get("conteos"), 2_000, 2_000, 500, 500, 0, 0);
        assertThat(contar("select count(*) from historial_precio where ferreteria_id = ? and origen = 'IMPORTACION'",
                e.ferreteriaId())).isEqualTo(4_500);
        assertThat(contar("select count(*) from articulo where ferreteria_id = ? and proveedor_id = ?",
                e.ferreteriaId(), e.proveedorId())).isEqualTo(FILAS);
        assertThat(contar("select count(*) from articulo where ferreteria_id = ? and codigo_proveedor >= 'P-04501'",
                e.ferreteriaId())).isEqualTo(500);
        assertThat(total).as("vista previa + aplicar de 5.000 filas").isLessThan(LIMITE);
    }
}
