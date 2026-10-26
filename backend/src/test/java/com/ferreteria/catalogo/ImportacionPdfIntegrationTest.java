package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ferreteria.cotizacion.CotizacionTestBase;
import com.ferreteria.cotizacion.model.LadoCotizacion;
import com.ferreteria.support.ListasDePrueba;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Importacion de una lista en PDF de punta a punta contra MySQL real (CU-04): un PDF armado como LISTA 336, con el
 * encabezado repetido en cada pagina, un rubro como titulo, filas en pesos y en dolares («US$») y la alicuota en Sin IVA
 * y Con IVA. Ferreteria propia (CUIT 30-78190002-4) con el dolar vigente en 1.000.
 */
class ImportacionPdfIntegrationTest extends CotizacionTestBase {

    private static final String MUESTRA = "/api/importaciones/muestra";
    private static final String VISTA_PREVIA = "/api/importaciones/vista-previa";
    private static final String APLICAR = "/api/importaciones";
    private static final String CASA = "t80-oficial";
    private static final Instant HACE_UN_RATO = Instant.parse("2026-10-26T11:00:00Z");
    private static final List<String> ENCABEZADO =
        List.of("Código", "Artículo", "Mon.", "Lista (unidad)", "Bulto (u.)", "Sin IVA", "Con IVA");
    /** Filas de datos de la primera pagina: con el titulo de la lista, el encabezado y el rubro la llenan. */
    private static final int DATOS_PAGINA_UNO = ListasDePrueba.RENGLONES_POR_PAGINA_PDF - 3;

    private Long ferreteria;
    private String dueno;

    @BeforeEach
    void sembrar() throws Exception {
        ferreteria = ferreteria("30-78190002-4", "Lista en PDF");
        borrarCotizaciones("t80-");
        cotizacion("USD", CASA, "1090", "1100", HACE_UN_RATO);
        configuracion(ferreteria, c -> {
            c.setCasaPrecios(CASA);
            c.fijarVigente(new BigDecimal("1000.0000"), CASA, LadoCotizacion.VENTA, HACE_UN_RATO);
        });
        dueno = login(usuario(ferreteria, "dueno-t80@test.local", "DUENO"));
    }

    @AfterEach
    void limpiar() {
        borrarCotizaciones("t80-");
        borrarDatosDe(ferreteria);
    }

    /**
     * Pagina 1: «CATÁLOGO» a la derecha, el encabezado, el rubro TORNILLERIA y TOR-001 a TOR-022 (TOR-002 en dolares,
     * TOR-003 con IVA 10,5 %). Pagina 2: el encabezado repetido y TUE-001.
     */
    private static MockMultipartFile listaPdf() {
        List<List<String>> filas = new ArrayList<>();
        filas.add(List.of("", "", "", "", "", "CATÁLOGO"));
        filas.add(ENCABEZADO);
        filas.add(List.of("TORNILLERIA"));
        filas.add(List.of("TOR-001", "Tornillo 3/8", "ARG", "$ 100,00", "—", "$ 100,00", "$ 121,00"));
        filas.add(List.of("TOR-002", "Tornillo 1/2", "USD", "US$ 10,00", "—", "US$ 10,00", "US$ 12,10"));
        filas.add(List.of("TOR-003", "Mecha 6 mm", "ARG", "$ 200,00", "—", "$ 200,00", "$ 221,00"));
        for (int i = 4; i <= DATOS_PAGINA_UNO; i++) {
            String codigo = String.format("TOR-%03d", i);
            filas.add(List.of(codigo, "Tornillo " + i, "ARG", "$ 1.0" + String.format("%02d", i) + ",50", "—",
                "$ 1.000,00", "$ 1.210,00"));
        }
        filas.add(ENCABEZADO);
        filas.add(List.of("TUE-001", "Tuerca 3/8", "ARG", "$ 50,00", "12", "$ 600,00", "$ 726,00"));
        return archivo("lista-336.pdf", ListasDePrueba.pdf(filas));
    }

