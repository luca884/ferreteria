package com.ferreteria.catalogo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ferreteria.support.IntegrationTestBase;
import com.ferreteria.support.ListasDePrueba;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.catalogo.repository.ImportacionListaRepository;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Importacion de listas (CU-04) por HTTP contra MySQL real: acceso por rol, aislamiento entre
 * ferreterias, archivos y datos invalidos. A = El Tornillo, B = La Tuerca. La base se comparte y no
 * se revierte: cada test crea su proveedor y sus articulos con codigos unicos.
 */
class ImportacionIntegrationTest extends IntegrationTestBase {

    private static final String MUESTRA = "/api/importaciones/muestra";
    private static final String VISTA_PREVIA = "/api/importaciones/vista-previa";
    private static final String APLICAR = "/api/importaciones";
    private static final List<String> ENDPOINTS = List.of(MUESTRA, VISTA_PREVIA, APLICAR);

    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n', 0, 0, 0, 13};

    private static final String DERIVA = "La lista cambió desde la vista previa. Volvé a generar la vista previa y confirmá de nuevo.";
    private static final List<Object> ENCABEZADO = List.of("Codigo", "Descripcion", "Costo");

    @SpyBean
    ArticuloRepository articuloRepository;

    @SpyBean
    ImportacionListaRepository importacionListaRepository;

    private String duenoA;
    private String duenoB;
    private String vendedorA;

    @BeforeEach
    void tokens() throws Exception {
        duenoA = login("dueno@tornillo.demo");
        duenoB = login("dueno@tuerca.demo");
        vendedorA = login("vendedor@tornillo.demo");
    }

    // --- Helpers ---

