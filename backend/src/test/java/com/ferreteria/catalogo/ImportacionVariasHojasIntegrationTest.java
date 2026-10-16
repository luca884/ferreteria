package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ferreteria.cotizacion.CotizacionTestBase;
import com.ferreteria.cotizacion.model.LadoCotizacion;
import com.ferreteria.support.ListasDePrueba;
import com.ferreteria.support.ListasReales;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Importacion de punta a punta con libros de varias hojas (R-102 a R-106, R-111, R-116) contra MySQL real: los fixtures
 * chicos de LISTA 336 (3 hojas, moneda por fila, IVA de I / H, titulos en Mon.) y de M-S (.xls de encabezado de dos
 * filas). Ferreteria propia (CUIT 30-78190001-1) con el dolar vigente en 1.000.
 */
class ImportacionVariasHojasIntegrationTest extends CotizacionTestBase {

    private static final String MUESTRA = "/api/importaciones/muestra";
    private static final String VISTA_PREVIA = "/api/importaciones/vista-previa";
    private static final String APLICAR = "/api/importaciones";
    private static final String CASA = "t79-oficial";
    private static final Instant HACE_UN_RATO = Instant.parse("2026-10-16T11:00:00Z");

    private Long ferreteria;
    private String dueno;

    @BeforeEach
    void sembrar() throws Exception {
        ferreteria = ferreteria("30-78190001-1", "Varias hojas");
        borrarCotizaciones("t79-");
        cotizacion("USD", CASA, "1090", "1100", HACE_UN_RATO);
        configuracion(ferreteria, c -> {
            c.setCasaPrecios(CASA);
            c.fijarVigente(new BigDecimal("1000.0000"), CASA, LadoCotizacion.VENTA, HACE_UN_RATO);
        });
        dueno = login(usuario(ferreteria, "dueno-t79@test.local", "DUENO"));
    }

    @AfterEach
    void limpiar() {
        borrarCotizaciones("t79-");
        borrarDatosDe(ferreteria);
    }

    // ---- helpers ----

