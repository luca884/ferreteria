package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ferreteria.cotizacion.CotizacionTestBase;
import com.ferreteria.support.ListasDePrueba;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * Retrocompatibilidad por endpoint (R-110, R-125, R-130): un proveedor con el mapeo viejo de V1002 cargado por JDBC y
 * pedidos con el cuerpo de antes del corte 2 (sin hojas, sin campos nuevos y sin filas elegidas). Ferreteria propia
 * (CUIT 30-78200001-1).
 */
class ImportacionRetrocompatibilidadIntegrationTest extends CotizacionTestBase {

    private static final String VISTA_PREVIA = "/api/importaciones/vista-previa";
    private static final String APLICAR = "/api/importaciones";
    /** El JSON de V1002__importacion_demo.sql: sin ninguno de los campos del corte 2. */
    private static final String MAPEO_V1002 = "{\"hoja\":0,\"filaDesde\":2,\"columnaCodigo\":0,\"columnaDescripcion\":1,"
            + "\"columnaCosto\":2,\"encabezados\":{\"codigo\":\"Codigo\",\"descripcion\":\"Descripcion\",\"costo\":\"Precio\"}}";

    private Long ferreteria;
    private String dueno;

    @BeforeEach
    void sembrar() throws Exception {
        ferreteria = ferreteria("30-78200001-1", "Retrocompatibilidad");
        dueno = login(usuario(ferreteria, "dueno-t80@test.local", "DUENO"));
    }

    @AfterEach
    void limpiar() {
        borrarDatosDe(ferreteria);
    }