    private long crearProveedor(String token, String nombre) throws Exception {
        String body = postJson("/api/proveedores", token, "{\"nombre\":\"" + nombre + "\"}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private long crearRubro(String token, String nombre, String margen) throws Exception {
        String body = postJson("/api/rubros", token,
                "{\"nombre\":\"" + nombre + "\",\"margenPorcentaje\":" + margen + "}")
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    private long crearArticulo(String token, long proveedorId, String codigoProveedor, String costo) throws Exception {
        ObjectNode pedido = objectMapper.createObjectNode()
                .put("codigoInterno", unico("INT").substring(0, 12))
                .put("codigoProveedor", codigoProveedor)
                .put("descripcion", "Articulo " + codigoProveedor)
                .put("unidad", "UNIDAD")
                .put("proveedorId", proveedorId)
                .put("margenPorcentaje", new BigDecimal("35"))
                .put("costo", new BigDecimal(costo));
        String body = postJson("/api/articulos", token, objectMapper.writeValueAsString(pedido))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    /** Mapeo estandar de los tests: codigo en A, descripcion en B, costo en C, datos desde la fila 2. */
    private ObjectNode mapeo() {
        return objectMapper.createObjectNode()
                .put("hoja", 0).put("filaDesde", 2)
                .put("columnaCodigo", 0).put("columnaDescripcion", 1).put("columnaCosto", 2);
    }

    private ObjectNode datosVistaPrevia(long proveedorId) {
        ObjectNode datos = objectMapper.createObjectNode()
                .put("proveedorId", proveedorId)
                .put("listaIncluyeIva", false);
        datos.set("mapeo", mapeo());
        return datos;
    }

    private ObjectNode datosAplicar(long proveedorId, int suben, int bajan, int nuevos, int sinCambio, int noEstan, int conError) {
        ObjectNode datos = datosVistaPrevia(proveedorId);
        datos.put("crearNuevos", false);
        datos.set("conteosEsperados", objectMapper.createObjectNode()
                .put("suben", suben).put("bajan", bajan).put("nuevos", nuevos)
                .put("sinCambio", sinCambio).put("noEstan", noEstan).put("conError", conError));
        return datos;
    }

    private ObjectNode datosAplicarEnCero(long proveedorId) {
        return datosAplicar(proveedorId, 0, 0, 0, 0, 0, 0);
    }

    /** Lista de tres columnas con encabezado: codigo, descripcion, costo. */
    private MockMultipartFile lista(String codigo, String costo) {
        return archivo("lista.xlsx", ListasDePrueba.xlsx(List.of(
                List.<Object>of("Codigo", "Descripcion", "Costo"),
                List.<Object>of(codigo, "Descripcion " + codigo, new BigDecimal(costo)))));
    }

    private ResultActions enviar(String url, String token, MockMultipartFile archivo, ObjectNode vistaPrevia, ObjectNode aplicar) throws Exception {
        if (MUESTRA.equals(url)) {
            return postMultipart(url, token, archivo, null);
        }
        return postMultipart(url, token, archivo, APLICAR.equals(url) ? aplicar : vistaPrevia);
    }

    private ObjectNode datosPara(String url, long proveedorId) {
        return APLICAR.equals(url) ? datosAplicarEnCero(proveedorId) : datosVistaPrevia(proveedorId);
    }

    private long contar(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private Map<String, Object> filaDeArticulo(long id) {
        return jdbc.queryForMap("select * from articulo where id = ?", id);
    }

    // --- Acceso (R-001) ---

    @Test
    void elVendedorRecibe403EnLosTresEndpointsConUnCuerpoValido() throws Exception {
        long proveedor = crearProveedor(duenoA, unico("P"));

        for (String url : ENDPOINTS) {
            postMultipart(url, vendedorA, lista("V-1", "100"), MUESTRA.equals(url) ? null : datosPara(url, proveedor))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void elVendedorRecibe403AntesQueCualquierValidacionDelCuerpo() throws Exception {
        long proveedor = crearProveedor(duenoA, unico("P"));

        for (String url : List.of(VISTA_PREVIA, APLICAR)) {
            ObjectNode conBonificacionInvalida = datosPara(url, proveedor);
            conBonificacionInvalida.put("bonificacion1", new BigDecimal("150"));
            postMultipart(url, vendedorA, lista("V-1", "100"), conBonificacionInvalida)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message", is("No tenes permiso para hacer esto")));

            ObjectNode conMapeoInvalido = datosPara(url, proveedor);
            ((ObjectNode) conMapeoInvalido.get("mapeo")).put("columnaCosto", 0);
            postMultipart(url, vendedorA, lista("V-1", "100"), conMapeoInvalido)
                    .andExpect(status().isForbidden());

            postMultipart(url, vendedorA, lista("V-1", "100"), null).andExpect(status().isForbidden());
            postMultipart(url, vendedorA, archivo("lista.xlsx", PNG), datosPara(url, proveedor))
                    .andExpect(status().isForbidden());
        }
        postMultipart(MUESTRA + "?hoja=9", vendedorA, archivo("lista.csv", PNG), null)
                .andExpect(status().isForbidden());
    }

    @Test
    void sinTokenLosTresEndpointsDan401() throws Exception {
        for (String url : ENDPOINTS) {
            mvc.perform(MockMvcRequestBuilders.multipart(url).file(lista("V-1", "100")))
                    .andExpect(status().isUnauthorized());
        }
    }

    // --- Aislamiento entre ferreterias ---

    @Test
    void unProveedorDeOtraFerreteriaDa404EnVistaPreviaYEnAplicarYNoCambiaNada() throws Exception {
        long proveedorB = crearProveedor(duenoB, unico("PB"));
        String codigo = unico("AJ");
        long articuloB = crearArticulo(duenoB, proveedorB, codigo, "100");
        String mapeoGuardado = "{\"hoja\":0,\"filaDesde\":2,\"columnaCodigo\":0,\"columnaDescripcion\":1,\"columnaCosto\":2}";
        jdbc.update("update proveedor set mapeo_lista = ?, bonificacion1 = 40, lista_incluye_iva = true where id = ?",
                mapeoGuardado, proveedorB);
        jdbc.update("update articulo set precio_lista_proveedor = 150 where id = ?", articuloB);
        Map<String, Object> articuloAntes = filaDeArticulo(articuloB);
        Map<String, Object> proveedorAntes = jdbc.queryForMap("select * from proveedor where id = ?", proveedorB);
        long historialAntes = contar("select count(*) from historial_precio where articulo_id = ?", articuloB);

        postMultipart(VISTA_PREVIA, duenoA, lista(codigo, "500"), datosVistaPrevia(proveedorB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Proveedor no encontrado")));
        ObjectNode aplicar = datosAplicarEnCero(proveedorB);
        aplicar.put("crearNuevos", true);
        postMultipart(APLICAR, duenoA, lista(codigo, "500"), aplicar)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Proveedor no encontrado")));

        assertThat(filaDeArticulo(articuloB)).isEqualTo(articuloAntes);
        assertThat(jdbc.queryForMap("select * from proveedor where id = ?", proveedorB)).isEqualTo(proveedorAntes);
        assertThat(contar("select count(*) from historial_precio where articulo_id = ?", articuloB)).isEqualTo(historialAntes);
        assertThat(contar("select count(*) from importacion_lista where proveedor_id = ?", proveedorB)).isZero();
        assertThat(filaDeArticulo(articuloB).get("costo")).isEqualTo(new BigDecimal("100.00"));
        assertThat((BigDecimal) filaDeArticulo(articuloB).get("precio_lista_proveedor")).isEqualByComparingTo("150");
    }

    @Test
    void unProveedorAjenoDa404AunqueLasBonificacionesSeanInvalidas() throws Exception {
        long proveedorB = crearProveedor(duenoB, unico("PB"));

        for (String url : List.of(VISTA_PREVIA, APLICAR)) {
            ObjectNode datos = datosPara(url, proveedorB);
            datos.put("bonificacion1", new BigDecimal("150"));
            datos.put("bonificacion3", new BigDecimal("10.555"));

            postMultipart(url, duenoA, lista("X-1", "100"), datos)
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message", is("Proveedor no encontrado")));
        }
    }

    @Test
    void conVariasBonificacionesInvalidasLaRespuestaListaCadaUna() throws Exception {
        long proveedor = crearProveedor(duenoA, unico("P"));

        for (String url : List.of(VISTA_PREVIA, APLICAR)) {
            ObjectNode datos = datosPara(url, proveedor);
            datos.put("bonificacion1", new BigDecimal("150"));
            datos.put("bonificacion3", new BigDecimal("10.555"));

            postMultipart(url, duenoA, lista("X-1", "100"), datos)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", is("Datos invalidos")))
                    .andExpect(jsonPath("$.errors.bonificacion1", is("La bonificación 1 debe estar entre 0 y 100.")))
                    .andExpect(jsonPath("$.errors.bonificacion3",
                            is("La bonificación 3 puede tener como máximo 2 decimales.")));
        }
    }

    @Test
    void unRubroDeOtraFerreteriaConCrearNuevosDa404ySinCrearArticulos() throws Exception {
        long proveedorA = crearProveedor(duenoA, unico("PA"));
        long rubroB = crearRubro(duenoB, unico("RB"), "40");
        String codigo = unico("NU");
        ObjectNode datos = datosAplicar(proveedorA, 0, 0, 1, 0, 0, 0);
        datos.put("crearNuevos", true).put("rubroId", rubroB);

        postMultipart(APLICAR, duenoA, lista(codigo, "100"), datos)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Rubro no encontrado")));

        assertThat(contar("select count(*) from articulo where codigo_proveedor = ?", codigo)).isZero();
        assertThat(contar("select count(*) from importacion_lista where proveedor_id = ?", proveedorA)).isZero();
    }

    // --- Archivos invalidos (R-020, R-026) ---

    private List<Object[]> archivosInvalidos() {
        byte[] pdf = "%PDF-1.7\n1 0 obj\n".getBytes(StandardCharsets.US_ASCII);
        byte[] zipRoto = new byte[] {'P', 'K', 3, 4, 9, 9, 9, 9, 9, 9, 9, 9};
        return List.of(
                new Object[] {archivo("lista.xlsx", pdf), "Las listas en PDF todavía no se pueden importar."},
                new Object[] {archivo("lista.csv", PNG), "El archivo no es una lista válida. Subí un Excel (.xlsx o .xls) o un CSV."},
                new Object[] {archivo("lista.xlsx", new byte[0]), "No se pudo leer el archivo. Verificá que no esté dañado."},
                new Object[] {archivo("lista.xlsx", zipRoto), "No se pudo leer el archivo. Verificá que no esté dañado."},
                new Object[] {archivo("lista.xlsx", ListasDePrueba.docx()), "No se pudo leer el archivo. Verificá que no esté dañado."});
    }

    @Test
    void losArchivosInvalidosDan400ConUnMensajeEnLosTresEndpoints() throws Exception {
        long proveedor = crearProveedor(duenoA, unico("P"));

        for (String url : ENDPOINTS) {
            for (Object[] caso : archivosInvalidos()) {
                MockMultipartFile archivo = (MockMultipartFile) caso[0];
                postMultipart(url, duenoA, archivo, MUESTRA.equals(url) ? null : datosPara(url, proveedor))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.message", is(caso[1])));
            }
        }
    }

    @Test
    void sinLaParteArchivoDa400() throws Exception {
        long proveedor = crearProveedor(duenoA, unico("P"));
        for (String url : List.of(VISTA_PREVIA, APLICAR)) {
            MockMultipartFile soloDatos = new MockMultipartFile("datos", "", "application/json",
                    objectMapper.writeValueAsBytes(datosPara(url, proveedor)));
            mvc.perform(MockMvcRequestBuilders.multipart(url).file(soloDatos)
                            .header("Authorization", "Bearer " + duenoA))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", is("Falta el archivo de la lista.")));
        }
    }

    @Test
    void sinLaParteDatosDa400() throws Exception {
        for (String url : List.of(VISTA_PREVIA, APLICAR)) {
            postMultipart(url, duenoA, lista("D-1", "100"), null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", is("Faltan los datos de la importación.")));
        }
    }

    // --- Datos invalidos (R-003, R-033) ---

    @Test
    void elMismoNumeroDeColumnaParaCodigoYCostoDa400() throws Exception {
        long proveedor = crearProveedor(duenoA, unico("P"));
        for (String url : List.of(VISTA_PREVIA, APLICAR)) {
            ObjectNode datos = datosPara(url, proveedor);
            ((ObjectNode) datos.get("mapeo")).put("columnaCosto", 0);

            postMultipart(url, duenoA, lista("M-1", "100"), datos)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors['mapeo.columnasDistintas']",
                            is("Elegí columnas distintas para código, descripción y costo.")));
        }
    }

    @Test
    void unaHojaInexistenteDa400() throws Exception {
        long proveedor = crearProveedor(duenoA, unico("P"));
        for (String url : List.of(VISTA_PREVIA, APLICAR)) {
            ObjectNode datos = datosPara(url, proveedor);
            ((ObjectNode) datos.get("mapeo")).put("hoja", 7);

            postMultipart(url, duenoA, lista("H-1", "100"), datos)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", is("La hoja elegida no existe en el archivo.")));
        }
        postMultipart(MUESTRA + "?hoja=7", duenoA, lista("H-1", "100"), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("La hoja elegida no existe en el archivo.")));
    }

    @Test
    void laMuestraDeUnArchivoConDosHojasListaAmbosNombresYDevuelveLasFilasDeLaHojaPedida() throws Exception {
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put("Lista", List.of(List.<Object>of("Codigo", "Descripcion", "Costo"), List.<Object>of("A-1", "Tornillo", "10")));
        hojas.put("Oferta", List.of(List.<Object>of("Cod", "Detalle"), List.<Object>of("Z-9", "Arandela")));
        MockMultipartFile archivo = archivo("lista.xlsx", ListasDePrueba.xlsxHojas(hojas));

        // Sin el parametro, la hoja 0.
        postMultipart(MUESTRA, duenoA, archivo, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hojas[0]", is("Lista")))
                .andExpect(jsonPath("$.hojas[1]", is("Oferta")))
                .andExpect(jsonPath("$.hoja", is(0)))
                .andExpect(jsonPath("$.filas[1].celdas[0]", is("A-1")));

        // hoja=1: los mismos nombres y las filas de la segunda hoja.
        postMultipart(MUESTRA + "?hoja=1", duenoA, archivo, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hojas[0]", is("Lista")))
                .andExpect(jsonPath("$.hojas[1]", is("Oferta")))
                .andExpect(jsonPath("$.hoja", is(1)))
                .andExpect(jsonPath("$.filas[1].celdas[0]", is("Z-9")));
    }

    // --- Limite de filas (R-025) ---

    private static final String MENSAJE_LIMITE = "La lista tiene más de 20.000 filas. Dividila en archivos más chicos.";

    private MockMultipartFile csvDe(int titulos, int datos) {
        List<List<String>> filas = new ArrayList<>();
        for (int i = 1; i <= titulos; i++) {
            filas.add(List.of("Titulo " + i));
        }
        for (int i = 1; i <= datos; i++) {
            filas.add(List.of("L-" + i, "Descripcion " + i, "10"));
        }
        return archivo("lista.csv", ListasDePrueba.csv(filas, ';', StandardCharsets.UTF_8, false));
    }

    @Test
    void conMasDeVeinteMilFilasDeDatosVistaPreviaYAplicarDan400AunSinEncabezado() throws Exception {
        long proveedor = crearProveedor(duenoA, unico("P"));

        for (String url : List.of(VISTA_PREVIA, APLICAR)) {
            ObjectNode datos = datosPara(url, proveedor);
            ((ObjectNode) datos.get("mapeo")).put("filaDesde", 1);

            postMultipart(url, duenoA, csvDe(0, 20_001), datos)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", is(MENSAJE_LIMITE)));
        }
    }

    @Test
    void conTitulosEncabezadoYVeinteMilFilasDeDatosLaVistaPreviaFunciona() throws Exception {
        long proveedor = crearProveedor(duenoA, unico("P"));
        ObjectNode datos = datosVistaPrevia(proveedor);
        ((ObjectNode) datos.get("mapeo")).put("filaDesde", 5);

        postMultipart(VISTA_PREVIA, duenoA, csvDe(4, 20_000), datos)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conteos.nuevos", is(20_000)))
                .andExpect(jsonPath("$.detalleRecortado", is(true)));
    }

    @Test
    void conMuchasMasDeVeinteMilFilasTambienLaMuestraDa400() throws Exception {
        postMultipart(MUESTRA, duenoA, csvDe(0, 25_000), null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is(MENSAJE_LIMITE)));
    }

    private void bonificacionInvalida(String url, long proveedor, String campo, Object valor, String mensaje) throws Exception {
        ObjectNode datos = datosPara(url, proveedor);
        datos.putPOJO(campo, valor);

        postMultipart(url, duenoA, lista("B-1", "100"), datos)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors." + campo, is(mensaje)));
    }

    @Test
    void lasBonificacionesInvalidasNombranLaBonificacionEnVistaPreviaYEnAplicar() throws Exception {
        long proveedor = crearProveedor(duenoA, unico("P"));

        for (String url : List.of(VISTA_PREVIA, APLICAR)) {
            bonificacionInvalida(url, proveedor, "bonificacion1", new BigDecimal("101"), "La bonificación 1 debe estar entre 0 y 100.");
            bonificacionInvalida(url, proveedor, "bonificacion2", new BigDecimal("-5"), "La bonificación 2 debe estar entre 0 y 100.");
            bonificacionInvalida(url, proveedor, "bonificacion3", "abc", "La bonificación 3 no es un número válido.");
            bonificacionInvalida(url, proveedor, "bonificacion1", new BigDecimal("10.555"), "La bonificación 1 puede tener como máximo 2 decimales.");
        }
    }

    @Test
    void unaBonificacionConDosViolacionesDaSiempreElMensajeDeRango() throws Exception {
        long proveedor = crearProveedor(duenoA, unico("P"));

        for (int i = 0; i < 5; i++) {
            bonificacionInvalida(VISTA_PREVIA, proveedor, "bonificacion1", new BigDecimal("100.555"),
                    "La bonificación 1 debe estar entre 0 y 100.");
        }
    }

    @Test
    void unaBonificacionTipoTextoEnElJsonTambienDa400() throws Exception {
        long proveedor = crearProveedor(duenoA, unico("P"));
        byte[] datos = ("{\"proveedorId\":" + proveedor + ",\"mapeo\":" + objectMapper.writeValueAsString(mapeo())
                + ",\"bonificacion2\":\"diez\"}").getBytes(StandardCharsets.UTF_8);

        postMultipart(VISTA_PREVIA, duenoA, lista("B-1", "100"), datos)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("La bonificación 2 no es un número válido.")));
    }

    @Test
    void aplicarConUnaBonificacionDe150NoCambiaLaGuardadaNiLosArticulos() throws Exception {
        long proveedor = crearProveedor(duenoA, unico("P"));
        String codigo = unico("BO");
        long articulo = crearArticulo(duenoA, proveedor, codigo, "100");
        jdbc.update("update proveedor set bonificacion1 = 40 where id = ?", proveedor);
        Map<String, Object> antes = filaDeArticulo(articulo);
        ObjectNode datos = datosAplicar(proveedor, 1, 0, 0, 0, 0, 0);
        datos.put("bonificacion1", new BigDecimal("150"));

        postMultipart(APLICAR, duenoA, lista(codigo, "500"), datos)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.bonificacion1", is("La bonificación 1 debe estar entre 0 y 100.")));

        JsonNode proveedorJson = getJson("/api/proveedores/" + proveedor, duenoA);
        assertThat(proveedorJson.get("bonificacion1").decimalValue()).isEqualByComparingTo("40.00");
        assertThat(filaDeArticulo(articulo)).isEqualTo(antes);
        assertThat(contar("select count(*) from importacion_lista where proveedor_id = ?", proveedor)).isZero();
    }

    // =====================================================================================
    // Caminos felices y consistencia (T-33)
    // =====================================================================================

    private static List<Object> fila(Object... celdas) {
        return java.util.Arrays.asList(celdas);
    }

    private static List<List<Object>> conEncabezado(List<Object>... filas) {
        List<List<Object>> todas = new ArrayList<>();
        todas.add(ENCABEZADO);
        todas.addAll(List.of(filas));
        return todas;
    }

    private static BigDecimal n(String valor) {
        return new BigDecimal(valor);
    }

    private MockMultipartFile xlsx(List<List<Object>> filas) {
        return archivo("lista.xlsx", ListasDePrueba.xlsx(filas));
    }

    private JsonNode vistaPrevia(MockMultipartFile archivo, ObjectNode datos) throws Exception {
        String cuerpo = postMultipart(VISTA_PREVIA, duenoA, archivo, datos)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(cuerpo);
    }

    /** Aplica con los contadores de la vista previa de los mismos datos (lo que hace la pantalla). */
    private ResultActions aplicar(MockMultipartFile archivo, ObjectNode datosVistaPrevia, boolean crearNuevos, Long rubroId)
            throws Exception {
        JsonNode previa = vistaPrevia(archivo, datosVistaPrevia);
        return postMultipart(APLICAR, duenoA, archivo, datosDeAplicar(datosVistaPrevia, previa.get("conteos"), crearNuevos, rubroId));
    }

    private ObjectNode datosDeAplicar(ObjectNode datosVistaPrevia, JsonNode conteos, boolean crearNuevos, Long rubroId) {
        ObjectNode datos = datosVistaPrevia.deepCopy();
        datos.put("crearNuevos", crearNuevos);
        if (rubroId != null) {
            datos.put("rubroId", rubroId);
        }
        datos.set("conteosEsperados", conteos);
        return datos;
    }

    private Map<String, Object> instantanea(long proveedorId) {
        Map<String, Object> estado = new LinkedHashMap<>();
        estado.put("articulos", jdbc.queryForList("select * from articulo where proveedor_id = ? order by id", proveedorId));
        estado.put("proveedor", jdbc.queryForMap("select * from proveedor where id = ?", proveedorId));
        estado.put("historial", contar("select count(*) from historial_precio where articulo_id in"
                + " (select id from articulo where proveedor_id = ?)", proveedorId));
        estado.put("importaciones", contar("select count(*) from importacion_lista where proveedor_id = ?", proveedorId));
        return estado;
    }

    private long importaciones(long proveedorId) {
        return contar("select count(*) from historial_precio where origen = 'IMPORTACION' and articulo_id in"
                + " (select id from articulo where proveedor_id = ?)", proveedorId);
    }

    private BigDecimal decimal(long articuloId, String columna) {
        return jdbc.queryForObject("select " + columna + " from articulo where id = ?", BigDecimal.class, articuloId);
    }

    private static void assertConteos(JsonNode conteos, int suben, int bajan, int nuevos, int sinCambio, int noEstan, int conError) {
        assertThat(conteos.get("suben").asInt()).as("suben").isEqualTo(suben);
        assertThat(conteos.get("bajan").asInt()).as("bajan").isEqualTo(bajan);
        assertThat(conteos.get("nuevos").asInt()).as("nuevos").isEqualTo(nuevos);
        assertThat(conteos.get("sinCambio").asInt()).as("sinCambio").isEqualTo(sinCambio);
        assertThat(conteos.get("noEstan").asInt()).as("noEstan").isEqualTo(noEstan);
        assertThat(conteos.get("conError").asInt()).as("conError").isEqualTo(conError);
    }

    /** Diez filas: 4 suben, 2 bajan, 1 sin cambio, 2 nuevas, 1 con error; y 3 articulos que no estan. */
    private record Escenario(long proveedor, String sufijo, Map<String, Long> ids, List<List<Object>> filas) {
        long id(String clave) {
            return ids.get(clave);
        }
    }

    private Escenario escenarioDeDiez() throws Exception {
        long proveedor = crearProveedor(duenoA, unico("P"));
        String sufijo = unico("");
        Map<String, Long> ids = new LinkedHashMap<>();
        for (String clave : List.of("S1", "S2", "S3", "S4", "B1", "B2", "C1", "N1", "N2", "N3")) {
            ids.put(clave, crearArticulo(duenoA, proveedor, clave + sufijo, "100"));
        }
        List<List<Object>> filas = new ArrayList<>();
        filas.add(ENCABEZADO);
        for (String clave : List.of("S1", "S2", "S3", "S4")) {
            filas.add(fila(clave + sufijo, "Lista " + clave, n("120")));
        }
        for (String clave : List.of("B1", "B2")) {
            filas.add(fila(clave + sufijo, "Lista " + clave, n("80")));
        }
        filas.add(fila("C1" + sufijo, "Lista C1", n("100")));
        filas.add(fila("X1" + sufijo, "Nuevo uno", n("50")));
        filas.add(fila("X2" + sufijo, "Nuevo dos", n("60")));
        filas.add(fila("ERR" + sufijo, "Con error", "abc"));
        return new Escenario(proveedor, sufijo, ids, filas);
    }

    @Test
    void xlsxXlsYCsvConElMismoContenidoDanLaMismaVistaPrevia() throws Exception {
        Escenario e = escenarioDeDiez();
        List<List<String>> filasCsv = new ArrayList<>();
        e.filas().forEach(f -> filasCsv.add(f.stream().map(Object::toString).toList()));
        ObjectNode datos = datosVistaPrevia(e.proveedor());

        JsonNode xlsx = vistaPrevia(xlsx(e.filas()), datos);
        JsonNode xls = vistaPrevia(archivo("lista.xls", ListasDePrueba.xls(e.filas())), datos);
        JsonNode csv = vistaPrevia(archivo("lista.csv",
                ListasDePrueba.csv(filasCsv, ';', StandardCharsets.UTF_8, false)), datos);

        assertConteos(xlsx.get("conteos"), 4, 2, 2, 1, 3, 1);
        assertThat(resumen(xls)).isEqualTo(resumen(xlsx));
        assertThat(resumen(csv)).isEqualTo(resumen(xlsx));
    }

    /** Lo que importa de una vista previa, sin depender de la escala de los numeros del JSON. */
    private Map<String, Object> resumen(JsonNode previa) {
        Map<String, Object> resumen = new LinkedHashMap<>();
        resumen.put("conteos", previa.get("conteos"));
        List<String> cambios = new ArrayList<>();
        previa.get("cambios").forEach(c -> cambios.add(c.get("fila").asInt() + "|" + c.get("codigoProveedor").asText()
                + "|" + c.get("costoAnterior").decimalValue().stripTrailingZeros().toPlainString()
                + "|" + c.get("costoNuevo").decimalValue().stripTrailingZeros().toPlainString()
                + "|" + c.get("precioNuevo").decimalValue().stripTrailingZeros().toPlainString()));
        resumen.put("cambios", cambios);
        List<String> nuevos = new ArrayList<>();
        previa.get("nuevos").forEach(a -> nuevos.add(a.get("fila").asInt() + "|" + a.get("codigo").asText()
                + "|" + a.get("costo").decimalValue().stripTrailingZeros().toPlainString()));
        resumen.put("nuevos", nuevos);
        List<String> errores = new ArrayList<>();
        previa.get("errores").forEach(a -> errores.add(a.get("fila").asInt() + "|" + a.get("mensaje").asText()));
        resumen.put("errores", errores);
        List<String> ausentes = new ArrayList<>();
        previa.get("noEstan").forEach(a -> ausentes.add(a.get("codigoProveedor").asText()));
        resumen.put("noEstan", ausentes);
        return resumen;
    }

    @Test
    void laVistaPreviaCuentaBienYNoEscribeNada() throws Exception {
        Escenario e = escenarioDeDiez();
        Map<String, Object> antes = instantanea(e.proveedor());

        JsonNode previa = vistaPrevia(xlsx(e.filas()), datosVistaPrevia(e.proveedor()));

        assertConteos(previa.get("conteos"), 4, 2, 2, 1, 3, 1);
        assertThat(previa.get("cambios")).hasSize(6);
        assertThat(previa.get("nuevos")).hasSize(2);
        assertThat(previa.get("errores")).hasSize(1);
        assertThat(previa.get("errores").get(0).get("mensaje").asText()).isEqualTo("El costo está vacío o no es un número.");
        assertThat(previa.get("noEstan")).hasSize(3);
        assertThat(instantanea(e.proveedor())).isEqualTo(antes);
    }

    @Test
    void laVistaPreviaConBonificacionesNoGuardaBonificacionesNiMapeoNiPrecioDeLista() throws Exception {
        Escenario e = escenarioDeDiez();
        Map<String, Object> antes = instantanea(e.proveedor());
        ObjectNode datos = datosVistaPrevia(e.proveedor());
        datos.put("listaIncluyeIva", true).put("bonificacion1", n("40")).put("bonificacion2", n("10"));

        JsonNode previa = vistaPrevia(xlsx(e.filas()), datos);

        assertThat(previa.get("cambios").size() + previa.get("nuevos").size()).isPositive();
        assertThat(instantanea(e.proveedor())).isEqualTo(antes);
        JsonNode proveedor = getJson("/api/proveedores/" + e.proveedor(), duenoA);
        assertThat(sinValor(proveedor, "mapeoLista")).isTrue();
        assertThat(proveedor.get("listaIncluyeIva").asBoolean()).isFalse();
        assertThat(sinValor(proveedor, "bonificacion1")).isTrue();
        assertThat(decimal(e.id("S1"), "precio_lista_proveedor")).isNull();
    }

    @Test
    void aplicarActualizaCostosPreciosHistorialYRegistraLaImportacion() throws Exception {
        Escenario e = escenarioDeDiez();
        long usuario = contar("select id from usuario where email = ?", "dueno@tornillo.demo");

        String cuerpo = aplicar(xlsx(e.filas()), datosVistaPrevia(e.proveedor()), false, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.estado", is("APLICADA")))
                .andExpect(jsonPath("$.archivo", is("lista.xlsx")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        JsonNode respuesta = objectMapper.readTree(cuerpo);
        assertConteos(respuesta.get("conteos"), 4, 2, 0, 1, 3, 1);
        for (String clave : List.of("S1", "S2", "S3", "S4")) {
            assertThat(decimal(e.id(clave), "costo")).isEqualByComparingTo("120");
            assertThat(decimal(e.id(clave), "precio_final")).isEqualByComparingTo("200");
            assertThat(decimal(e.id(clave), "precio_lista_proveedor")).isEqualByComparingTo("120");
            assertThat(filaDeArticulo(e.id(clave)).get("descripcion")).isEqualTo("Articulo " + clave + e.sufijo());
        }
        for (String clave : List.of("B1", "B2")) {
            assertThat(decimal(e.id(clave), "costo")).isEqualByComparingTo("80");
            assertThat(decimal(e.id(clave), "precio_final")).isEqualByComparingTo("140");
        }
        assertThat(decimal(e.id("C1"), "costo")).isEqualByComparingTo("100");
        assertThat(decimal(e.id("C1"), "precio_final")).isEqualByComparingTo("170");
        assertThat(decimal(e.id("N1"), "costo")).isEqualByComparingTo("100");
        assertThat(decimal(e.id("N1"), "precio_lista_proveedor")).isNull();
        assertThat(filaDeArticulo(e.id("S1")).get("stock_actual")).isEqualTo(new BigDecimal("0.000"));
        assertThat(importaciones(e.proveedor())).isEqualTo(6);
        Map<String, Object> historial = jdbc.queryForMap("select * from historial_precio where origen = 'IMPORTACION'"
                + " and articulo_id = ?", e.id("S1"));
        assertThat((BigDecimal) historial.get("costo_anterior")).isEqualByComparingTo("100");
        assertThat((BigDecimal) historial.get("costo_nuevo")).isEqualByComparingTo("120");
        assertThat((BigDecimal) historial.get("precio_anterior")).isEqualByComparingTo("170");
        assertThat((BigDecimal) historial.get("precio_nuevo")).isEqualByComparingTo("200");
        assertThat(((Number) historial.get("usuario_id")).longValue()).isEqualTo(usuario);
        assertThat(contar("select count(*) from articulo where codigo_proveedor like 'X%' and proveedor_id = ?", e.proveedor())).isZero();
        Map<String, Object> registro = jdbc.queryForMap("select * from importacion_lista where proveedor_id = ?", e.proveedor());
        assertThat(registro).containsEntry("suben", 4).containsEntry("bajan", 2).containsEntry("nuevos", 0)
                .containsEntry("sin_cambio", 1).containsEntry("no_estan", 3).containsEntry("con_error", 1)
                .containsEntry("estado", "APLICADA").containsEntry("archivo", "lista.xlsx");
        assertThat(((Number) registro.get("usuario_id")).longValue()).isEqualTo(usuario);
        assertThat(respuesta.get("id").asLong()).isEqualTo(((Number) registro.get("id")).longValue());
    }

    @Test
    void conIvaIncluidoYBonificacionesElCostoEsSinIvaYBonificado() throws Exception {
        long proveedor = crearProveedor(duenoA, unico("P"));
        String codigo = unico("IVA");
        long articulo = crearArticulo(duenoA, proveedor, codigo, "100");
        ObjectNode datos = datosVistaPrevia(proveedor);
        datos.put("listaIncluyeIva", true).put("bonificacion1", n("40"));

        aplicar(xlsx(conEncabezado(fila(codigo, "Lista", n("1210")))), datos, false, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conteos.suben", is(1)));

        assertThat(decimal(articulo, "costo")).isEqualByComparingTo("600.00");
        assertThat(decimal(articulo, "precio_lista_proveedor")).isEqualByComparingTo("1210.00");
    }

    @Test
    void unaFilaSinCambioFijaSoloElPrecioDeListaYLasInvalidasNoTocanElArticulo() throws Exception {
        long proveedor = crearProveedor(duenoA, unico("P"));
        String s = unico("");
        long inactivo = crearArticulo(duenoA, proveedor, "IN" + s, "100");
        long sinCambio = crearArticulo(duenoA, proveedor, "SC" + s, "100");
        long conCostoCero = crearArticulo(duenoA, proveedor, "Z" + s, "100");
        long repetido = crearArticulo(duenoA, proveedor, "D" + s, "100");
        jdbc.update("update articulo set activo = false where id = ?", inactivo);

        aplicar(xlsx(conEncabezado(
                        fila("IN" + s, "x", n("120")),
                        fila("SC" + s, "x", n("100")),
                        fila("Z" + s, "x", n("0")),
                        fila("D" + s, "x", n("120")),
                        fila("D" + s, "x", n("130")))),
                datosVistaPrevia(proveedor), false, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conteos.suben", is(1)))
                .andExpect(jsonPath("$.conteos.sinCambio", is(1)))
                .andExpect(jsonPath("$.conteos.conError", is(3)))
                .andExpect(jsonPath("$.conteos.noEstan", is(0)));

        assertThat(filaDeArticulo(inactivo).get("activo")).isEqualTo(false);
        assertThat(decimal(inactivo, "costo")).isEqualByComparingTo("120");
        assertThat(decimal(sinCambio, "costo")).isEqualByComparingTo("100");
        assertThat(decimal(sinCambio, "precio_lista_proveedor")).isEqualByComparingTo("100");
        assertThat(contar("select count(*) from historial_precio where origen = 'IMPORTACION' and articulo_id = ?", sinCambio)).isZero();
        for (long intacto : List.of(conCostoCero, repetido)) {
            assertThat(decimal(intacto, "costo")).isEqualByComparingTo("100");
            assertThat(decimal(intacto, "precio_lista_proveedor")).isNull();
        }
    }

    @Test
    void crearNuevosConRubroCreaSoloLaFilaCreable() throws Exception {
        long proveedor = crearProveedor(duenoA, unico("P"));
        long rubro = crearRubro(duenoA, unico("R"), "40");
        String codigo = unico("NUEVO");
        String demasiadoLargo = "Z".repeat(35);

        aplicar(xlsx(conEncabezado(fila(codigo, "Tornillo nuevo", n("100")), fila(demasiadoLargo, "No creable", n("100")))),
                datosVistaPrevia(proveedor), true, rubro)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conteos.nuevos", is(1)))
                .andExpect(jsonPath("$.conteos.conError", is(1)));

        Map<String, Object> creado = jdbc.queryForMap("select * from articulo where codigo_interno = ?", codigo);
        assertThat(creado).containsEntry("codigo_proveedor", codigo).containsEntry("descripcion", "Tornillo nuevo")
                .containsEntry("unidad", "UNIDAD").containsEntry("alicuota_iva", "IVA_21").containsEntry("activo", true);
        assertThat(((Number) creado.get("rubro_id")).longValue()).isEqualTo(rubro);
        assertThat(((Number) creado.get("proveedor_id")).longValue()).isEqualTo(proveedor);
        assertThat(creado.get("margen_porcentaje")).isNull();
        assertThat((BigDecimal) creado.get("costo")).isEqualByComparingTo("100");
        assertThat((BigDecimal) creado.get("precio_final")).isEqualByComparingTo("170");
        assertThat((BigDecimal) creado.get("precio_lista_proveedor")).isEqualByComparingTo("100");
        assertThat((BigDecimal) creado.get("stock_minimo")).isEqualByComparingTo("0");
        assertThat(contar("select count(*) from articulo where proveedor_id = ?", proveedor)).isEqualTo(1);
        Map<String, Object> historial = jdbc.queryForMap("select * from historial_precio where articulo_id = ?", creado.get("id"));
        assertThat(historial.get("costo_anterior")).isNull();
        assertThat(historial.get("precio_anterior")).isNull();
        assertThat(historial).containsEntry("origen", "IMPORTACION");
        assertThat(jdbc.queryForObject("select nuevos from importacion_lista where proveedor_id = ?", Integer.class, proveedor)).isEqualTo(1);
    }

    @Test
    void crearNuevosSinRubroUsaElMargenPorDefectoDeLaFerreteria() throws Exception {
        long proveedor = crearProveedor(duenoA, unico("P"));
        String codigo = unico("NSR");

        aplicar(xlsx(conEncabezado(fila(codigo, "Sin rubro", n("100")))), datosVistaPrevia(proveedor), true, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conteos.nuevos", is(1)));

        Map<String, Object> creado = jdbc.queryForMap("select * from articulo where codigo_interno = ?", codigo);
        assertThat(creado.get("rubro_id")).isNull();
        assertThat((BigDecimal) creado.get("precio_final")).isEqualByComparingTo("160");
    }

    @Test
    void sinCrearNuevosLasFilasNuevasSoloSeCuentan() throws Exception {
        long proveedor = crearProveedor(duenoA, unico("P"));
        String codigo = unico("SOLO");

        aplicar(xlsx(conEncabezado(fila(codigo, "Solo contada", n("100")))), datosVistaPrevia(proveedor), false, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conteos.nuevos", is(0)));

        assertThat(contar("select count(*) from articulo where codigo_interno = ?", codigo)).isZero();
    }

    // --- Deriva entre la vista previa y la confirmacion ---

    @Test
    void siSeCargaUnArticuloEntreLaVistaPreviaYAplicarDa409YNoCambiaNada() throws Exception {
        long proveedor = crearProveedor(duenoA, unico("P"));
        String codigo = unico("DER");
        crearArticulo(duenoA, proveedor, codigo, "100");
        MockMultipartFile archivo = xlsx(conEncabezado(fila(codigo, "x", n("120"))));
        ObjectNode datos = datosVistaPrevia(proveedor);
        JsonNode previa = vistaPrevia(archivo, datos);
        crearArticulo(duenoA, proveedor, unico("MANUAL"), "100");
        Map<String, Object> antes = instantanea(proveedor);

        postMultipart(APLICAR, duenoA, archivo, datosDeAplicar(datos, previa.get("conteos"), false, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is(DERIVA)));

        assertThat(instantanea(proveedor)).isEqualTo(antes);
    }

    @Test
    void siSeConfirmaConOtroArchivoDa409YNoCambiaNada() throws Exception {
        long proveedor = crearProveedor(duenoA, unico("P"));
        String codigo = unico("OTRO");
        crearArticulo(duenoA, proveedor, codigo, "100");
        ObjectNode datos = datosVistaPrevia(proveedor);
        JsonNode previa = vistaPrevia(xlsx(conEncabezado(fila(codigo, "x", n("120")))), datos);
        Map<String, Object> antes = instantanea(proveedor);

        postMultipart(APLICAR, duenoA, xlsx(conEncabezado(fila(codigo, "x", n("100")))),
                datosDeAplicar(datos, previa.get("conteos"), false, null))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is(DERIVA)));

        assertThat(instantanea(proveedor)).isEqualTo(antes);
    }

    @Test
    void siCambianLasBonificacionesEntreLaVistaPreviaYAplicarDa409SinGuardarlas() throws Exception {
        long proveedor = crearProveedor(duenoA, unico("P"));
        String codigo = unico("BON");
        crearArticulo(duenoA, proveedor, codigo, "100");
        MockMultipartFile archivo = xlsx(conEncabezado(fila(codigo, "x", n("100"))));
        ObjectNode datos = datosVistaPrevia(proveedor);
        JsonNode previa = vistaPrevia(archivo, datos);
        assertConteos(previa.get("conteos"), 0, 0, 0, 1, 0, 0);
        Map<String, Object> antes = instantanea(proveedor);
        ObjectNode otras = datosDeAplicar(datos, previa.get("conteos"), false, null);
        otras.put("bonificacion1", n("60"));

        postMultipart(APLICAR, duenoA, archivo, otras)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is(DERIVA)));

        assertThat(instantanea(proveedor)).isEqualTo(antes);
        assertThat(sinValor(getJson("/api/proveedores/" + proveedor, duenoA), "bonificacion1")).isTrue();
    }

    // --- Lo que se guarda en el proveedor (R-019, R-028) ---

    @Test
    void trasAplicarElProveedorMuestraMapeoIvaYBonificacionesYUnaSegundaImportacionLosReemplaza() throws Exception {
        long proveedor = crearProveedor(duenoA, unico("P"));
        long otro = crearProveedor(duenoA, unico("OTRO"));
        String codigo = unico("MAP");
        crearArticulo(duenoA, proveedor, codigo, "100");
        ObjectNode primera = datosVistaPrevia(proveedor);
        primera.put("listaIncluyeIva", true).put("bonificacion1", n("40")).put("bonificacion2", n("10"));

        aplicar(xlsx(conEncabezado(fila(codigo, "Lista", n("1210")))), primera, false, null).andExpect(status().isCreated());

        JsonNode guardado = getJson("/api/proveedores/" + proveedor, duenoA);
        JsonNode mapeo = guardado.get("mapeoLista");
        assertThat(mapeo.get("hoja").asInt()).isZero();
        assertThat(mapeo.get("filaDesde").asInt()).isEqualTo(2);
        assertThat(mapeo.get("columnaCodigo").asInt()).isZero();
        assertThat(mapeo.get("columnaDescripcion").asInt()).isEqualTo(1);
        assertThat(mapeo.get("columnaCosto").asInt()).isEqualTo(2);
        assertThat(mapeo.get("encabezados").get("codigo").asText()).isEqualTo("Codigo");
        assertThat(mapeo.get("encabezados").get("costo").asText()).isEqualTo("Costo");
        assertThat(guardado.get("listaIncluyeIva").asBoolean()).isTrue();
        assertThat(guardado.get("bonificacion1").decimalValue()).isEqualByComparingTo("40.00");
        assertThat(guardado.get("bonificacion2").decimalValue()).isEqualByComparingTo("10.00");
        assertThat(sinValor(guardado, "bonificacion3")).isTrue();
        JsonNode ajeno = getJson("/api/proveedores/" + otro, duenoA);
        assertThat(sinValor(ajeno, "mapeoLista")).isTrue();
        assertThat(ajeno.get("listaIncluyeIva").asBoolean()).isFalse();
        assertThat(sinValor(ajeno, "bonificacion1")).isTrue();

        // Segunda importacion: otro orden de columnas, sin encabezado, sin IVA ni bonificaciones.
        ObjectNode segunda = datosVistaPrevia(proveedor);
        ObjectNode mapeoNuevo = (ObjectNode) segunda.get("mapeo");
        mapeoNuevo.put("filaDesde", 1).put("columnaCodigo", 1).put("columnaDescripcion", 0).put("columnaCosto", 2);
        List<List<Object>> sinEncabezado = List.of(fila("Lista", codigo, n("130")));

        aplicar(xlsx(sinEncabezado), segunda, false, null).andExpect(status().isCreated());

        JsonNode reemplazado = getJson("/api/proveedores/" + proveedor, duenoA);
        assertThat(reemplazado.get("mapeoLista").get("filaDesde").asInt()).isEqualTo(1);
        assertThat(reemplazado.get("mapeoLista").get("columnaCodigo").asInt()).isEqualTo(1);
        assertThat(sinValor(reemplazado.get("mapeoLista"), "encabezados")).isTrue();
        assertThat(reemplazado.get("listaIncluyeIva").asBoolean()).isFalse();
        assertThat(sinValor(reemplazado, "bonificacion1")).isTrue();
        assertThat(sinValor(reemplazado, "bonificacion2")).isTrue();
        assertThat(sinValor(reemplazado, "bonificacion3")).isTrue();
    }

    @Test
    void conBonificacionDe100TodasLasFilasSonErrorYAplicarConfirmaConLosContadores() throws Exception {
        long proveedor = crearProveedor(duenoA, unico("P"));
        String s = unico("");
        long a = crearArticulo(duenoA, proveedor, "A" + s, "100");
        crearArticulo(duenoA, proveedor, "B" + s, "100");
        ObjectNode datos = datosVistaPrevia(proveedor);
        datos.put("bonificacion1", n("100"));

        aplicar(xlsx(conEncabezado(fila("A" + s, "x", n("200")), fila("B" + s, "x", n("300")))), datos, false, null)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conteos.conError", is(2)))
                .andExpect(jsonPath("$.conteos.suben", is(0)))
                .andExpect(jsonPath("$.conteos.bajan", is(0)))
                .andExpect(jsonPath("$.conteos.noEstan", is(0)));

        assertThat(decimal(a, "costo")).isEqualByComparingTo("100");
        assertThat(importaciones(proveedor)).isZero();
    }

    @Test
    void unPutPosteriorDeCU03ConservaElPrecioDeLista() throws Exception {
        long proveedor = crearProveedor(duenoA, unico("P"));
        String codigo = unico("PUT");
        long articulo = crearArticulo(duenoA, proveedor, codigo, "100");
        ObjectNode datos = datosVistaPrevia(proveedor);
        datos.put("listaIncluyeIva", true);
        aplicar(xlsx(conEncabezado(fila(codigo, "x", n("1210")))), datos, false, null).andExpect(status().isCreated());
        assertThat(decimal(articulo, "precio_lista_proveedor")).isEqualByComparingTo("1210");
        JsonNode actual = getJson("/api/articulos/" + articulo, duenoA);
        ObjectNode pedido = objectMapper.createObjectNode()
                .put("codigoInterno", actual.get("codigoInterno").asText())
                .put("codigoProveedor", codigo)
                .put("descripcion", "Editado a mano")
                .put("unidad", "UNIDAD")
                .put("proveedorId", proveedor)
                .put("costo", n("333"))
                .put("precioListaProveedor", n("1"));

        putJson("/api/articulos/" + articulo, duenoA, objectMapper.writeValueAsString(pedido)).andExpect(status().isOk());

        assertThat(decimal(articulo, "costo")).isEqualByComparingTo("333");
        assertThat(decimal(articulo, "precio_lista_proveedor")).isEqualByComparingTo("1210");
    }

    // --- Atomicidad (R-017) ---

    @Test
    void siFallaElRegistroDeLaImportacionSeRevierteTodo() throws Exception {
        Escenario e = escenarioDeDiez();
        jdbc.update("update proveedor set bonificacion1 = 40 where id = ?", e.proveedor());
        Map<String, Object> antes = instantanea(e.proveedor());
        long articulosAntes = contar("select count(*) from articulo");
        ObjectNode datos = datosVistaPrevia(e.proveedor());
        datos.put("bonificacion1", n("40"));
        MockMultipartFile archivo = xlsx(e.filas());
        JsonNode previa = vistaPrevia(archivo, datos);
        doThrow(new IllegalStateException("falla simulada")).when(importacionListaRepository).save(any());

        postMultipart(APLICAR, duenoA, archivo, datosDeAplicar(datos, previa.get("conteos"), true, null))
                .andExpect(status().isInternalServerError());

        assertThat(instantanea(e.proveedor())).isEqualTo(antes);
        assertThat(contar("select count(*) from articulo")).isEqualTo(articulosAntes);
        assertThat(importaciones(e.proveedor())).isZero();
    }

    @Test
    void siFallaElAltaDeArticulosNuevosNoQuedaNadaAplicado() throws Exception {
        Escenario e = escenarioDeDiez();
        Map<String, Object> antes = instantanea(e.proveedor());
        long articulosAntes = contar("select count(*) from articulo");
        ObjectNode datos = datosVistaPrevia(e.proveedor());
        MockMultipartFile archivo = xlsx(e.filas());
        JsonNode previa = vistaPrevia(archivo, datos);
        doThrow(new IllegalStateException("falla simulada")).when(articuloRepository).saveAll(any());

        postMultipart(APLICAR, duenoA, archivo, datosDeAplicar(datos, previa.get("conteos"), true, null))
                .andExpect(status().isInternalServerError());

        assertThat(instantanea(e.proveedor())).isEqualTo(antes);
        assertThat(contar("select count(*) from articulo")).isEqualTo(articulosAntes);
    }

    private static boolean sinValor(JsonNode nodo, String campo) {
        JsonNode valor = nodo.path(campo);
        return valor.isMissingNode() || valor.isNull();
    }
}