    private long proveedor() throws Exception {
        String body = postJson("/api/proveedores", dueno, "{\"nombre\":\"" + unico("Prov") + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private long rubro(String nombre) throws Exception {
        String body = postJson("/api/rubros", dueno, "{\"nombre\":\"" + nombre + "\",\"margenPorcentaje\":40}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private long articulo(long proveedor, String codigo, String costo) throws Exception {
        ObjectNode pedido = objectMapper.createObjectNode()
                .put("codigoInterno", unico("INT").substring(0, 12)).put("codigoProveedor", codigo)
                .put("descripcion", "Articulo " + codigo).put("unidad", "UNIDAD").put("proveedorId", proveedor)
                .put("margenPorcentaje", new BigDecimal("30")).put("costo", new BigDecimal(costo));
        String body = postJson("/api/articulos", dueno, objectMapper.writeValueAsString(pedido))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    /** LISTA 336: codigo A, descripcion B, moneda E, costo F, sin IVA H y con IVA I, datos desde la fila 2. */
    private ObjectNode mapeo336(Integer... hojas) {
        ObjectNode mapeo = objectMapper.createObjectNode().put("hoja", 0).put("filaDesde", 2).put("columnaCodigo", 0)
                .put("columnaDescripcion", 1).put("columnaCosto", 5).put("columnaMoneda", 4)
                .put("columnaSinIva", 7).put("columnaConIva", 8);
        if (hojas.length > 0) {
            mapeo.set("hojas", objectMapper.valueToTree(hojas));
        }
        return mapeo;
    }

    private ObjectNode datos(long proveedor, ObjectNode mapeo) {
        ObjectNode datos = objectMapper.createObjectNode().put("proveedorId", proveedor).put("listaIncluyeIva", false);
        datos.set("mapeo", mapeo);
        return datos;
    }

    private JsonNode previa(MockMultipartFile archivo, ObjectNode datos) throws Exception {
        return objectMapper.readTree(postMultipart(VISTA_PREVIA, dueno, archivo, datos).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode aplicar(MockMultipartFile archivo, ObjectNode datos, JsonNode conteos, boolean crear, Long rubroId)
            throws Exception {
        ObjectNode pedido = datos.deepCopy();
        pedido.put("crearNuevos", crear);
        if (rubroId != null) {
            pedido.put("rubroId", rubroId);
        }
        pedido.set("conteosEsperados", conteos);
        return objectMapper.readTree(postMultipart(APLICAR, dueno, archivo, pedido).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private MockMultipartFile lista336() {
        return archivo("lista-336.xlsx", ListasReales.lista336Chica());
    }

    private long contar(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private Map<String, Object> articuloPorCodigo(String codigo) {
        return jdbc.queryForMap("select * from articulo where ferreteria_id = ? and codigo_proveedor = ?", ferreteria, codigo);
    }

    private static List<Object> fila(Object... celdas) {
        return Arrays.asList(celdas);
    }

    private static final List<Object> ENCABEZADO = fila("Codigo", "Descripcion", "Costo");

    private MockMultipartFile libro(Map<String, List<List<Object>>> hojas) {
        return archivo("libro.xlsx", ListasDePrueba.xlsxHojas(hojas));
    }

    private ObjectNode mapeoSimple(Integer... hojas) {
        ObjectNode mapeo = objectMapper.createObjectNode().put("hoja", 0).put("filaDesde", 2).put("columnaCodigo", 0)
                .put("columnaDescripcion", 1).put("columnaCosto", 2);
        if (hojas.length > 0) {
            mapeo.set("hojas", objectMapper.valueToTree(hojas));
        }
        return mapeo;
    }

    // ---- tests ----

    @Test
    void unaVistaPreviaConLasTresHojasYUnSoloAplicarCreanUnaSolaImportacion() throws Exception {
        long proveedor = proveedor();
        ObjectNode datos = datos(proveedor, mapeo336(0, 1, 2));

        JsonNode previa = previa(lista336(), datos);

        JsonNode conteos = previa.get("conteos");
        assertThat(conteos.get("nuevos").asInt()).isEqualTo(7);
        assertThat(conteos.get("conError").asInt()).isEqualTo(1);
        assertThat(conteos.get("salteadas").asInt()).isEqualTo(1);
        assertThat(conteos.get("suben").asInt()).isZero();
        assertThat(previa.get("nuevos")).extracting(n -> n.get("hoja").asInt()).containsExactly(0, 0, 0, 0, 1, 1, 2);

        JsonNode respuesta = aplicar(lista336(), datos, conteos, true, null);

        assertThat(contar("select count(*) from importacion_lista where proveedor_id = ?", proveedor)).isEqualTo(1);
        assertThat(contar("select count(*) from articulo where proveedor_id = ?", proveedor)).isEqualTo(7);
        assertThat(respuesta.get("conteos").get("nuevos").asInt()).isEqualTo(7);
        assertThat(respuesta.get("conteos").get("salteadas").asInt()).isEqualTo(1);
    }

    @Test
    void elCostoEsPorUnidadElIvaSaleDeIDivididoHYLasFilasEnDolaresQuedanEnDolares() throws Exception {
        long proveedor = proveedor();
        ObjectNode datos = datos(proveedor, mapeo336(0, 1, 2));
        JsonNode previa = previa(lista336(), datos);
        aplicar(lista336(), datos, previa.get("conteos"), true, null);

        // TOR-004 viene de a bultos de 10: el costo es F (150) y nunca H (1.500).
        assertThat((BigDecimal) articuloPorCodigo("TOR-004").get("costo")).isEqualByComparingTo("150");
        assertThat(articuloPorCodigo("TOR-003").get("alicuota_iva")).isEqualTo("IVA_10_5");
        assertThat(articuloPorCodigo("TOR-001").get("alicuota_iva")).isEqualTo("IVA_21");
        assertThat(articuloPorCodigo("TOR-002").get("moneda")).isEqualTo("USD");
        assertThat((BigDecimal) articuloPorCodigo("TOR-002").get("costo")).isEqualByComparingTo("10");
        // 10 x 1.000 x 1,30 x 1,21 = 15.730
        assertThat((BigDecimal) articuloPorCodigo("TOR-002").get("precio_final")).isEqualByComparingTo("15730");
        assertThat(articuloPorCodigo("TUE-001").get("moneda")).isEqualTo("ARS");
        // La fila con costo 0 es un error y no se crea.
        assertThat(contar("select count(*) from articulo where ferreteria_id = ? and codigo_proveedor = 'TOR-005'", ferreteria))
                .isZero();
        assertThat(previa.get("errores").get(0).get("codigo").asText()).isEqualTo("TOR-005");
    }

    @Test
    void conHojasCeroYUnoSeLeenSoloEsasDos() throws Exception {
        long proveedor = proveedor();

        JsonNode previa = previa(lista336(), datos(proveedor, mapeo336(0, 1)));

        assertThat(previa.get("conteos").get("nuevos").asInt()).isEqualTo(6);
        assertThat(previa.get("nuevos")).extracting(n -> n.get("codigo").asText())
                .doesNotContain("ARA-001");
    }

    @Test
    void unArticuloPresenteEnUnaHojaIntermediaNoEsUnoQueNoEstaYElAusenteSaleUnaSolaVez() throws Exception {
        long proveedor = proveedor();
        articulo(proveedor, "T-100", "100");
        articulo(proveedor, "Z-999", "100");
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put("TORNILLOS", List.of(ENCABEZADO, fila("A-1", "x", new BigDecimal("10"))));
        hojas.put("TUERCAS", List.of(ENCABEZADO, fila("T-100", "x", new BigDecimal("100"))));
        hojas.put("ARANDELAS", List.of(ENCABEZADO, fila("A-2", "x", new BigDecimal("10"))));

        JsonNode previa = previa(libro(hojas), datos(proveedor, mapeoSimple(0, 1, 2)));

        assertThat(previa.get("noEstan")).hasSize(1);
        assertThat(previa.get("noEstan").get(0).get("codigoProveedor").asText()).isEqualTo("Z-999");
        assertThat(previa.get("conteos").get("sinCambio").asInt()).isEqualTo(1);
    }

    @Test
    void unCodigoRepetidoEntreHojasEsErrorEnLasDosFilasConHojaYFila() throws Exception {
        long proveedor = proveedor();
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put("TORNILLOS", List.of(ENCABEZADO, fila("A-1", "x", new BigDecimal("10"))));
        hojas.put("TUERCAS", List.of(ENCABEZADO, fila("otra", "x", new BigDecimal("10")), fila("A-1", "x", new BigDecimal("10"))));

        JsonNode previa = previa(libro(hojas), datos(proveedor, mapeoSimple(0, 1)));

        assertThat(previa.get("errores")).hasSize(2);
        String mensaje = "El código aparece repetido en la lista (TORNILLOS fila 2 y TUERCAS fila 3).";
        assertThat(previa.get("errores").get(0).get("mensaje").asText()).isEqualTo(mensaje);
        assertThat(previa.get("errores").get(1).get("mensaje").asText()).isEqualTo(mensaje);
        assertThat(previa.get("errores").get(1).get("hoja").asInt()).isEqualTo(1);
        assertThat(previa.get("errores").get(1).get("fila").asInt()).isEqualTo(3);
        assertThat(previa.get("conteos").get("nuevos").asInt()).isEqualTo(1);
    }

    @Test
    void elNombreDeLaHojaNoEsRubro() throws Exception {
        long proveedor = proveedor();
        long rubroElegido = rubro("Elegido " + unico("R"));
        long rubroPropio = rubro("Propio " + unico("R"));
        long existente = articulo(proveedor, "EXI-1", "100");
        jdbc.update("update articulo set rubro_id = ? where id = ?", rubroPropio, existente);
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put("FERRETERIA GENERAL", List.of(ENCABEZADO, fila("N-1", "Nuevo", new BigDecimal("10")),
                fila("EXI-1", "x", new BigDecimal("120"))));
        long rubrosAntes = contar("select count(*) from rubro where ferreteria_id = ?", ferreteria);
        ObjectNode datos = datos(proveedor, mapeoSimple());

        // Sin rubro elegido el nuevo queda sin rubro; con rubro elegido, en ese.
        JsonNode previa = previa(libro(hojas), datos);
        aplicar(libro(hojas), datos, previa.get("conteos"), true, null);
        assertThat(articuloPorCodigo("N-1").get("rubro_id")).isNull();
        Map<String, List<List<Object>>> otra = new LinkedHashMap<>();
        otra.put("FERRETERIA GENERAL", List.of(ENCABEZADO, fila("N-2", "Otro nuevo", new BigDecimal("10"))));
        JsonNode segunda = previa(libro(otra), datos);
        aplicar(libro(otra), datos, segunda.get("conteos"), true, rubroElegido);

        assertThat(((Number) articuloPorCodigo("N-2").get("rubro_id")).longValue()).isEqualTo(rubroElegido);
        // El existente conserva el suyo y la tabla de rubros no cambia.
        assertThat(((Number) articuloPorCodigo("EXI-1").get("rubro_id")).longValue()).isEqualTo(rubroPropio);
        assertThat(contar("select count(*) from rubro where ferreteria_id = ?", ferreteria)).isEqualTo(rubrosAntes);
    }

    @Test
    void msChicaSeImportaDeVerdadConLosTitulosSalteadosYLosCostosADosDecimales() throws Exception {
        long proveedor = proveedor();
        MockMultipartFile ms = archivo("ms.xls", ListasReales.msChica());
        ObjectNode mapeo = objectMapper.createObjectNode().put("hoja", 0).put("filaDesde", 15).put("columnaCodigo", 0)
                .put("columnaDescripcion", 1).put("columnaCosto", 2);
        ObjectNode datos = datos(proveedor, mapeo);

        JsonNode previa = previa(ms, datos);

        assertThat(previa.get("conteos").get("nuevos").asInt()).isEqualTo(ListasReales.MS_CHICA_ARTICULOS);
        assertThat(previa.get("conteos").get("salteadas").asInt()).isEqualTo(2);
        assertThat(previa.get("conteos").get("conError").asInt()).isZero();
        aplicar(ms, datos, previa.get("conteos"), true, null);
        assertThat((BigDecimal) articuloPorCodigo("P-001").get("costo")).isEqualByComparingTo("1532.44");
        assertThat((BigDecimal) articuloPorCodigo("C-002").get("costo")).isEqualByComparingTo("120.00");
        assertThat(contar("select count(*) from articulo where proveedor_id = ?", proveedor))
                .isEqualTo(ListasReales.MS_CHICA_ARTICULOS);
    }

    @Test
    void elMapeoGuardadoLlevaLasHojasYUnaHojaNuevaQuedaFueraDeLoGuardado() throws Exception {
        long proveedor = proveedor();
        ObjectNode datos = datos(proveedor, mapeo336(0, 1));
        JsonNode previa = previa(lista336(), datos);
        aplicar(lista336(), datos, previa.get("conteos"), true, null);

        JsonNode guardado = getJson("/api/proveedores/" + proveedor, dueno).get("mapeoLista");
        assertThat(guardado.get("hojas")).extracting(JsonNode::asInt).containsExactly(0, 1);
        assertThat(guardado.get("columnaMoneda").asInt()).isEqualTo(4);
        assertThat(guardado.get("columnaSinIva").asInt()).isEqualTo(7);
        assertThat(guardado.get("encabezados").get("moneda").asText()).isEqualTo("Mon.");

        // La muestra del mismo archivo reconoce las tres hojas: la 2 es nueva respecto de lo guardado.
        postMultipart(MUESTRA, dueno, lista336(), null).andExpect(status().isOk())
                .andExpect(jsonPath("$.propuesta.hojas.length()", is(3)))
                .andExpect(jsonPath("$.propuesta.hojas[2]", is(2)));
    }

    @Test
    void laColumnaStockDelArchivoNoCambiaElStockDeNingunArticulo() throws Exception {
        long proveedor = proveedor();
        long existente = articulo(proveedor, "TOR-001", "100");
        jdbc.update("update articulo set stock_actual = 7 where id = ?", existente);
        ObjectNode datos = datos(proveedor, mapeo336(0));
        JsonNode previa = previa(lista336(), datos);

        aplicar(lista336(), datos, previa.get("conteos"), true, null);

        assertThat((BigDecimal) jdbc.queryForObject("select stock_actual from articulo where id = ?", BigDecimal.class, existente))
                .isEqualByComparingTo("7");
        // Los nuevos entran con el stock con el que se crea cualquier articulo: 0 (la lista trae 10 en "Stock").
        assertThat((BigDecimal) articuloPorCodigo("TOR-002").get("stock_actual")).isEqualByComparingTo("0");
        assertThat(previa.get("conteos").get("nuevos").asInt()).isEqualTo(3);
    }
}