    /** Codigo A, descripcion B, moneda C, costo D, sin IVA F y con IVA G, datos desde la fila 3. */
    private ObjectNode datos(long proveedor) {
        ObjectNode mapeo = objectMapper.createObjectNode().put("hoja", 0).put("filaDesde", 3).put("columnaCodigo", 0)
            .put("columnaDescripcion", 1).put("columnaMoneda", 2).put("columnaCosto", 3)
            .put("columnaSinIva", 5).put("columnaConIva", 6);
        ObjectNode datos = objectMapper.createObjectNode().put("proveedorId", proveedor).put("listaIncluyeIva", false);
        datos.set("mapeo", mapeo);
        return datos;
    }

    private long proveedor() throws Exception {
        String body = postJson("/api/proveedores", dueno, "{\"nombre\":\"" + unico("Prov") + "\"}")
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private JsonNode enviar(String url, ObjectNode datos, int estado) throws Exception {
        return objectMapper.readTree(postMultipart(url, dueno, listaPdf(), datos)
            .andExpect(status().is(estado)).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private Map<String, Object> articulo(String codigo) {
        return jdbc.queryForMap("select * from articulo where ferreteria_id = ? and codigo_proveedor = ?", ferreteria,
            codigo);
    }

    @Test
    void laMuestraDeUnPdfEsUnaHojaConSusColumnasYElEncabezado() throws Exception {
        postMultipart(MUESTRA, dueno, listaPdf(), null)
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hojas.length()", is(1)))
            .andExpect(jsonPath("$.hojas[0].nombre", is("PDF")))
            .andExpect(jsonPath("$.hojas[0].columnas", is(7)))
            .andExpect(jsonPath("$.hojas[0].filas[0].celdas", contains("CATÁLOGO")))
            .andExpect(jsonPath("$.hojas[0].filas[1].celdas", contains(ENCABEZADO.toArray())))
            .andExpect(jsonPath("$.hojas[0].filas[3].numero", is(4)))
            .andExpect(jsonPath("$.hojas[0].filas[3].celdas",
                contains("TOR-001", "Tornillo 3/8", "ARG", "$ 100,00", "—", "$ 100,00", "$ 121,00")));
    }

    @Test
    void laVistaPreviaSalteaElRubroYElEncabezadoRepetidoYAplicarCreaLosArticulos() throws Exception {
        long proveedor = proveedor();
        ObjectNode datos = datos(proveedor);

        JsonNode previa = enviar(VISTA_PREVIA, datos, 200);

        JsonNode conteos = previa.get("conteos");
        assertThat(conteos.get("nuevos").asInt()).isEqualTo(DATOS_PAGINA_UNO + 1);
        assertThat(conteos.get("conError").asInt()).isZero();
        assertThat(conteos.get("salteadas").asInt()).isEqualTo(2);

        ObjectNode pedido = datos.deepCopy().put("crearNuevos", true);
        pedido.set("conteosEsperados", conteos);
        JsonNode respuesta = objectMapper.readTree(postMultipart(APLICAR, dueno, listaPdf(), pedido)
            .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertThat(respuesta.get("conteos").get("nuevos").asInt()).isEqualTo(DATOS_PAGINA_UNO + 1);
        assertThat((BigDecimal) articulo("TOR-001").get("costo")).isEqualByComparingTo("100");
        assertThat(articulo("TOR-001").get("alicuota_iva")).isEqualTo("IVA_21");
        assertThat(articulo("TOR-002").get("moneda")).isEqualTo("USD");
        assertThat((BigDecimal) articulo("TOR-002").get("costo")).isEqualByComparingTo("10");
        assertThat(articulo("TOR-003").get("alicuota_iva")).isEqualTo("IVA_10_5");
        assertThat((BigDecimal) articulo("TOR-004").get("costo")).isEqualByComparingTo("1004.50");
        assertThat(articulo("TUE-001").get("descripcion")).isEqualTo("Tuerca 3/8");
    }
}