    private long proveedorConMapeoViejo() throws Exception {
        String body = postJson("/api/proveedores", dueno, "{\"nombre\":\"" + unico("Prov") + "\"}")
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = objectMapper.readTree(body).get("id").asLong();
        jdbc.update("update proveedor set mapeo_lista = ? where id = ?", MAPEO_V1002, id);
        return id;
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

    /** El cuerpo de antes del corte 2: proveedor, mapeo de 5 campos y, al aplicar, los seis contadores. */
    private ObjectNode cuerpoViejo(long proveedor) {
        ObjectNode datos = objectMapper.createObjectNode().put("proveedorId", proveedor).put("listaIncluyeIva", false);
        datos.set("mapeo", objectMapper.createObjectNode().put("hoja", 0).put("filaDesde", 2).put("columnaCodigo", 0)
                .put("columnaDescripcion", 1).put("columnaCosto", 2));
        return datos;
    }

    private ObjectNode aplicarViejo(ObjectNode datos, int suben, int bajan, int nuevos, int sinCambio, int noEstan, int conError) {
        ObjectNode aplicar = datos.deepCopy();
        aplicar.put("crearNuevos", true);
        aplicar.set("conteosEsperados", objectMapper.createObjectNode().put("suben", suben).put("bajan", bajan)
                .put("nuevos", nuevos).put("sinCambio", sinCambio).put("noEstan", noEstan).put("conError", conError));
        return aplicar;
    }

    private static List<Object> fila(Object... celdas) {
        return Arrays.asList(celdas);
    }

    private BigDecimal decimal(long id, String columna) {
        return jdbc.queryForObject("select " + columna + " from articulo where id = ?", BigDecimal.class, id);
    }

    private MockMultipartFile dosHojas(String codigo, String costo) {
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put("Lista", List.of(fila("Codigo", "Descripcion", "Precio"), fila(codigo, "x", new BigDecimal(costo))));
        hojas.put("Oferta", List.of(fila("Codigo", "Descripcion", "Precio"), fila("OFERTA-1", "x", new BigDecimal("5"))));
        return archivo("lista.xlsx", ListasDePrueba.xlsxHojas(hojas));
    }

    @Test
    void laVistaPreviaConElCuerpoViejoDa200YLeeSoloLaHojaCero() throws Exception {
        long proveedor = proveedorConMapeoViejo();
        articulo(proveedor, "RC-1", "100");

        String cuerpo = postMultipart(VISTA_PREVIA, dueno, dosHojas("RC-1", "120"), cuerpoViejo(proveedor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conteos.suben", is(1)))
                .andExpect(jsonPath("$.conteos.nuevos", is(0)))
                .andExpect(jsonPath("$.conteos.noEstan", is(0)))
                .andExpect(jsonPath("$.conteos.conError", is(0)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // Es el mismo resultado de antes para los articulos en pesos: ni moneda ni IVA ni ajustes cambian.
        JsonNode cambio = objectMapper.readTree(cuerpo).get("cambios").get(0);
        assertThat(cambio.get("hoja").asInt()).isZero();
        assertThat(cambio.get("monedaAnterior").asText()).isEqualTo("ARS");
        assertThat(cambio.get("monedaNueva").asText()).isEqualTo("ARS");
        assertThat(cambio.get("alicuotaNueva").asText()).isEqualTo("IVA_21");
        // 120 x 1,30 x 1,21 = 188,76 -> 190
        assertThat(cambio.get("precioNuevo").decimalValue()).isEqualByComparingTo("190");
    }

    @Test
    void aplicarConElCuerpoViejoSinFilasElegidasAplicaTodoYGuardaElMapeoSinInventarCampos() throws Exception {
        long proveedor = proveedorConMapeoViejo();
        long articulo = articulo(proveedor, "RC-1", "100");
        ObjectNode datos = cuerpoViejo(proveedor);

        postMultipart(APLICAR, dueno, dosHojas("RC-1", "120"), aplicarViejo(datos, 1, 0, 0, 0, 0, 0))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conteos.suben", is(1)));

        assertThat(decimal(articulo, "costo")).isEqualByComparingTo("120");
        JsonNode guardado = objectMapper.readTree(
                jdbc.queryForObject("select mapeo_lista from proveedor where id = ?", String.class, proveedor));
        // Los campos viejos se conservan (los encabezados se leen del archivo nuevo) y los del corte 2 no aparecen.
        assertThat(guardado.get("hoja").asInt()).isZero();
        assertThat(guardado.get("filaDesde").asInt()).isEqualTo(2);
        assertThat(guardado.get("columnaCodigo").asInt()).isZero();
        assertThat(guardado.get("columnaDescripcion").asInt()).isEqualTo(1);
        assertThat(guardado.get("columnaCosto").asInt()).isEqualTo(2);
        assertThat(guardado.get("encabezados").get("costo").asText()).isEqualTo("Precio");
        assertThat(guardado.fieldNames()).toIterable().containsExactlyInAnyOrder(
                "hoja", "filaDesde", "columnaCodigo", "columnaDescripcion", "columnaCosto", "encabezados");
        assertThat(guardado.get("encabezados").fieldNames()).toIterable()
                .containsExactlyInAnyOrder("codigo", "descripcion", "costo");
    }

    @Test
    void conElMapeoViejoLosAjustesYaGuardadosSeConservan() throws Exception {
        long proveedor = proveedorConMapeoViejo();
        long articulo = articulo(proveedor, "RC-1", "900");
        jdbc.update("update articulo set ajuste1 = -5.00 where id = ?", articulo);
        ObjectNode datos = cuerpoViejo(proveedor);

        postMultipart(APLICAR, dueno, dosHojas("RC-1", "1000"), aplicarViejo(datos, 1, 0, 0, 0, 0, 0))
                .andExpect(status().isCreated());

        assertThat(decimal(articulo, "ajuste1")).isEqualByComparingTo("-5");
        assertThat(decimal(articulo, "costo")).isEqualByComparingTo("950");
    }

    @Test
    void unaListaDeUnaHojaDaLosMismosContadoresQueEnCu04() throws Exception {
        long proveedor = proveedorConMapeoViejo();
        String s = unico("");
        for (String clave : List.of("S1", "S2", "B1", "C1")) {
            articulo(proveedor, clave + s, "100");
        }
        articulo(proveedor, "AUS" + s, "100");
        MockMultipartFile lista = archivo("lista.xlsx", ListasDePrueba.xlsx(List.of(
                fila("Codigo", "Descripcion", "Precio"),
                fila("S1" + s, "x", new BigDecimal("120")), fila("S2" + s, "x", new BigDecimal("130")),
                fila("B1" + s, "x", new BigDecimal("80")), fila("C1" + s, "x", new BigDecimal("100")),
                fila("NUEVO" + s, "Nuevo", new BigDecimal("50")), fila("ERR" + s, "x", "abc"))));
        ObjectNode datos = cuerpoViejo(proveedor);

        postMultipart(VISTA_PREVIA, dueno, lista, datos)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conteos.suben", is(2)))
                .andExpect(jsonPath("$.conteos.bajan", is(1)))
                .andExpect(jsonPath("$.conteos.nuevos", is(1)))
                .andExpect(jsonPath("$.conteos.sinCambio", is(1)))
                .andExpect(jsonPath("$.conteos.noEstan", is(1)))
                .andExpect(jsonPath("$.conteos.conError", is(1)))
                .andExpect(jsonPath("$.conteos.cambianMoneda", is(0)))
                .andExpect(jsonPath("$.conteos.corrigen", is(0)))
                .andExpect(jsonPath("$.conteos.salteadas", is(0)));
        postMultipart(APLICAR, dueno, lista, aplicarViejo(datos, 2, 1, 1, 1, 1, 1))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conteos.suben", is(2)))
                .andExpect(jsonPath("$.conteos.nuevos", is(1)));
    }
}
