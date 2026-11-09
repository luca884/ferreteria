package com.ferreteria.catalogo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ferreteria.catalogo.service.lectura.LectorDeListas;
import com.ferreteria.cotizacion.CotizacionTestBase;
import com.ferreteria.cotizacion.model.LadoCotizacion;
import com.ferreteria.support.ListasDePrueba;
import com.ferreteria.support.ListasReales;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.time.Instant;
import com.ferreteria.catalogo.repository.ArticuloRepository;
import com.ferreteria.catalogo.repository.HistorialPrecioRepository;
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
class ImportacionIntegrationTest extends CotizacionTestBase {

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

    @SpyBean
    LectorDeListas lector;

    @SpyBean
    HistorialPrecioRepository historialPrecioRepository;

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
                    .andExpect(jsonPath("$.message", is("No tenés permiso para hacer esto")));

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
                    .andExpect(jsonPath("$.message", is("Datos inválidos")))
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
                new Object[] {archivo("lista.xlsx", pdf), "No se pudo leer el archivo. Verificá que no esté dañado."},
                new Object[] {archivo("lista.pdf", ListasDePrueba.pdfSinTexto()),
                    "El PDF no tiene texto para leer. Pedile al proveedor la lista en Excel o en un PDF que no sea escaneado."},
                new Object[] {archivo("lista.csv", PNG), "El archivo no es una lista válida. Subí un Excel (.xlsx o .xls), un CSV o un PDF."},
                new Object[] {archivo("lista.xlsx", new byte[0]), "No se pudo leer el archivo. Verificá que no esté dañado."},
                new Object[] {archivo("lista.xlsx", zipRoto), "No se pudo leer el archivo. Verificá que no esté dañado."},
                new Object[] {archivo("lista.xlsx", ListasDePrueba.docx()), "No se pudo leer el archivo. Verificá que no esté dañado."});
    }

    @Test
    @DisplayName("la exportación de Artículos (CSV o guardada como Excel) da 400 con el aviso en los tres endpoints, sin escribir nada")
    void laExportacionDeArticulosSeRechazaEnLosTresEndpoints() throws Exception {
        long proveedor = crearProveedor(duenoA, unico("P"));
        byte[] exportado = getResult("/api/articulos/exportacion", duenoA)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        List<Object> encabezado = List.of("Código", "Código de barras", "Código del proveedor", "Descripción", "Rubro",
                "Proveedor", "Unidad", "Moneda", "Costo", "IVA %", "Margen %", "Precio final", "Precio manual", "Stock",
                "Stock mínimo", "Ubicación", "Activo");
        byte[] comoExcel = ListasDePrueba.xlsx(List.of(encabezado, List.of("A-1", "", "P-1", "Tornillo", "", "", "Unidad",
                "ARS", 100, 21, "Heredado", 150, "No", 10, 0, "", "Sí")));
        String aviso = "Este archivo es una exportación de Artículos, no una lista de un proveedor: su costo ya trae "
                + "las bonificaciones y los ajustes, y se aplicarían otra vez. Para cambiar precios en masa usá "
                + "Actualizar precios.";

        for (MockMultipartFile archivo : List.of(archivo("articulos-2026-11-05.csv", exportado),
                archivo("articulos.xlsx", comoExcel))) {
            for (String url : ENDPOINTS) {
                postMultipart(url, duenoA, archivo, MUESTRA.equals(url) ? null : datosPara(url, proveedor))
                        .andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.message", is(aviso)));
            }
        }
        assertThat(contar("select count(*) from importacion_lista where proveedor_id = ?", proveedor)).isZero();
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
    }

    @Test
    void laMuestraDeUnArchivoConDosHojasDevuelveLasDosConSusFilas() throws Exception {
        Map<String, List<List<Object>>> hojas = new LinkedHashMap<>();
        hojas.put("Lista", List.of(List.<Object>of("Codigo", "Descripcion", "Costo"), List.<Object>of("A-1", "Tornillo", "10")));
        hojas.put("Oferta", List.of(List.<Object>of("Cod", "Detalle"), List.<Object>of("Z-9", "Arandela")));
        MockMultipartFile archivo = archivo("lista.xlsx", ListasDePrueba.xlsxHojas(hojas));

        postMultipart(MUESTRA, duenoA, archivo, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hojas[0].indice", is(0)))
                .andExpect(jsonPath("$.hojas[0].nombre", is("Lista")))
                .andExpect(jsonPath("$.hojas[0].columnas", is(3)))
                .andExpect(jsonPath("$.hojas[0].filas[1].celdas[0]", is("A-1")))
                .andExpect(jsonPath("$.hojas[1].indice", is(1)))
                .andExpect(jsonPath("$.hojas[1].nombre", is("Oferta")))
                .andExpect(jsonPath("$.hojas[1].columnas", is(2)))
                .andExpect(jsonPath("$.hojas[1].filas[1].celdas[0]", is("Z-9")));
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
                .andExpect(jsonPath("$.nuevos.length()", is(20_000)))
                .andExpect(jsonPath("$.detalleRecortado", is(false)));
    }

    @Test
    void conMuchasMasDeVeinteMilFilasTambienLaMuestraDa400() throws Exception {
        // El tope grueso de lectura es de 25.000 filas por libro (corte 2): 25.000 pasan, 25.001 dan 400.
        postMultipart(MUESTRA, duenoA, csvDe(0, 25_001), null)
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

    // --- Precio manual y ajustes sobre el precio final (CU-14) ---

    @Test
    void laListaRespetaElPrecioManualYAplicaLosAjustesComoLaVistaPrevia() throws Exception {
        long proveedor = crearProveedor(duenoA, unico("Prov CU14"));
        String sufijo = unico("").substring(0, 6);
        long manual = crearArticulo(duenoA, proveedor, "M-" + sufijo, "100.00");
        jdbc.update("update articulo set precio_manual = true, precio_final = 999.00 where id = ?", manual);
        long conAjuste = crearArticulo(duenoA, proveedor, "A-" + sufijo, "100.00");
        jdbc.update("update articulo set ajustes_precio = ? where id = ?",
                "[{\"nombre\":\"Flete\",\"tipo\":\"PORCENTAJE\",\"valor\":10}]", conAjuste);
        long comun = crearArticulo(duenoA, proveedor, "C-" + sufijo, "100.00");
        MockMultipartFile archivo = xlsx(conEncabezado(
                fila("M-" + sufijo, "Manual", n("110")),
                fila("A-" + sufijo, "Con ajuste", n("110")),
                fila("C-" + sufijo, "Comun", n("110"))));

        JsonNode previa = vistaPrevia(archivo, datosVistaPrevia(proveedor));
        Map<Long, BigDecimal> precios = new LinkedHashMap<>();
        previa.get("cambios").forEach(c -> precios.put(c.get("articuloId").asLong(), c.get("precioNuevo").decimalValue()));
        assertThat(precios.get(manual)).isEqualByComparingTo("999.00");
        assertThat(precios.get(conAjuste)).isGreaterThan(precios.get(comun));

        aplicar(archivo, datosVistaPrevia(proveedor), false, null).andExpect(status().isCreated());

        assertThat(decimal(manual, "costo")).isEqualByComparingTo("110.00");
        assertThat(decimal(manual, "precio_final")).isEqualByComparingTo("999.00");
        assertThat(jdbc.queryForObject("select precio_manual from articulo where id = ?", Boolean.class, manual))
                .isTrue();
        assertThat(decimal(conAjuste, "precio_final")).isEqualByComparingTo(precios.get(conAjuste));
        assertThat(decimal(comun, "precio_final")).isEqualByComparingTo(precios.get(comun));
    }

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
    void unCostoEnDolaresEnUnCsvSinMonedaAvisaEnLaVistaPreviaYNoSeImporta() throws Exception {
        long proveedor = crearProveedor(duenoA, unico("P"));
        String enDolares = unico("USD");
        String enPesos = unico("ARS");
        MockMultipartFile csv = archivo("lista.csv", ListasDePrueba.csv(List.of(
                List.of("Codigo", "Descripcion", "Costo"),
                List.of(enDolares, "Tuerca", "US$ 4,00"),
                List.of(enPesos, "Tornillo", "$ 100,00")), ';', StandardCharsets.UTF_8, false));

        JsonNode previa = vistaPrevia(csv, datosVistaPrevia(proveedor));
        assertThat(previa.at("/conteos/conError").asInt()).isEqualTo(1);
        assertThat(previa.at("/conteos/nuevos").asInt()).isEqualTo(1);
        assertThat(previa.at("/errores/0/fila").asInt()).isEqualTo(2);
        assertThat(previa.at("/errores/0/mensaje").asText()).isEqualTo("El costo está en dólares, pero la lista no dice "
                + "la moneda. Mapeá la columna de moneda o elegí la moneda de todas las filas.");

        aplicar(csv, datosVistaPrevia(proveedor), true, null).andExpect(status().isCreated());
        assertThat(contar("select count(*) from articulo where codigo_interno = ?", enDolares)).isZero();
        assertThat(contar("select count(*) from articulo where codigo_interno = ?", enPesos)).isEqualTo(1);
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

        putJson("/api/articulos/" + articulo, duenoA, conLaVersionVigente(articulo, objectMapper.writeValueAsString(pedido))).andExpect(status().isOk());

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

    // --- Articulos en dolares: la guarda del corte 1 ya no existe (T-125, T-145) ---

    private static final String CASA_DE_PRUEBA = "t78-oficial";
    private static final Instant HACE_UN_RATO = Instant.parse("2026-10-16T11:00:00Z");

    /** La ferreteria de los tests con dolar: nunca la de los demos, porque la configuracion y la cotizacion se reemplazan. */
    private Long ferreteriaConDolar;

    @AfterEach
    void limpiarFerreteriasPropias() {
        borrarCotizaciones("t78-");
        for (Long ferreteria : java.util.Arrays.asList(ferreteriaConDolar, ferreteriaA, ferreteriaB)) {
            if (ferreteria != null) {
                borrarDatosDe(ferreteria);
            }
        }
        ferreteriaConDolar = null;
        ferreteriaA = null;
        ferreteriaB = null;
    }

    /** Una ferreteria propia con el dolar vigente en 1.000 (margen 30 %, multiplo 10) y un Dueno; devuelve su token. */
    private String duenoConDolar(String valor) throws Exception {
        ferreteriaConDolar = ferreteria("30-78150001-1", "Importacion con dolar");
        borrarCotizaciones("t78-");
        cotizacion("USD", CASA_DE_PRUEBA, "1090", "1100", HACE_UN_RATO);
        configuracion(ferreteriaConDolar, c -> {
            c.setCasaPrecios(CASA_DE_PRUEBA);
            c.fijarVigente(new BigDecimal(valor), CASA_DE_PRUEBA, LadoCotizacion.VENTA, HACE_UN_RATO);
        });
        return login(usuario(ferreteriaConDolar, "dueno-t78@test.local", "DUENO"));
    }

    @Test
    void laFilaDeUnArticuloEnDolaresSeActualizaComoLasDePesos() throws Exception {
        String dueno = duenoConDolar("1000.0000");
        long proveedor = crearProveedor(dueno, unico("P"));
        String s = unico("");
        long enDolares = crearArticulo(dueno, proveedor, "USD" + s, "100");
        long enPesos = crearArticulo(dueno, proveedor, "ARS" + s, "100");
        // El de dolares: 10 USD y precio 16.340 con su margen de 35 % (10 x 1.000 x 1,35 x 1,21 = 16.335).
        jdbc.update("update articulo set moneda = 'USD', costo = 10.00, precio_final = 16340.00 where id = ?", enDolares);
        ObjectNode datos = datosVistaPrevia(proveedor);
        MockMultipartFile archivo = xlsx(conEncabezado(fila("USD" + s, "x", n("12")), fila("ARS" + s, "x", n("120"))));

        JsonNode previa = objectMapper.readTree(postMultipart(VISTA_PREVIA, dueno, archivo, datos)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        assertConteos(previa.get("conteos"), 2, 0, 0, 0, 0, 0);
        assertThat(previa.get("errores")).isEmpty();

        postMultipart(APLICAR, dueno, archivo, datosDeAplicar(datos, previa.get("conteos"), false, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conteos.suben", is(2)))
                .andExpect(jsonPath("$.conteos.conError", is(0)));

        assertThat(decimal(enDolares, "costo")).isEqualByComparingTo("12.00");
        // 12 x 1.000 x 1,35 x 1,21 = 19.602 -> 19.610
        assertThat(decimal(enDolares, "precio_final")).isEqualByComparingTo("19610.00");
        assertThat(filaDeArticulo(enDolares).get("moneda")).isEqualTo("USD");
        // La entrada de alta (MANUAL, en pesos) y la de la importacion, ya en USD.
        assertThat(historialDe(enDolares)).hasSize(2);
        assertThat(historialDe(enDolares).get(1).get("origen")).isEqualTo("IMPORTACION");
        assertThat(historialDe(enDolares).get(1).get("moneda")).isEqualTo("USD");
        assertThat(decimal(enPesos, "costo")).isEqualByComparingTo("120");
        assertThat(contar("select count(*) from historial_precio where origen = 'IMPORTACION' and articulo_id = ?", enPesos))
                .isEqualTo(1);
    }

    @Test
    void unaListaSinArticulosEnDolaresSigueIgual() throws Exception {
        long proveedor = crearProveedor(duenoA, unico("P"));
        String codigo = unico("SOLOARS");
        long articulo = crearArticulo(duenoA, proveedor, codigo, "100");
        ObjectNode datos = datosVistaPrevia(proveedor);
        MockMultipartFile archivo = xlsx(conEncabezado(fila(codigo, "x", n("120"))));

        JsonNode previa = vistaPrevia(archivo, datos);

        assertConteos(previa.get("conteos"), 1, 0, 0, 0, 0, 0);
        assertThat(previa.get("errores")).isEmpty();
        postMultipart(APLICAR, duenoA, archivo, datosDeAplicar(datos, previa.get("conteos"), false, null))
                .andExpect(status().isCreated());
        assertThat(decimal(articulo, "costo")).isEqualByComparingTo("120");
        assertThat(filaDeArticulo(articulo).get("moneda")).isEqualTo("ARS");
    }

    // =====================================================================================================
    // Corte 2: dos ferreterias propias (bloque de CUIT 30-7815xxxx), con el dolar vigente en 1.000 en las dos
    // =====================================================================================================

    private static final String CUIT_A = "30-78150002-2";
    private static final String CUIT_B = "30-78150003-3";

    private Long ferreteriaA;
    private Long ferreteriaB;
    private String dueA;
    private String dueB;
    private String venA;

    /** A y B con el dolar vigente en 1.000 (margen 30 %, multiplo 10), un Dueno cada una y un Vendedor en A. */
    private void sembrarAyB() throws Exception {
        ferreteriaA = ferreteria(CUIT_A, "Importacion A");
        ferreteriaB = ferreteria(CUIT_B, "Importacion B");
        borrarCotizaciones("t78-");
        cotizacion("USD", CASA_DE_PRUEBA, "1090", "1100", HACE_UN_RATO);
        for (Long f : List.of(ferreteriaA, ferreteriaB)) {
            configuracion(f, c -> {
                c.setCasaPrecios(CASA_DE_PRUEBA);
                c.fijarVigente(new BigDecimal("1000.0000"), CASA_DE_PRUEBA, LadoCotizacion.VENTA, HACE_UN_RATO);
            });
        }
        dueA = login(usuario(ferreteriaA, "dueno-t78a@test.local", "DUENO"));
        venA = login(usuario(ferreteriaA, "vendedor-t78a@test.local", "VENDEDOR"));
        dueB = login(usuario(ferreteriaB, "dueno-t78b@test.local", "DUENO"));
    }

    private long articuloDe(String token, long proveedor, String codigo, String costo) throws Exception {
        ObjectNode pedido = objectMapper.createObjectNode()
                .put("codigoInterno", unico("INT").substring(0, 12))
                .put("codigoProveedor", codigo)
                .put("descripcion", "Articulo " + codigo)
                .put("unidad", "UNIDAD")
                .put("proveedorId", proveedor)
                .put("margenPorcentaje", new BigDecimal("30"))
                .put("costo", new BigDecimal(costo));
        String body = postJson("/api/articulos", token, objectMapper.writeValueAsString(pedido))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asLong();
    }

    /** Un mapeo con las columnas del corte 2 que se piden (el indice de cada una); codigo 0, descripcion 1 y costo 2 siempre. */
    private ObjectNode mapeoNode(Integer moneda, Integer iva, Integer ajuste1, Integer barras, Integer unidad, Integer rubro) {
        ObjectNode mapeo = mapeo();
        if (moneda != null) {
            mapeo.put("columnaMoneda", moneda);
        }
        if (iva != null) {
            mapeo.put("columnaIva", iva);
        }
        if (ajuste1 != null) {
            mapeo.put("columnaAjuste1", ajuste1);
        }
        if (barras != null) {
            mapeo.put("columnaCodigoBarras", barras);
        }
        if (unidad != null) {
            mapeo.put("columnaUnidad", unidad);
        }
        if (rubro != null) {
            mapeo.put("columnaRubro", rubro);
        }
        return mapeo;
    }

    private ObjectNode datosCon(long proveedor, ObjectNode mapeo) {
        ObjectNode datos = objectMapper.createObjectNode().put("proveedorId", proveedor).put("listaIncluyeIva", false);
        datos.set("mapeo", mapeo);
        return datos;
    }

    private ObjectNode conteos9(int suben, int bajan, int nuevos, int sinCambio, int noEstan, int conError,
                                int cambianMoneda, int corrigen, int salteadas) {
        return objectMapper.createObjectNode().put("suben", suben).put("bajan", bajan).put("nuevos", nuevos)
                .put("sinCambio", sinCambio).put("noEstan", noEstan).put("conError", conError)
                .put("cambianMoneda", cambianMoneda).put("corrigen", corrigen).put("salteadas", salteadas);
    }

    /** Los datos de aplicar: los de la vista previa mas los contadores y, si se pasan, las filas elegidas como hoja, fila... */
    private ObjectNode datosAplicarCon(ObjectNode datos, JsonNode conteos, boolean crearNuevos, Long rubroId,
                                       int... hojaYFila) {
        ObjectNode aplicar = datos.deepCopy();
        aplicar.put("crearNuevos", crearNuevos);
        if (rubroId != null) {
            aplicar.put("rubroId", rubroId);
        }
        aplicar.set("conteosEsperados", conteos);
        if (hojaYFila.length > 0) {
            aplicar.set("filasElegidas", filasElegidas(hojaYFila));
        }
        return aplicar;
    }

    private com.fasterxml.jackson.databind.node.ArrayNode filasElegidas(int... hojaYFila) {
        com.fasterxml.jackson.databind.node.ArrayNode filas = objectMapper.createArrayNode();
        for (int i = 0; i < hojaYFila.length; i += 2) {
            filas.add(objectMapper.createObjectNode().put("hoja", hojaYFila[i]).put("fila", hojaYFila[i + 1]));
        }
        return filas;
    }

    private JsonNode vistaPreviaDe(String token, MockMultipartFile archivo, ObjectNode datos) throws Exception {
        return objectMapper.readTree(postMultipart(VISTA_PREVIA, token, archivo, datos)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String texto(ResultActions resultado) throws Exception {
        return resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private Map<String, Object> estadoDeArticulo(long id) {
        return filaDeArticulo(id);
    }

    private static List<Object> filaExtra(Object... celdas) {
        return java.util.Arrays.asList(celdas);
    }

    // --- T-137: POST /api/importaciones/muestra con todas las hojas y la propuesta ---

    @Test
    void laMuestraDeMsChicaTraeLaHojaConSuEncabezadoYProponeLasColumnas() throws Exception {
        sembrarAyB();

        postMultipart(MUESTRA, dueA, archivo("lista.xls", ListasReales.msChica()), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hojas.length()", is(1)))
                .andExpect(jsonPath("$.hojas[0].indice", is(0)))
                .andExpect(jsonPath("$.hojas[0].nombre", is("M-S ")))
                .andExpect(jsonPath("$.hojas[0].filaEncabezado", is(14)))
                .andExpect(jsonPath("$.hojas[0].columnas", is(6)))
                .andExpect(jsonPath("$.propuesta.hoja", is(0)))
                .andExpect(jsonPath("$.propuesta.filaDesde", is(15)))
                .andExpect(jsonPath("$.propuesta.columnaCodigo", is(0)))
                .andExpect(jsonPath("$.propuesta.columnaDescripcion", is(1)))
                .andExpect(jsonPath("$.propuesta.columnaCosto", is(2)))
                .andExpect(jsonPath("$.propuesta.columnaMoneda").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.propuesta.columnaSinIva").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.propuesta.hojas[0]", is(0)));
    }

    @Test
    void laMuestraDeLista336ProponeMonedaYElParDeIvaYNoProponeStockSinonimoNiBulto() throws Exception {
        sembrarAyB();

        String cuerpo = texto(postMultipart(MUESTRA, dueA, archivo("lista.xlsx", ListasReales.lista336Chica()), null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hojas.length()", is(3)))
                .andExpect(jsonPath("$.hojas[1].nombre", is("TUERCAS")))
                .andExpect(jsonPath("$.hojas[2].filaEncabezado", is(1)))
                .andExpect(jsonPath("$.propuesta.columnaCodigo", is(0)))
                .andExpect(jsonPath("$.propuesta.columnaDescripcion", is(1)))
                .andExpect(jsonPath("$.propuesta.columnaMoneda", is(4)))
                .andExpect(jsonPath("$.propuesta.columnaCosto", is(5)))
                .andExpect(jsonPath("$.propuesta.columnaSinIva", is(7)))
                .andExpect(jsonPath("$.propuesta.columnaConIva", is(8)))
                .andExpect(jsonPath("$.propuesta.filaDesde", is(2))));

        JsonNode propuesta = objectMapper.readTree(cuerpo).get("propuesta");
        // Sinonimo (2), Stock (3) y Bulto (6) no son ningun campo de la propuesta.
        List<Integer> columnas = new ArrayList<>();
        propuesta.fields().forEachRemaining(campo -> {
            if (campo.getKey().startsWith("columna") && campo.getValue().isInt()) {
                columnas.add(campo.getValue().asInt());
            }
        });
        assertThat(columnas).doesNotContain(2, 3, 6);
    }

    @Test
    void laMuestraEsDelDuenoElVendedorRecibe403YSinTokenDa401() throws Exception {
        sembrarAyB();
        MockMultipartFile lista = archivo("lista.xlsx", ListasReales.lista336Chica());

        postMultipart(MUESTRA, dueA, lista, null).andExpect(status().isOk());
        postMultipart(MUESTRA, venA, lista, null).andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.multipart(MUESTRA).file(lista)).andExpect(status().isUnauthorized());
    }

    @Test
    void laMuestraSoloDependeDelArchivoYNoMuestraNadaDeNingunaFerreteria() throws Exception {
        sembrarAyB();
        long proveedorA = crearProveedor(dueA, unico("PROV-A"));
        long proveedorB = crearProveedor(dueB, unico("PROV-B"));
        MockMultipartFile lista = archivo("lista.xlsx", ListasReales.lista336Chica());
        String sinMapeoGuardado = texto(postMultipart(MUESTRA, dueA, lista, null).andExpect(status().isOk()));

        // B guarda un mapeo distinto en su proveedor y A lo recibe igual: la propuesta sale del archivo.
        jdbc.update("update proveedor set mapeo_lista = ? where id = ?",
                "{\"hoja\":0,\"filaDesde\":9,\"columnaCodigo\":3,\"columnaDescripcion\":4,\"columnaCosto\":5}", proveedorB);
        String conMapeoEnB = texto(postMultipart(MUESTRA, dueA, lista, null).andExpect(status().isOk()));
        // Un parametro ferreteriaId se ignora.
        String conParametro = texto(postMultipart(MUESTRA + "?ferreteriaId=" + ferreteriaB, dueA, lista, null)
                .andExpect(status().isOk()));

        assertThat(objectMapper.readTree(conMapeoEnB)).isEqualTo(objectMapper.readTree(sinMapeoGuardado));
        assertThat(objectMapper.readTree(conParametro)).isEqualTo(objectMapper.readTree(sinMapeoGuardado));
        // La respuesta es solo el contenido del archivo: ningun nombre de proveedor ni dato de una ferreteria.
        assertThat(conParametro).doesNotContain("PROV-A", "PROV-B", "Importacion A", "Importacion B");
        assertThat(proveedorA).isNotEqualTo(proveedorB);
    }

    @Test
    void unaColumnaStockOMarcaEnElMapeoSeIgnoraYNoCambiaElStock() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        String codigo = unico("STK");
        long articulo = articuloDe(dueA, proveedor, codigo, "100");
        jdbc.update("update articulo set stock_actual = 7 where id = ?", articulo);
        MockMultipartFile lista = archivo("lista.xlsx", ListasDePrueba.xlsx(List.of(
                filaExtra("Codigo", "Descripcion", "Costo", "Stock", "Marca"),
                filaExtra(codigo, "x", n("120"), n("99"), "Acme"))));
        ObjectNode mapeo = mapeo();
        mapeo.put("columnaStock", 3).put("columnaMarca", 4);
        ObjectNode datos = datosCon(proveedor, mapeo);

        JsonNode previa = vistaPreviaDe(dueA, lista, datos);
        postMultipart(APLICAR, dueA, lista, datosAplicarCon(datos, previa.get("conteos"), false, null))
                .andExpect(status().isCreated());

        assertThat(decimal(articulo, "stock_actual")).isEqualByComparingTo("7");
        assertThat(decimal(articulo, "costo")).isEqualByComparingTo("120");
        String mapeoGuardado = jdbc.queryForObject("select mapeo_lista from proveedor where id = ?", String.class, proveedor);
        assertThat(mapeoGuardado).doesNotContain("Stock", "Marca");
    }

    // --- T-138: POST /api/importaciones/vista-previa con el detalle completo y las listas nuevas ---

    @Test
    void conDosMilQuinientasFilasNuevasLosContadoresYElDetalleDicenDosMilQuinientas() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        List<List<Object>> filas = new ArrayList<>();
        filas.add(ENCABEZADO);
        for (int i = 1; i <= 2500; i++) {
            filas.add(fila("N-" + i, "Nuevo " + i, n("10")));
        }

        JsonNode previa = vistaPreviaDe(dueA, xlsx(filas), datosVistaPrevia(proveedor));

        assertThat(previa.get("conteos").get("nuevos").asInt()).isEqualTo(2500);
        assertThat(previa.get("nuevos")).hasSize(2500);
        assertThat(previa.get("nuevos").get(2499).get("fila").asInt()).isEqualTo(2501);
        assertThat(previa.get("detalleRecortado").asBoolean()).isFalse();
    }

    /** La fila trae los recargos del costo (CU-13) y el costo real antes y despues; sin recargos van en null. */
    @Test
    void laVistaPreviaTraeElCostoRealDeLosArticulosConRecargos() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        String s = unico("");
        long conFlete = articuloDe(dueA, proveedor, "FLE" + s, "100");
        jdbc.update("update articulo set recargos_costo = ? where id = ?",
                "[{\"nombre\":\"Flete\",\"porcentaje\":10}]", conFlete);
        articuloDe(dueA, proveedor, "SIN" + s, "100");
        MockMultipartFile lista = archivo("lista.xlsx", ListasDePrueba.xlsx(List.of(
                fila("Codigo", "Descripcion", "Costo"),
                fila("FLE" + s, "x", n("200")),
                fila("SIN" + s, "x", n("200")))));

        JsonNode cambios = vistaPreviaDe(dueA, lista, datosCon(proveedor, mapeo())).get("cambios");

        assertThat(cambios).hasSize(2);
        JsonNode flete = cambios.get(0);
        assertThat(flete.get("articuloId").asLong()).isEqualTo(conFlete);
        assertThat(flete.get("recargosPorcentaje").decimalValue()).isEqualByComparingTo("10");
        assertThat(flete.get("costoRealAnterior").decimalValue()).isEqualByComparingTo("110.00");
        assertThat(flete.get("costoRealNuevo").decimalValue()).isEqualByComparingTo("220.00");
        for (String campo : List.of("recargosPorcentaje", "costoRealAnterior", "costoRealNuevo")) {
            assertThat(cambios.get(1).has(campo)).as(campo).isTrue();
            assertThat(cambios.get(1).get(campo).isNull()).as(campo).isTrue();
        }
    }

    /** El cambio trae la unidad del articulo que ya existe, para que la pantalla diga «/ kg» (#36). */
    @Test
    void laVistaPreviaTraeLaUnidadDeLosArticulosQueCambian() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        String s = unico("");
        long porKilo = articuloDe(dueA, proveedor, "KG" + s, "100");
        jdbc.update("update articulo set unidad = 'KILO' where id = ?", porKilo);
        articuloDe(dueA, proveedor, "UN" + s, "100");
        MockMultipartFile lista = archivo("lista.xlsx", ListasDePrueba.xlsx(List.of(
                fila("Codigo", "Descripcion", "Costo"),
                fila("KG" + s, "x", n("200")),
                fila("UN" + s, "x", n("200")))));

        JsonNode cambios = vistaPreviaDe(dueA, lista, datosCon(proveedor, mapeo())).get("cambios");

        assertThat(cambios).hasSize(2);
        assertThat(cambios.get(0).get("articuloId").asLong()).isEqualTo(porKilo);
        assertThat(cambios.get(0).get("unidad").asText()).isEqualTo("KILO");
        assertThat(cambios.get(1).get("unidad").asText()).isEqualTo("UNIDAD");
    }

    @Test
    void laVistaPreviaTraeLasListasNuevasConHojaYLosContadoresNuevos() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        String s = unico("");
        long moneda = articuloDe(dueA, proveedor, "MON" + s, "1000");
        long correccion = articuloDe(dueA, proveedor, "COR" + s, "10000");
        jdbc.update("update articulo set unidad = 'KILO' where id = ?", moneda);
        jdbc.update("update articulo set unidad = 'METRO' where id = ?", correccion);
        articuloDe(dueA, proveedor, "IGU" + s, "100");
        MockMultipartFile lista = archivo("lista.xlsx", ListasDePrueba.xlsx(List.of(
                filaExtra("Codigo", "Descripcion", "Costo", "Moneda", "IVA"),
                filaExtra("MON" + s, "x", n("10"), "USD", null),
                filaExtra("COR" + s, "x", n("10000"), "ARG", n("10.5")),
                filaExtra("IGU" + s, "x", n("100"), "ARG", null),
                filaExtra("Familia: 001 - Titulo"))));
        ObjectNode datos = datosCon(proveedor, mapeoNode(3, 4, null, null, null, null));

        JsonNode previa = vistaPreviaDe(dueA, lista, datos);

        assertThat(previa.get("conteos").get("cambianMoneda").asInt()).isEqualTo(1);
        assertThat(previa.get("conteos").get("corrigen").asInt()).isEqualTo(1);
        assertThat(previa.get("conteos").get("sinCambio").asInt()).isEqualTo(1);
        assertThat(previa.get("conteos").get("salteadas").asInt()).isEqualTo(1);
        assertThat(previa.get("cambiosDeMoneda")).hasSize(1);
        assertThat(previa.get("cambiosDeMoneda").get(0).get("hoja").asInt()).isZero();
        assertThat(previa.get("cambiosDeMoneda").get(0).get("fila").asInt()).isEqualTo(2);
        assertThat(previa.get("cambiosDeMoneda").get(0).get("monedaAnterior").asText()).isEqualTo("ARS");
        assertThat(previa.get("cambiosDeMoneda").get(0).get("monedaNueva").asText()).isEqualTo("USD");
        assertThat(previa.get("cambiosDeMoneda").get(0).get("precioNuevo").decimalValue()).isEqualByComparingTo("15730");
        assertThat(previa.get("cambiosDeMoneda").get(0).get("unidad").asText()).isEqualTo("KILO");
        assertThat(previa.get("correcciones")).hasSize(1);
        assertThat(previa.get("correcciones").get(0).get("alicuotaAnterior").asText()).isEqualTo("IVA_21");
        assertThat(previa.get("correcciones").get(0).get("alicuotaNueva").asText()).isEqualTo("IVA_10_5");
        assertThat(previa.get("correcciones").get(0).get("precioNuevo").decimalValue()).isEqualByComparingTo("14370");
        assertThat(previa.get("correcciones").get(0).get("unidad").asText()).isEqualTo("METRO");
        assertThat(previa.get("sinCambio")).hasSize(1);
        assertThat(previa.get("sinCambio").get(0).get("fila").asInt()).isEqualTo(4);
        assertThat(previa.get("sinCambio").get(0).get("codigo").asText()).isEqualTo("IGU" + s);
        assertThat(previa.get("cambios")).isEmpty();
        assertThat(previa.get("errores")).isEmpty();
    }

    @Test
    void elCuerpoViejoSinHojasNiCamposNuevosDa200YLeeLaHojaDelMapeo() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        String codigo = unico("OLD");
        articuloDe(dueA, proveedor, codigo, "100");
        Map<String, List<List<Object>>> libro = new LinkedHashMap<>();
        libro.put("Portada", List.of(filaExtra("nada")));
        libro.put("Lista", List.of(ENCABEZADO, filaExtra(codigo, "x", n("120"))));
        MockMultipartFile lista = archivo("lista.xlsx", ListasDePrueba.xlsxHojas(libro));
        ObjectNode datos = datosVistaPrevia(proveedor);
        ((ObjectNode) datos.get("mapeo")).put("hoja", 1);

        JsonNode previa = vistaPreviaDe(dueA, lista, datos);

        assertConteos(previa.get("conteos"), 1, 0, 0, 0, 0, 0);
        assertThat(previa.get("cambios").get(0).get("hoja").asInt()).isEqualTo(1);
        assertThat(previa.get("detalleRecortado").asBoolean()).isFalse();
    }

    @Test
    void unMapeoSinCodigoOSinCostoDa400EnEspanol() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        for (String campo : List.of("columnaCodigo", "columnaCosto")) {
            ObjectNode datos = datosVistaPrevia(proveedor);
            ((ObjectNode) datos.get("mapeo")).remove(campo);

            postMultipart(VISTA_PREVIA, dueA, lista("M-1", "100"), datos)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors['mapeo." + campo + "']").exists());
        }
    }

    @Test
    void laAdvertenciaDeEncabezadoCambiadoLlegaConLaHoja() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        jdbc.update("update proveedor set mapeo_lista = ? where id = ?",
                "{\"hoja\":0,\"filaDesde\":2,\"columnaCodigo\":0,\"columnaDescripcion\":1,\"columnaCosto\":2,"
                        + "\"encabezados\":{\"codigo\":\"Codigo\",\"descripcion\":\"Descripcion\",\"costo\":\"Precio\"}}",
                proveedor);

        JsonNode previa = vistaPreviaDe(dueA, lista("H-1", "100"), datosVistaPrevia(proveedor));

        assertThat(previa.get("advertencias")).hasSize(1);
        assertThat(previa.get("advertencias").get(0).get("hoja").asInt()).isZero();
        assertThat(previa.get("advertencias").get(0).get("mensaje").asText())
                .isEqualTo("El encabezado de la columna del costo cambió: antes decía «Precio» y ahora dice «Costo». Revisá el mapeo.");
    }

    // --- T-139: aislamiento de la vista previa ---

    @Test
    void laVistaPreviaConElProveedorDeOtraFerreteriaDa404YNoLeeElArchivo() throws Exception {
        sembrarAyB();
        long proveedorB = crearProveedor(dueB, unico("PB"));

        postMultipart(VISTA_PREVIA, dueA, lista("T-100", "100"), datosVistaPrevia(proveedorB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Proveedor no encontrado")));

        org.mockito.Mockito.verifyNoInteractions(lector);
    }

    @Test
    void conElMismoCodigoEnAyEnBElArticuloDeBNoApareceNiCambiaYParaAEsNuevo() throws Exception {
        sembrarAyB();
        long proveedorA = crearProveedor(dueA, unico("PA"));
        long proveedorB = crearProveedor(dueB, unico("PB"));
        long articuloB = articuloDe(dueB, proveedorB, "T-100", "100");
        Map<String, Object> antes = estadoDeArticulo(articuloB);

        JsonNode previa = vistaPreviaDe(dueA, lista("T-100", "500"), datosVistaPrevia(proveedorA));

        assertConteos(previa.get("conteos"), 0, 0, 1, 0, 0, 0);
        assertThat(previa.get("nuevos").get(0).get("codigo").asText()).isEqualTo("T-100");
        assertThat(previa.get("noEstan")).isEmpty();
        assertThat(previa.get("cambios")).isEmpty();
        assertThat(previa.toString()).doesNotContain("Articulo T-100");
        assertThat(estadoDeArticulo(articuloB)).isEqualTo(antes);
    }

    @Test
    void unRubroQueSoloExisteEnBNoSeEncuentraDesdeA() throws Exception {
        sembrarAyB();
        long proveedorA = crearProveedor(dueA, unico("PA"));
        String rubroDeB = "Tornilleria " + unico("R");
        crearRubro(dueB, rubroDeB, "40");
        MockMultipartFile lista = archivo("lista.xlsx", ListasDePrueba.xlsx(List.of(
                filaExtra("Codigo", "Descripcion", "Costo", "Rubro"),
                filaExtra("R-1", "Tornillo", n("100"), rubroDeB))));

        JsonNode previa = vistaPreviaDe(dueA, lista, datosCon(proveedorA, mapeoNode(null, null, null, null, null, 3)));

        assertThat(previa.get("nuevos").get(0).get("rubroId").isNull()).isTrue();
        assertThat(previa.get("advertencias")).hasSize(1);
        assertThat(previa.get("advertencias").get(0).get("mensaje").asText())
                .contains("El rubro «" + rubroDeB + "» no existe");
    }

    @Test
    void unCodigoDeBarrasActivoSoloEnBNoChocaEnA() throws Exception {
        sembrarAyB();
        long proveedorA = crearProveedor(dueA, unico("PA"));
        long proveedorB = crearProveedor(dueB, unico("PB"));
        long articuloB = articuloDe(dueB, proveedorB, "BAR-B", "100");
        jdbc.update("update articulo set codigo_barras = '7791234567890' where id = ?", articuloB);
        MockMultipartFile lista = archivo("lista.xlsx", ListasDePrueba.xlsx(List.of(
                filaExtra("Codigo", "Descripcion", "Costo", "Barras"),
                filaExtra("BAR-A", "Tornillo", n("100"), "7791234567890"))));

        JsonNode previa = vistaPreviaDe(dueA, lista, datosCon(proveedorA, mapeoNode(null, null, null, 3, null, null)));

        assertThat(previa.get("errores")).isEmpty();
        assertThat(previa.get("nuevos").get(0).get("codigoBarras").asText()).isEqualTo("7791234567890");
    }

    @Test
    void unArticuloEnDolaresDeBNoSeVeNiCambiaDesdeA() throws Exception {
        sembrarAyB();
        long proveedorA = crearProveedor(dueA, unico("PA"));
        long proveedorB = crearProveedor(dueB, unico("PB"));
        long articuloB = articuloDe(dueB, proveedorB, "USD-B", "100");
        jdbc.update("update articulo set moneda = 'USD', costo = 10.00, precio_final = 15730.00 where id = ?", articuloB);
        Map<String, Object> antes = estadoDeArticulo(articuloB);
        MockMultipartFile lista = archivo("lista.xlsx", ListasDePrueba.xlsx(List.of(
                filaExtra("Codigo", "Descripcion", "Costo", "Moneda"),
                filaExtra("USD-B", "x", n("12"), "USD"))));

        JsonNode previa = vistaPreviaDe(dueA, lista, datosCon(proveedorA, mapeoNode(3, null, null, null, null, null)));

        // Para A es un articulo nuevo en dolares; el de B no entra en ninguna lista.
        assertConteos(previa.get("conteos"), 0, 0, 1, 0, 0, 0);
        assertThat(previa.get("cambiosDeMoneda")).isEmpty();
        assertThat(previa.get("nuevos").get(0).get("moneda").asText()).isEqualTo("USD");
        assertThat(estadoDeArticulo(articuloB)).isEqualTo(antes);
    }

    // --- T-140: POST /api/importaciones (aplicar) con las filas elegidas ---

    private ResultActions aplicarCon(String token, MockMultipartFile archivo, ObjectNode datos) throws Exception {
        return postMultipart(APLICAR, token, archivo, datos);
    }

    private String ultimaImportacion(long articuloId) {
        Object valor = jdbc.queryForMap("select ultima_importacion_id from articulo where id = ?", articuloId)
                .get("ultima_importacion_id");
        return valor == null ? null : valor.toString();
    }

    private long historialImportacion(long articuloId) {
        return contar("select count(*) from historial_precio where origen = 'IMPORTACION' and articulo_id = ?", articuloId);
    }

    @Test
    void masDeVeinteMilFilasElegidasDa400() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        ObjectNode datos = datosAplicarCon(datosVistaPrevia(proveedor), conteos9(0, 0, 0, 0, 0, 0, 0, 0, 0), false, null);
        com.fasterxml.jackson.databind.node.ArrayNode muchas = objectMapper.createArrayNode();
        for (int i = 0; i < 20_001; i++) {
            muchas.add(objectMapper.createObjectNode().put("hoja", 0).put("fila", 2));
        }
        datos.set("filasElegidas", muchas);

        aplicarCon(dueA, lista("L-1", "100"), datos)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.filasElegidas", is("Se pueden elegir hasta 20.000 filas.")));
    }

    @Test
    void conTresDeCincoFilasElegidasSoloEsasTocanArticulosHistorialYUltimaImportacion() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        String s = unico("");
        long[] ids = new long[5];
        List<List<Object>> filas = new ArrayList<>();
        filas.add(ENCABEZADO);
        for (int i = 0; i < 5; i++) {
            ids[i] = articuloDe(dueA, proveedor, "S" + i + s, "100");
            filas.add(fila("S" + i + s, "x", n("120")));
        }
        ObjectNode datos = datosVistaPrevia(proveedor);

        // Se eligen las filas 2, 4 y 6 (los articulos 0, 2 y 4).
        aplicarCon(dueA, xlsx(filas), datosAplicarCon(datos, conteos9(3, 0, 0, 0, 0, 0, 0, 0, 0), false, null, 0, 2, 0, 4, 0, 6))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conteos.suben", is(3)));

        for (int i : new int[] {0, 2, 4}) {
            assertThat(decimal(ids[i], "costo")).isEqualByComparingTo("120");
            assertThat(historialImportacion(ids[i])).isEqualTo(1);
            assertThat(ultimaImportacion(ids[i])).isNotNull();
        }
        for (int i : new int[] {1, 3}) {
            assertThat(decimal(ids[i], "costo")).isEqualByComparingTo("100");
            assertThat(decimal(ids[i], "precio_lista_proveedor")).isNull();
            assertThat(historialImportacion(ids[i])).isZero();
            assertThat(ultimaImportacion(ids[i])).isNull();
        }
    }

    @Test
    void ultimaImportacionQuedaEnLosElegidosYNoEnLosDemas() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        String s = unico("");
        long sube = articuloDe(dueA, proveedor, "SUBE" + s, "100");
        long igual = articuloDe(dueA, proveedor, "IGUAL" + s, "100");
        long noElegido = articuloDe(dueA, proveedor, "NOELEGIDO" + s, "100");
        long previo = articuloDe(dueA, proveedor, "PREVIO" + s, "100");
        long aMano = articuloDe(dueA, proveedor, "AMANO" + s, "100");
        // El PREVIO venia de otra importacion (la 3 del ejemplo): es un registro real de la ferreteria.
        jdbc.update("insert into importacion_lista (ferreteria_id, proveedor_id, usuario_id, archivo, suben, bajan, nuevos, "
                + "sin_cambio, no_estan, con_error, estado) values (?, ?, "
                + "(select id from usuario where email = 'dueno-t78a@test.local'), 'anterior.xlsx', 0, 0, 0, 0, 0, 0, 'APLICADA')",
                ferreteriaA, proveedor);
        long anterior = contar("select max(id) from importacion_lista where ferreteria_id = ?", ferreteriaA);
        jdbc.update("update articulo set ultima_importacion_id = ? where id = ?", anterior, previo);
        List<List<Object>> filas = List.of(ENCABEZADO,
                fila("SUBE" + s, "x", n("120")), fila("IGUAL" + s, "x", n("100")), fila("NOELEGIDO" + s, "x", n("130")),
                fila("PREVIO" + s, "x", n("100")), fila("NUEVO" + s, "Nuevo", n("10")));

        String cuerpo = texto(aplicarCon(dueA, xlsx(filas), datosAplicarCon(datosVistaPrevia(proveedor),
                conteos9(1, 0, 1, 2, 1, 0, 0, 0, 0), true, null, 0, 2, 0, 3, 0, 5, 0, 6))
                .andExpect(status().isCreated()));

        long importacion = objectMapper.readTree(cuerpo).get("id").asLong();
        assertThat(ultimaImportacion(sube)).isEqualTo(String.valueOf(importacion));
        assertThat(ultimaImportacion(igual)).isEqualTo(String.valueOf(importacion));
        assertThat(ultimaImportacion(previo)).isEqualTo(String.valueOf(importacion));
        assertThat(ultimaImportacion(noElegido)).isNull();
        assertThat(ultimaImportacion(aMano)).isNull();
        Long nuevo = jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_proveedor = ?",
                Long.class, ferreteriaA, "NUEVO" + s);
        assertThat(ultimaImportacion(nuevo)).isEqualTo(String.valueOf(importacion));
        assertThat(decimal(noElegido, "costo")).isEqualByComparingTo("100");
    }

    @Test
    void unCambioDeMonedaDeArsAUsdSeAplicaConElHistorialDeLasDosMonedas() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        String codigo = unico("MON");
        long articulo = articuloDe(dueA, proveedor, codigo, "1000");
        MockMultipartFile lista = archivo("lista.xlsx", ListasDePrueba.xlsx(List.of(
                filaExtra("Codigo", "Descripcion", "Costo", "Moneda"), filaExtra(codigo, "x", n("10"), "USD"))));
        ObjectNode datos = datosCon(proveedor, mapeoNode(3, null, null, null, null, null));

        aplicarCon(dueA, lista, datosAplicarCon(datos, conteos9(0, 0, 0, 0, 0, 0, 1, 0, 0), false, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conteos.cambianMoneda", is(1)))
                .andExpect(jsonPath("$.conteos.suben", is(0)));

        Map<String, Object> fila = filaDeArticulo(articulo);
        assertThat(fila.get("moneda")).isEqualTo("USD");
        assertThat(decimal(articulo, "costo")).isEqualByComparingTo("10");
        assertThat(decimal(articulo, "precio_final")).isEqualByComparingTo("15730");
        Map<String, Object> historial = jdbc.queryForMap("select * from historial_precio where origen = 'IMPORTACION' "
                + "and articulo_id = ?", articulo);
        assertThat((BigDecimal) historial.get("costo_anterior")).isEqualByComparingTo("1000");
        assertThat((BigDecimal) historial.get("costo_nuevo")).isEqualByComparingTo("10");
        assertThat((BigDecimal) historial.get("precio_anterior")).isEqualByComparingTo("1580");
        assertThat((BigDecimal) historial.get("precio_nuevo")).isEqualByComparingTo("15730");
        assertThat(historial.get("moneda_anterior")).isEqualTo("ARS");
        assertThat(historial.get("moneda")).isEqualTo("USD");
    }

    @Test
    void unaCorreccionDeIvaSeAplicaDe15730A14370() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        String codigo = unico("COR");
        long articulo = articuloDe(dueA, proveedor, codigo, "10000");
        MockMultipartFile lista = archivo("lista.xlsx", ListasDePrueba.xlsx(List.of(
                filaExtra("Codigo", "Descripcion", "Costo", "IVA"), filaExtra(codigo, "x", n("10000"), n("10.5")))));
        ObjectNode datos = datosCon(proveedor, mapeoNode(null, 3, null, null, null, null));

        aplicarCon(dueA, lista, datosAplicarCon(datos, conteos9(0, 0, 0, 0, 0, 0, 0, 1, 0), false, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conteos.corrigen", is(1)));

        assertThat(filaDeArticulo(articulo).get("alicuota_iva")).isEqualTo("IVA_10_5");
        assertThat(decimal(articulo, "costo")).isEqualByComparingTo("10000");
        assertThat(decimal(articulo, "precio_final")).isEqualByComparingTo("14370");
        Map<String, Object> historial = jdbc.queryForMap("select * from historial_precio where origen = 'IMPORTACION' "
                + "and articulo_id = ?", articulo);
        assertThat((BigDecimal) historial.get("precio_anterior")).isEqualByComparingTo("15730");
        assertThat((BigDecimal) historial.get("precio_nuevo")).isEqualByComparingTo("14370");
        assertThat(historial.get("moneda_anterior")).isNull();
    }

    @Test
    void losAjustesSePersistenConElCostoYElPrecioYSeConservanOSeBorranSegunLaColumna() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        String s = unico("");
        long conAjustes = articuloDe(dueA, proveedor, "AJU" + s, "100");
        long conservado = articuloDe(dueA, proveedor, "CON" + s, "100");
        jdbc.update("update articulo set ajuste1 = -5.00 where id = ?", conservado);

        // 1.000 con bonificacion 10 % y ajustes -5 y +3: 880,65 y precio 1.390.
        ObjectNode mapeoConAjustes = mapeoNode(null, null, 3, null, null, null);
        mapeoConAjustes.put("columnaAjuste2", 4);
        MockMultipartFile lista = archivo("lista.xlsx", ListasDePrueba.xlsx(List.of(
                filaExtra("Codigo", "Descripcion", "Costo", "Desc1", "Desc2"),
                filaExtra("AJU" + s, "x", n("1000"), n("-5"), n("3")))));
        ObjectNode datos = datosCon(proveedor, mapeoConAjustes);
        datos.put("bonificacion1", n("10"));

        aplicarCon(dueA, lista, datosAplicarCon(datos, conteos9(1, 0, 0, 0, 1, 0, 0, 0, 0), false, null)).andExpect(status().isCreated());

        assertThat(decimal(conAjustes, "costo")).isEqualByComparingTo("880.65");
        assertThat(decimal(conAjustes, "precio_final")).isEqualByComparingTo("1390");
        assertThat(decimal(conAjustes, "ajuste1")).isEqualByComparingTo("-5");
        assertThat(decimal(conAjustes, "ajuste2")).isEqualByComparingTo("3");

        // Sin columnas de ajuste el -5 del articulo se conserva: 1.000 x 0,95 = 950 (sin la bonificacion de antes).
        MockMultipartFile soloCosto = archivo("lista.xlsx", ListasDePrueba.xlsx(List.of(ENCABEZADO,
                fila("CON" + s, "x", n("1000")))));
        ObjectNode sinColumnas = datosVistaPrevia(proveedor);
        aplicarCon(dueA, soloCosto, datosAplicarCon(sinColumnas, conteos9(1, 0, 0, 0, 1, 0, 0, 0, 0), false, null))
                .andExpect(status().isCreated());
        assertThat(decimal(conservado, "ajuste1")).isEqualByComparingTo("-5");
        assertThat(decimal(conservado, "costo")).isEqualByComparingTo("950");

        // Con la columna mapeada y la celda vacia, el ajuste se borra.
        MockMultipartFile celdaVacia = archivo("lista.xlsx", ListasDePrueba.xlsx(List.of(
                filaExtra("Codigo", "Descripcion", "Costo", "Desc1"), filaExtra("CON" + s, "x", n("1000"), null))));
        ObjectNode conColumna = datosCon(proveedor, mapeoNode(null, null, 3, null, null, null));
        aplicarCon(dueA, celdaVacia, datosAplicarCon(conColumna, conteos9(1, 0, 0, 0, 1, 0, 0, 0, 0), false, null))
                .andExpect(status().isCreated());
        assertThat(decimal(conservado, "ajuste1")).isNull();
        assertThat(decimal(conservado, "costo")).isEqualByComparingTo("1000");
    }

    @Test
    void aplicarSinFilasElegidasDa400ConUnaListaVaciaOHojaOFilaInexistentesOConError() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        String s = unico("");
        long articulo = articuloDe(dueA, proveedor, "A" + s, "100");
        List<List<Object>> filas = List.of(ENCABEZADO, fila("A" + s, "x", n("120")), fila("ERR" + s, "x", "abc"));
        MockMultipartFile lista = xlsx(filas);
        ObjectNode datos = datosVistaPrevia(proveedor);
        ObjectNode conteos = conteos9(0, 0, 0, 0, 0, 1, 0, 0, 0);

        // Lista vacia.
        ObjectNode vacia = datosAplicarCon(datos, conteos, false, null);
        vacia.set("filasElegidas", objectMapper.createArrayNode());
        aplicarCon(dueA, lista, vacia).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("Elegí al menos una fila.")));
        // Una fila que no existe, y una hoja que no existe.
        aplicarCon(dueA, lista, datosAplicarCon(datos, conteos, false, null, 0, 4000))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message",
                        is("La fila 4000 de la hoja 1 no está en la lista. Volvé a generar la vista previa.")));
        aplicarCon(dueA, lista, datosAplicarCon(datos, conteos, false, null, 5, 2))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message",
                        is("La fila 2 de la hoja 6 no está en la lista. Volvé a generar la vista previa.")));
        // Una fila con error (la 3), con los contadores de lo elegido.
        aplicarCon(dueA, lista, datosAplicarCon(datos, conteos9(0, 0, 0, 0, 0, 1, 0, 0, 0), false, null, 0, 3))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("tiene un error")));

        assertThat(decimal(articulo, "costo")).isEqualByComparingTo("100");
        assertThat(contar("select count(*) from importacion_lista where proveedor_id = ?", proveedor)).isZero();
    }

    @Test
    void siLaListaCambioDesdeLaVistaPreviaDa409SinEscribirNada() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        String s = unico("");
        long articulo = articuloDe(dueA, proveedor, "A" + s, "100");
        MockMultipartFile lista = xlsx(List.of(ENCABEZADO, fila("A" + s, "x", n("120"))));
        ObjectNode datos = datosVistaPrevia(proveedor);
        JsonNode previa = vistaPreviaDe(dueA, lista, datos);
        // Otro usuario edita el costo del articulo: la fila ya no sube.
        jdbc.update("update articulo set costo = 120.00 where id = ?", articulo);
        Map<String, Object> antes = instantanea(proveedor);

        aplicarCon(dueA, lista, datosAplicarCon(datos, previa.get("conteos"), false, null, 0, 2))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is(DERIVA)));

        assertThat(instantanea(proveedor)).isEqualTo(antes);
    }

    @Test
    void sinFilasElegidasEnElPedidoSeAplicanTodas() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        String s = unico("");
        long uno = articuloDe(dueA, proveedor, "U" + s, "100");
        long dos = articuloDe(dueA, proveedor, "D" + s, "100");
        MockMultipartFile lista = xlsx(List.of(ENCABEZADO, fila("U" + s, "x", n("120")), fila("D" + s, "x", n("130"))));
        ObjectNode datos = datosVistaPrevia(proveedor);
        JsonNode previa = vistaPreviaDe(dueA, lista, datos);

        aplicarCon(dueA, lista, datosAplicarCon(datos, previa.get("conteos"), false, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conteos.suben", is(2)));

        assertThat(decimal(uno, "costo")).isEqualByComparingTo("120");
        assertThat(decimal(dos, "costo")).isEqualByComparingTo("130");
    }

    @Test
    void aplicarDosVecesLaMismaSeleccionNoRepiteCambiosNiDuplicaHistorial() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        String s = unico("");
        long articulo = articuloDe(dueA, proveedor, "A" + s, "100");
        MockMultipartFile lista = xlsx(List.of(ENCABEZADO, fila("A" + s, "x", n("120"))));
        ObjectNode datos = datosVistaPrevia(proveedor);
        JsonNode primera = vistaPreviaDe(dueA, lista, datos);
        aplicarCon(dueA, lista, datosAplicarCon(datos, primera.get("conteos"), false, null, 0, 2)).andExpect(status().isCreated());

        // Con los mismos contadores ya no vale (la fila paso a "sin cambio"): 409.
        aplicarCon(dueA, lista, datosAplicarCon(datos, primera.get("conteos"), false, null, 0, 2))
                .andExpect(status().isConflict());
        // Con los de una vista previa nueva aplica, pero sin tocar el historial.
        JsonNode segunda = vistaPreviaDe(dueA, lista, datos);
        aplicarCon(dueA, lista, datosAplicarCon(datos, segunda.get("conteos"), false, null, 0, 2)).andExpect(status().isCreated());

        assertThat(historialImportacion(articulo)).isEqualTo(1);
        assertThat(decimal(articulo, "costo")).isEqualByComparingTo("120");
    }

    @Test
    void aplicarConFilasElegidasEsDelDuenoElVendedorRecibe403YSinTokenDa401() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        ObjectNode datos = datosAplicarCon(datosVistaPrevia(proveedor), conteos9(0, 0, 0, 0, 0, 0, 0, 0, 0), false, null, 0, 2);

        aplicarCon(venA, lista("V-1", "100"), datos).andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.multipart(APLICAR).file(lista("V-1", "100"))).andExpect(status().isUnauthorized());
    }

    // --- T-141: aislamiento de aplicar ---

    @Test
    void aplicarConElProveedorOElRubroDeBDa404YNoEscribeNadaEnNingunaFerreteria() throws Exception {
        sembrarAyB();
        long proveedorA = crearProveedor(dueA, unico("PA"));
        long proveedorB = crearProveedor(dueB, unico("PB"));
        long rubroB = crearRubro(dueB, unico("RB"), "40");
        String s = unico("");
        long articuloB = articuloDe(dueB, proveedorB, "AJ" + s, "100");
        Map<String, Object> articuloAntes = estadoDeArticulo(articuloB);
        long historialAntes = contar("select count(*) from historial_precio where ferreteria_id in (?, ?)", ferreteriaA, ferreteriaB);
        MockMultipartFile lista = xlsx(List.of(ENCABEZADO, fila("AJ" + s, "x", n("500"))));

        aplicarCon(dueA, lista, datosAplicarCon(datosVistaPrevia(proveedorB), conteos9(1, 0, 0, 0, 0, 0, 0, 0, 0), false, null, 0, 2))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message", is("Proveedor no encontrado")));
        aplicarCon(dueA, lista, datosAplicarCon(datosVistaPrevia(proveedorA), conteos9(0, 0, 1, 0, 0, 0, 0, 0, 0), true, rubroB, 0, 2))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.message", is("Rubro no encontrado")));

        assertThat(estadoDeArticulo(articuloB)).isEqualTo(articuloAntes);
        assertThat(contar("select count(*) from historial_precio where ferreteria_id in (?, ?)", ferreteriaA, ferreteriaB))
                .isEqualTo(historialAntes);
        assertThat(contar("select count(*) from importacion_lista where ferreteria_id in (?, ?)", ferreteriaA, ferreteriaB)).isZero();
        assertThat(contar("select count(*) from articulo where ferreteria_id = ? and codigo_proveedor = ?", ferreteriaA, "AJ" + s)).isZero();
    }

    @Test
    void conLosMismosCodigosAplicarEnANoCambiaNadaDeB() throws Exception {
        sembrarAyB();
        long proveedorA = crearProveedor(dueA, unico("PA"));
        long proveedorB = crearProveedor(dueB, unico("PB"));
        long articuloA = articuloDe(dueA, proveedorA, "T-100", "100");
        long articuloB = articuloDe(dueB, proveedorB, "T-100", "100");
        jdbc.update("update articulo set codigo_barras = '7791234567890' where id = ?", articuloB);
        Map<String, Object> antesB = estadoDeArticulo(articuloB);
        long historialB = contar("select count(*) from historial_precio where articulo_id = ?", articuloB);
        String mapeoB = jdbc.queryForObject("select mapeo_lista from proveedor where id = ?", String.class, proveedorB);
        MockMultipartFile lista = archivo("lista.xlsx", ListasDePrueba.xlsx(List.of(
                filaExtra("Codigo", "Descripcion", "Costo", "Moneda", "IVA", "Desc1", "Barras"),
                filaExtra("T-100", "x", n("10"), "USD", n("10.5"), n("-5"), "7791234567890"),
                filaExtra("T-101", "Nuevo", n("10"), "ARG", null, null, "7791234567890"))));
        ObjectNode mapeo = mapeoNode(3, 4, 5, 6, null, null);
        ObjectNode datos = datosCon(proveedorA, mapeo);
        // Un ferreteriaId por cuerpo se ignora.
        datos.put("ferreteriaId", ferreteriaB);
        JsonNode previa = vistaPreviaDe(dueA, lista, datos);

        aplicarCon(dueA, lista, datosAplicarCon(datos, previa.get("conteos"), true, null, 0, 2, 0, 3))
                .andExpect(status().isCreated());

        // A cambio y creo; B sigue exactamente igual.
        assertThat(filaDeArticulo(articuloA).get("moneda")).isEqualTo("USD");
        assertThat(estadoDeArticulo(articuloB)).isEqualTo(antesB);
        assertThat(contar("select count(*) from historial_precio where articulo_id = ?", articuloB)).isEqualTo(historialB);
        assertThat(ultimaImportacion(articuloB)).isNull();
        assertThat(jdbc.queryForObject("select mapeo_lista from proveedor where id = ?", String.class, proveedorB))
                .isEqualTo(mapeoB);
        assertThat(ultimaImportacion(articuloA)).isNotNull();
        // El codigo de barras que B tiene activo no choca con el alta de A.
        assertThat(contar("select count(*) from articulo where ferreteria_id = ? and codigo_barras = '7791234567890'", ferreteriaA))
                .isEqualTo(1);
        assertThat(contar("select count(*) from importacion_lista where ferreteria_id = ?", ferreteriaB)).isZero();
    }

    @Test
    void laSeleccionEsPorPosicionDelArchivoYElMapeoSeGuardaSoloEnElProveedorDeA() throws Exception {
        sembrarAyB();
        long proveedorA = crearProveedor(dueA, unico("PA"));
        long proveedorB = crearProveedor(dueB, unico("PB"));
        long articuloB = articuloDe(dueB, proveedorB, "SEL-1", "100");
        long articuloA = articuloDe(dueA, proveedorA, "SEL-1", "100");
        Map<String, Object> proveedorBAntes = jdbc.queryForMap("select * from proveedor where id = ?", proveedorB);
        MockMultipartFile lista = xlsx(List.of(ENCABEZADO, fila("SEL-1", "x", n("120"))));
        ObjectNode datos = datosVistaPrevia(proveedorA);
        JsonNode previa = vistaPreviaDe(dueA, lista, datos);

        // La fila (hoja 0, fila 2) apunta al articulo SEL-1 de A: nunca al de B, que tiene el mismo codigo.
        aplicarCon(dueA, lista, datosAplicarCon(datos, previa.get("conteos"), false, null, 0, 2)).andExpect(status().isCreated());

        assertThat(decimal(articuloA, "costo")).isEqualByComparingTo("120");
        assertThat(decimal(articuloB, "costo")).isEqualByComparingTo("100");
        assertThat(jdbc.queryForMap("select * from proveedor where id = ?", proveedorB)).isEqualTo(proveedorBAntes);
        assertThat(jdbc.queryForObject("select mapeo_lista from proveedor where id = ?", String.class, proveedorA)).isNotNull();
        assertThat(ultimaImportacion(articuloB)).isNull();
        // Un ferreteriaId por parametro o por header tambien se ignora.
        mvc.perform(MockMvcRequestBuilders.multipart(VISTA_PREVIA + "?ferreteriaId=" + ferreteriaB)
                        .file(lista)
                        .file(new MockMultipartFile("datos", "", "application/json", objectMapper.writeValueAsBytes(datos)))
                        .header("Authorization", "Bearer " + dueA)
                        .header("X-Ferreteria-Id", ferreteriaB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conteos.noEstan", is(0)));
    }

    // --- T-142: GET /api/importaciones ---

    /** Una importacion aplicada por HTTP con una fila que sube; devuelve su id. */
    private long importacionAplicada(String token, long proveedor, String codigoArticulo, String archivoNombre) throws Exception {
        MockMultipartFile lista = archivo(archivoNombre, ListasDePrueba.xlsx(List.of(ENCABEZADO,
                fila(codigoArticulo, "x", n("120")))));
        ObjectNode datos = datosVistaPrevia(proveedor);
        JsonNode previa = objectMapper.readTree(postMultipart(VISTA_PREVIA, token, lista, datos)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        String cuerpo = postMultipart(APLICAR, token, lista, datosAplicarCon(datos, previa.get("conteos"), false, null))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(cuerpo).get("id").asLong();
    }

    private void fecha(long importacionId, String fecha) {
        jdbc.update("update importacion_lista set fecha = ? where id = ?", fecha, importacionId);
    }

    @Test
    void elListadoDevuelveLasImportacionesDeLaMasNuevaALaMasVieja() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, "Bulonera " + unico("P"));
        String s = unico("");
        articuloDe(dueA, proveedor, "L1" + s, "100");
        long primera = importacionAplicada(dueA, proveedor, "L1" + s, "primera.xlsx");
        long segunda = importacionAplicada(dueA, proveedor, "L1" + s, "segunda.xlsx");
        long tercera = importacionAplicada(dueA, proveedor, "L1" + s, "tercera.xlsx");
        fecha(primera, "2026-10-01 09:00:00");
        fecha(segunda, "2026-10-10 09:00:00");
        fecha(tercera, "2026-10-05 09:00:00");

        getResult("/api/importaciones", dueA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(3)))
                .andExpect(jsonPath("$[0].id", is((int) segunda)))
                .andExpect(jsonPath("$[1].id", is((int) tercera)))
                .andExpect(jsonPath("$[2].id", is((int) primera)))
                .andExpect(jsonPath("$[0].archivo", is("segunda.xlsx")))
                .andExpect(jsonPath("$[0].estado", is("APLICADA")))
                .andExpect(jsonPath("$[0].proveedorId", is((int) proveedor)))
                .andExpect(jsonPath("$[0].proveedorNombre", org.hamcrest.Matchers.startsWith("Bulonera ")))
                .andExpect(jsonPath("$[0].usuarioId").isNumber())
                .andExpect(jsonPath("$[0].fecha").exists());
    }

    @Test
    void conProveedorIdSoloListaLasDeEseProveedor() throws Exception {
        sembrarAyB();
        long proveedor1 = crearProveedor(dueA, unico("P1"));
        long proveedor2 = crearProveedor(dueA, unico("P2"));
        String s = unico("");
        articuloDe(dueA, proveedor1, "L1" + s, "100");
        articuloDe(dueA, proveedor2, "L2" + s, "100");
        importacionAplicada(dueA, proveedor1, "L1" + s, "uno-a.xlsx");
        importacionAplicada(dueA, proveedor1, "L1" + s, "uno-b.xlsx");
        long deDos = importacionAplicada(dueA, proveedor2, "L2" + s, "dos.xlsx");

        getResult("/api/importaciones?proveedorId=" + proveedor2, dueA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(1)))
                .andExpect(jsonPath("$[0].id", is((int) deDos)));
        getResult("/api/importaciones", dueA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(3)));
    }

    @Test
    void elListadoTraeLosContadoresDeLoAplicadoIncluidosLosNuevos() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        long usuario = contar("select id from usuario where email = 'dueno-t78a@test.local'");
        jdbc.update("insert into importacion_lista (ferreteria_id, proveedor_id, usuario_id, archivo, suben, bajan, nuevos, "
                + "sin_cambio, no_estan, con_error, cambian_moneda, corrigen, salteadas, estado) "
                + "values (?, ?, ?, 'lista-336.xlsx', 200, 0, 2700, 100, 5, 7, 3, 164, 42, 'APLICADA')",
                ferreteriaA, proveedor, usuario);

        getResult("/api/importaciones?proveedorId=" + proveedor, dueA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].conteos.suben", is(200)))
                .andExpect(jsonPath("$[0].conteos.nuevos", is(2700)))
                .andExpect(jsonPath("$[0].conteos.sinCambio", is(100)))
                .andExpect(jsonPath("$[0].conteos.noEstan", is(5)))
                .andExpect(jsonPath("$[0].conteos.conError", is(7)))
                .andExpect(jsonPath("$[0].conteos.cambianMoneda", is(3)))
                .andExpect(jsonPath("$[0].conteos.corrigen", is(164)))
                .andExpect(jsonPath("$[0].conteos.salteadas", is(42)))
                .andExpect(jsonPath("$[0].usuarioId", is((int) usuario)));
    }

    @Test
    void elListadoEsDelDuenoElVendedorRecibe403YSinTokenDa401() throws Exception {
        sembrarAyB();

        getResult("/api/importaciones", dueA).andExpect(status().isOk());
        getResult("/api/importaciones", venA).andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.get("/api/importaciones")).andExpect(status().isUnauthorized());
    }

    @Test
    void unProveedorInexistenteEnElListadoDa404() throws Exception {
        sembrarAyB();

        getResult("/api/importaciones?proveedorId=999999999", dueA)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Proveedor no encontrado")));
    }

    @Test
    void sinImportacionesElListadoEstaVacio() throws Exception {
        sembrarAyB();

        getResult("/api/importaciones", dueB).andExpect(status().isOk()).andExpect(jsonPath("$.length()", is(0)));
    }

    // --- T-143: aislamiento del listado ---

    @Test
    void elDuenoDeAVeSoloLasImportacionesDeASinImportarElProveedorNiElParametro() throws Exception {
        sembrarAyB();
        long proveedorA = crearProveedor(dueA, unico("PA"));
        long proveedorB = crearProveedor(dueB, unico("PB"));
        String s = unico("");
        articuloDe(dueA, proveedorA, "IA" + s, "100");
        articuloDe(dueB, proveedorB, "IB" + s, "100");
        long deA = importacionAplicada(dueA, proveedorA, "IA" + s, "de-a.xlsx");
        long deB = importacionAplicada(dueB, proveedorB, "IB" + s, "de-b.xlsx");

        getResult("/api/importaciones", dueA)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(1)))
                .andExpect(jsonPath("$[0].id", is((int) deA)));
        getResult("/api/importaciones", dueB)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(1)))
                .andExpect(jsonPath("$[0].id", is((int) deB)));
        // Un ferreteriaId por parametro o por header se ignora.
        mvc.perform(MockMvcRequestBuilders.get("/api/importaciones?ferreteriaId=" + ferreteriaB)
                        .header("Authorization", "Bearer " + dueA).header("X-Ferreteria-Id", ferreteriaB))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(1)))
                .andExpect(jsonPath("$[0].id", is((int) deA)));
    }

    @Test
    void conElProveedorDeBElListadoDeADa404() throws Exception {
        sembrarAyB();
        long proveedorB = crearProveedor(dueB, unico("PB"));
        String s = unico("");
        articuloDe(dueB, proveedorB, "IB" + s, "100");
        importacionAplicada(dueB, proveedorB, "IB" + s, "de-b.xlsx");

        getResult("/api/importaciones?proveedorId=" + proveedorB, dueA)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Proveedor no encontrado")));
    }

    @Test
    void cuandoAAplicaLaUltimaImportacionDeUnArticuloDeBNoCambia() throws Exception {
        sembrarAyB();
        long proveedorA = crearProveedor(dueA, unico("PA"));
        long proveedorB = crearProveedor(dueB, unico("PB"));
        String s = unico("");
        articuloDe(dueA, proveedorA, "IA" + s, "100");
        long articuloB = articuloDe(dueB, proveedorB, "IA" + s, "100");
        Map<String, Object> antes = estadoDeArticulo(articuloB);

        long importacion = importacionAplicada(dueA, proveedorA, "IA" + s, "de-a.xlsx");

        assertThat(ultimaImportacion(articuloB)).isNull();
        assertThat(ultimaImportacion(articuloB)).isNotEqualTo(String.valueOf(importacion));
        assertThat(estadoDeArticulo(articuloB)).isEqualTo(antes);
    }

    // --- T-145: la guarda de dolares ya no existe (resto de los casos) ---

    @Test
    void conLaCotizacionApagadaUnExistenteYaEnDolaresSeActualizaYUnaFilaNuevaEnDolaresDaErrorSinFrenarElResto() throws Exception {
        sembrarAyB();
        configuracion(ferreteriaA, c -> {
            c.setActiva(false);
            c.setCasaPrecios(CASA_DE_PRUEBA);
            c.fijarVigente(new BigDecimal("1000.0000"), CASA_DE_PRUEBA, LadoCotizacion.VENTA, HACE_UN_RATO);
        });
        long proveedor = crearProveedor(dueA, unico("P"));
        String s = unico("");
        long enDolares = articuloDe(dueA, proveedor, "USD" + s, "100");
        jdbc.update("update articulo set moneda = 'USD', costo = 10.00, precio_final = 15730.00 where id = ?", enDolares);
        MockMultipartFile lista = archivo("lista.xlsx", ListasDePrueba.xlsx(List.of(
                filaExtra("Codigo", "Descripcion", "Costo", "Moneda"),
                filaExtra("USD" + s, "x", n("12"), "USD"),
                filaExtra("NUEVO-USD" + s, "x", n("10"), "USD"),
                filaExtra("NUEVO-ARS" + s, "x", n("10"), "ARG"))));
        ObjectNode datos = datosCon(proveedor, mapeoNode(3, null, null, null, null, null));

        JsonNode previa = vistaPreviaDe(dueA, lista, datos);

        assertConteos(previa.get("conteos"), 1, 0, 1, 0, 0, 1);
        assertThat(previa.get("errores").get(0).get("mensaje").asText())
                .isEqualTo("Activá la cotización para cargar artículos en dólares");
        assertThat(previa.get("cambios").get(0).get("precioNuevo").decimalValue()).isEqualByComparingTo("18880");
        aplicarCon(dueA, lista, datosAplicarCon(datos, previa.get("conteos"), true, null)).andExpect(status().isCreated());
        assertThat(decimal(enDolares, "costo")).isEqualByComparingTo("12");
        assertThat(decimal(enDolares, "precio_final")).isEqualByComparingTo("18880");
    }

    @Test
    void sinNingunaCotizacionLaFilaEnDolaresDaErrorYLasDePesosSeImportan() throws Exception {
        sembrarAyB();
        // El tipo de dolar de A no tiene ningun valor guardado y A nunca fijo un dolar vigente.
        configuracion(ferreteriaA, c -> c.setCasaPrecios("t78-no-existe"));
        long proveedor = crearProveedor(dueA, unico("P"));
        String s = unico("");
        MockMultipartFile lista = archivo("lista.xlsx", ListasDePrueba.xlsx(List.of(
                filaExtra("Codigo", "Descripcion", "Costo", "Moneda"),
                filaExtra("USD" + s, "x", n("10"), "USD"),
                filaExtra("ARS" + s, "x", n("10"), "ARG"))));
        ObjectNode datos = datosCon(proveedor, mapeoNode(3, null, null, null, null, null));

        JsonNode previa = vistaPreviaDe(dueA, lista, datos);

        assertConteos(previa.get("conteos"), 0, 0, 1, 0, 0, 1);
        assertThat(previa.get("errores").get(0).get("mensaje").asText())
                .isEqualTo("Todavía no hay cotización del dólar. Probá en unos minutos");
        assertThat(previa.get("nuevos").get(0).get("codigo").asText()).isEqualTo("ARS" + s);
    }

    // --- Revision de los lotes 4 y 5: precio desactualizado, rubro en la vista previa y solo nuevas sin crear ---

    @Test
    void unPrecioGuardadoDesactualizadoEsUnaCorreccionYAplicarLoCorrigeCon1DeHistorial() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        String codigo = unico("OBS");
        long articulo = articuloDe(dueA, proveedor, codigo, "100");
        jdbc.update("update articulo set precio_final = 1.00 where id = ?", articulo);
        MockMultipartFile lista = xlsx(List.of(ENCABEZADO, fila(codigo, "x", n("100"))));
        ObjectNode datos = datosVistaPrevia(proveedor);
        long historialAntes = contar("select count(*) from historial_precio where articulo_id = ?", articulo);

        JsonNode previa = vistaPreviaDe(dueA, lista, datos);

        assertThat(previa.get("conteos").get("corrigen").asInt()).isEqualTo(1);
        assertThat(previa.get("conteos").get("sinCambio").asInt()).isZero();
        assertThat(previa.get("correcciones")).hasSize(1);
        assertThat(previa.get("correcciones").get(0).get("precioAnterior").decimalValue()).isEqualByComparingTo("1");
        assertThat(previa.get("correcciones").get(0).get("precioNuevo").decimalValue()).isEqualByComparingTo("160");
        assertThat(decimal(articulo, "precio_final")).isEqualByComparingTo("1");

        aplicarCon(dueA, lista, datosAplicarCon(datos, previa.get("conteos"), false, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conteos.corrigen", is(1)))
                .andExpect(jsonPath("$.conteos.sinCambio", is(0)));

        assertThat(decimal(articulo, "precio_final")).isEqualByComparingTo("160");
        assertThat(contar("select count(*) from historial_precio where articulo_id = ?", articulo))
                .isEqualTo(historialAntes + 1);
        Map<String, Object> historial = jdbc.queryForMap(
                "select * from historial_precio where origen = 'IMPORTACION' and articulo_id = ?", articulo);
        assertThat((BigDecimal) historial.get("precio_anterior")).isEqualByComparingTo("1");
        assertThat((BigDecimal) historial.get("precio_nuevo")).isEqualByComparingTo("160");
    }

    @Test
    void unaFilaSinCambioYUnaCorreccionDeIvaNeutraNuncaDejanHistorialNiMuevenElPrecioFinalAlAplicar() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        String s = unico("");
        long igual = articuloDe(dueA, proveedor, "IGU" + s, "100");
        // 1 x 1,30 x 1,21 = 1,573 y 1 x 1,30 x 1,105 = 1,4365: los dos redondean a 10, asi que el cambio de IVA no mueve el
        // precio. Es "Corrige" igual (decision 37): con esa alicuota se factura, y el precio no cambia, asi que no hay historial.
        long ivaSinEfecto = articuloDe(dueA, proveedor, "IVA" + s, "1");
        MockMultipartFile lista = archivo("lista.xlsx", ListasDePrueba.xlsx(List.of(
                filaExtra("Codigo", "Descripcion", "Costo", "IVA"),
                filaExtra("IGU" + s, "x", n("100"), null),
                filaExtra("IVA" + s, "x", n("1"), n("10.5")))));
        ObjectNode datos = datosCon(proveedor, mapeoNode(null, 3, null, null, null, null));
        BigDecimal precioIgual = decimal(igual, "precio_final");
        BigDecimal precioIva = decimal(ivaSinEfecto, "precio_final");
        long historialAntes = contar("select count(*) from historial_precio where articulo_id in (?, ?)", igual, ivaSinEfecto);

        JsonNode previa = vistaPreviaDe(dueA, lista, datos);
        assertThat(previa.get("conteos").get("sinCambio").asInt()).isEqualTo(1);
        assertThat(previa.get("conteos").get("corrigen").asInt()).isEqualTo(1);
        assertThat(previa.get("correcciones")).hasSize(1);
        assertThat(previa.get("correcciones").get(0).get("alicuotaAnterior").asText()).isEqualTo("IVA_21");
        assertThat(previa.get("correcciones").get(0).get("alicuotaNueva").asText()).isEqualTo("IVA_10_5");
        assertThat(previa.get("sinCambio")).hasSize(1);
        assertThat(previa.get("sinCambio").get(0).get("codigo").asText()).isEqualTo("IGU" + s);

        aplicarCon(dueA, lista, datosAplicarCon(datos, previa.get("conteos"), false, null))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conteos.sinCambio", is(1)))
                .andExpect(jsonPath("$.conteos.corrigen", is(1)));

        assertThat(contar("select count(*) from historial_precio where articulo_id in (?, ?)", igual, ivaSinEfecto))
                .isEqualTo(historialAntes);
        assertThat(decimal(igual, "precio_final")).isEqualByComparingTo(precioIgual);
        assertThat(decimal(ivaSinEfecto, "precio_final")).isEqualByComparingTo(precioIva);
        assertThat(precioIva).isEqualByComparingTo("10");
        // La alicuota nueva si se guarda (es una correccion), sin historial porque el precio no cambia.
        assertThat(filaDeArticulo(ivaSinEfecto).get("alicuota_iva")).isEqualTo("IVA_10_5");
        assertThat(filaDeArticulo(igual).get("alicuota_iva")).isEqualTo("IVA_21");
    }

    @Test
    void laVistaPreviaConRubroCalculaElPrecioDeLosNuevosConSuMargenYAplicarCreaLoMismo() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        long rubro = crearRubro(dueA, unico("R"), "40");
        String codigo = unico("NRU");
        MockMultipartFile lista = xlsx(List.of(ENCABEZADO, fila(codigo, "Nuevo", n("100"))));
        ObjectNode sinRubro = datosVistaPrevia(proveedor);
        ObjectNode conRubro = datosVistaPrevia(proveedor);
        conRubro.put("rubroId", rubro);

        JsonNode previaSinRubro = vistaPreviaDe(dueA, lista, sinRubro);
        JsonNode previaConRubro = vistaPreviaDe(dueA, lista, conRubro);

        // 100 x 1,30 x 1,21 = 157,3 -> 160 con el margen por defecto; 100 x 1,40 x 1,21 = 169,4 -> 170 con el del rubro.
        assertThat(previaSinRubro.get("nuevos").get(0).get("precioNuevo").decimalValue()).isEqualByComparingTo("160");
        assertThat(previaConRubro.get("nuevos").get(0).get("precioNuevo").decimalValue()).isEqualByComparingTo("170");
        assertThat(previaConRubro.get("conteos")).isEqualTo(previaSinRubro.get("conteos"));

        aplicarCon(dueA, lista, datosAplicarCon(sinRubro, previaConRubro.get("conteos"), true, rubro))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conteos.nuevos", is(1)));

        Map<String, Object> creado = jdbc.queryForMap("select * from articulo where codigo_interno = ?", codigo);
        assertThat((BigDecimal) creado.get("precio_final")).isEqualByComparingTo("170");
        assertThat(((Number) creado.get("rubro_id")).longValue()).isEqualTo(rubro);
    }

    @Test
    void laVistaPreviaConRubroYLaDeAntesSinRubroIdDanLoMismoParaLosExistentes() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        long rubro = crearRubro(dueA, unico("R"), "40");
        String codigo = unico("EXI");
        articuloDe(dueA, proveedor, codigo, "100");
        MockMultipartFile lista = xlsx(List.of(ENCABEZADO, fila(codigo, "x", n("120"))));
        ObjectNode conRubro = datosVistaPrevia(proveedor);
        conRubro.put("rubroId", rubro);

        JsonNode sinRubroId = vistaPreviaDe(dueA, lista, datosVistaPrevia(proveedor));
        JsonNode conRubroId = vistaPreviaDe(dueA, lista, conRubro);

        assertThat(conRubroId.get("cambios")).isEqualTo(sinRubroId.get("cambios"));
        assertThat(sinRubroId.get("cambios").get(0).get("precioNuevo").decimalValue()).isEqualByComparingTo("190");
    }

    @Test
    void laVistaPreviaConUnRubroDeOtraFerreteriaDa404ComoAplicarYNoEscribeNada() throws Exception {
        sembrarAyB();
        long proveedorA = crearProveedor(dueA, unico("PA"));
        long rubroB = crearRubro(dueB, unico("RB"), "40");
        String codigo = unico("AJE");
        articuloDe(dueA, proveedorA, codigo, "100");
        MockMultipartFile lista = xlsx(List.of(ENCABEZADO, fila(codigo, "x", n("120"))));
        ObjectNode datos = datosVistaPrevia(proveedorA);
        datos.put("rubroId", rubroB);
        Map<String, Object> antes = instantanea(proveedorA);

        postMultipart(VISTA_PREVIA, dueA, lista, datos)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Rubro no encontrado")));
        // Aplicar con el mismo rubro ajeno responde igual.
        aplicarCon(dueA, lista, datosAplicarCon(datosVistaPrevia(proveedorA), conteos9(1, 0, 0, 0, 0, 0, 0, 0, 0), true, rubroB))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message", is("Rubro no encontrado")));

        assertThat(instantanea(proveedorA)).isEqualTo(antes);
    }

    @Test
    void sinCrearNuevosElegirSoloFilasNuevasDa400SinRegistrarImportacionNiPisarElMapeo() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        String s = unico("");
        articuloDe(dueA, proveedor, "A" + s, "100");
        MockMultipartFile lista = xlsx(List.of(ENCABEZADO, fila("A" + s, "x", n("120")), fila("N" + s, "Nuevo", n("50"))));
        ObjectNode datos = datosVistaPrevia(proveedor);
        JsonNode previa = vistaPreviaDe(dueA, lista, datos);
        assertThat(previa.get("conteos").get("nuevos").asInt()).isEqualTo(1);
        Map<String, Object> antes = instantanea(proveedor);

        // Solo la fila 3 (la nueva) y sin crear los nuevos: nada para aplicar.
        ObjectNode soloLaNueva = datosAplicarCon(datos, conteos9(0, 0, 1, 0, 0, 0, 0, 0, 0), false, null, 0, 3);
        aplicarCon(dueA, lista, soloLaNueva)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("Elegí al menos una fila.")));

        assertThat(instantanea(proveedor)).isEqualTo(antes);
        assertThat(contar("select count(*) from importacion_lista where proveedor_id = ?", proveedor)).isZero();
        assertThat(jdbc.queryForMap("select * from proveedor where id = ?", proveedor).get("mapeo_lista")).isNull();

        // Con la misma seleccion pero creando los nuevos si se aplica, y la nueva se crea.
        aplicarCon(dueA, lista, datosAplicarCon(datos, conteos9(0, 0, 1, 0, 0, 0, 0, 0, 0), true, null, 0, 3))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conteos.nuevos", is(1)));
        assertThat(contar("select count(*) from articulo where proveedor_id = ? and codigo_interno = ?", proveedor, "N" + s))
                .isEqualTo(1);
    }

    // =====================================================================================================
    // Corte 3, lote 3 (T-214 a T-216): la importacion guarda en cada articulo la base con la que calculo el costo
    // =====================================================================================================

    private void assertBase(long articulo, String b1, String b2, String b3, Boolean incluyeIva) {
        for (String[] columna : new String[][] {{"bonificacion1", b1}, {"bonificacion2", b2}, {"bonificacion3", b3}}) {
            if (columna[1] == null) {
                assertThat(decimal(articulo, columna[0])).as(columna[0]).isNull();
            } else {
                assertThat(decimal(articulo, columna[0])).as(columna[0]).isEqualByComparingTo(columna[1]);
            }
        }
        assertThat(filaDeArticulo(articulo).get("lista_incluye_iva")).as("lista_incluye_iva").isEqualTo(incluyeIva);
    }

    /** Deja al articulo con una base guardada de partida (la que tendria de una importacion anterior). */
    private void conBase(long articulo, String b1, Boolean incluyeIva) {
        jdbc.update("update articulo set bonificacion1 = ?, bonificacion2 = NULL, bonificacion3 = NULL,"
                + " lista_incluye_iva = ? where id = ?", b1 == null ? null : new BigDecimal(b1), incluyeIva, articulo);
    }

    /** Como {@link #conBase}, pero tambien con la segunda y la tercera bonificacion de partida. */
    private void conBaseCompleta(long articulo, String b1, String b2, String b3, Boolean incluyeIva) {
        jdbc.update("update articulo set bonificacion1 = ?, bonificacion2 = ?, bonificacion3 = ?,"
                + " lista_incluye_iva = ? where id = ?", new BigDecimal(b1), new BigDecimal(b2), new BigDecimal(b3),
                incluyeIva, articulo);
    }

    /** Vista previa y aplicar como el Dueno de A, con los contadores que devolvio la vista previa. */
    private ResultActions aplicarComoA(MockMultipartFile lista, ObjectNode datos, boolean crearNuevos) throws Exception {
        JsonNode previa = vistaPreviaDe(dueA, lista, datos);
        return aplicarCon(dueA, lista, datosAplicarCon(datos, previa.get("conteos"), crearNuevos, null));
    }

    private void masivoComoA(long proveedor, String modo, String valor) throws Exception {
        ObjectNode pedido = objectMapper.createObjectNode()
                .put("proveedorId", proveedor).put("modo", modo).put("valor", new BigDecimal(valor));
        String huella = objectMapper.readTree(texto(postJson("/api/actualizacion-masiva/vista-previa", dueA,
                objectMapper.writeValueAsString(pedido)).andExpect(status().isOk()))).get("huella").asText();
        pedido.put("huella", huella);
        postJson("/api/actualizacion-masiva", dueA, objectMapper.writeValueAsString(pedido))
                .andExpect(status().isCreated());
    }

    private long idPorCodigo(long proveedor, String codigoProveedor) {
        return contar("select id from articulo where proveedor_id = ? and codigo_proveedor = ?", proveedor, codigoProveedor);
    }

    // --- T-214: nuevos, actualizados, cambios de moneda y Corrige ---

    @Test
    void unArticuloNuevoQuedaConLasBonificacionesDelProveedorYLaListaSinIva() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        String codigo = unico("NUE");
        ObjectNode datos = datosVistaPrevia(proveedor);
        datos.put("bonificacion1", n("10")).put("bonificacion2", n("5")).put("bonificacion3", n("2"));

        aplicarComoA(xlsx(conEncabezado(fila(codigo, "Nuevo", n("1000")))), datos, true)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conteos.nuevos", is(1)));

        long articulo = idPorCodigo(proveedor, codigo);
        assertBase(articulo, "10", "5", "2", false);
        assertThat(decimal(articulo, "costo")).isEqualByComparingTo("837.90");
    }

    @Test
    void unArticuloNuevoConLaListaConIvaGuardaListaIncluyeIvaTrue() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        String codigo = unico("NIV");
        ObjectNode datos = datosVistaPrevia(proveedor);
        datos.put("listaIncluyeIva", true);

        aplicarComoA(xlsx(conEncabezado(fila(codigo, "Nuevo", n("1210")))), datos, true)
                .andExpect(status().isCreated());

        long articulo = idPorCodigo(proveedor, codigo);
        assertBase(articulo, null, null, null, true);
        assertThat(decimal(articulo, "costo")).isEqualByComparingTo("1000.00");
    }

    @Test
    void subeBajaYCambioDeMonedaQuedanConLasBonificacionesDelProveedorDeAhoraYNoLasViejas() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        String s = unico("");
        long sube = articuloDe(dueA, proveedor, "SU" + s, "100");
        long baja = articuloDe(dueA, proveedor, "BA" + s, "100");
        long moneda = articuloDe(dueA, proveedor, "MO" + s, "100");
        for (long articulo : List.of(sube, baja, moneda)) {
            conBaseCompleta(articulo, "10", "8", "3", true);
        }
        MockMultipartFile lista = archivo("lista.xlsx", ListasDePrueba.xlsx(List.of(
                filaExtra("Codigo", "Descripcion", "Costo", "Moneda"),
                filaExtra("SU" + s, "x", n("1000"), "ARS"),
                filaExtra("BA" + s, "x", n("50"), "ARS"),
                filaExtra("MO" + s, "x", n("10"), "USD"))));
        ObjectNode datos = datosCon(proveedor, mapeoNode(3, null, null, null, null, null));
        datos.put("bonificacion1", n("12"));

        aplicarComoA(lista, datos, false)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conteos.suben", is(1)))
                .andExpect(jsonPath("$.conteos.bajan", is(1)))
                .andExpect(jsonPath("$.conteos.cambianMoneda", is(1)));

        assertThat(decimal(sube, "costo")).isEqualByComparingTo("880.00");
        assertThat(decimal(baja, "costo")).isEqualByComparingTo("44.00");
        assertThat(decimal(moneda, "costo")).isEqualByComparingTo("8.80");
        for (long articulo : List.of(sube, baja, moneda)) {
            assertBase(articulo, "12", null, null, false);
        }
    }

    @Test
    void unaCorreccionDeIvaTambienGuardaLaBaseDelProveedorDeEseMomento() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        String codigo = unico("COR");
        long articulo = articuloDe(dueA, proveedor, codigo, "100");
        conBase(articulo, "7", true);
        MockMultipartFile lista = archivo("lista.xlsx", ListasDePrueba.xlsx(List.of(
                filaExtra("Codigo", "Descripcion", "Costo", "IVA"), filaExtra(codigo, "x", n("200"), n("10.5")))));
        ObjectNode datos = datosCon(proveedor, mapeoNode(null, 3, null, null, null, null));
        datos.put("bonificacion1", n("50"));

        aplicarComoA(lista, datos, false)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conteos.corrigen", is(1)));

        assertThat(decimal(articulo, "costo")).isEqualByComparingTo("100.00");
        assertBase(articulo, "50", null, null, false);
        assertThat(ultimaImportacion(articulo)).isNotNull();
    }

    @Test
    void cambiarLaBaseDelProveedorSinImportarNoMueveLaDelArticulo() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        String codigo = unico("DER");
        ObjectNode datos = datosVistaPrevia(proveedor);
        datos.put("bonificacion1", n("10"));
        aplicarComoA(xlsx(conEncabezado(fila(codigo, "Nuevo", n("1000")))), datos, true).andExpect(status().isCreated());
        long articulo = idPorCodigo(proveedor, codigo);

        jdbc.update("update proveedor set bonificacion1 = 12, lista_incluye_iva = true where id = ?", proveedor);

        assertBase(articulo, "10", null, null, false);
    }

    @Test
    void elProveedorQuePasaDeIncluyeANoIncluyeIvaNoMueveElTrueDelArticulo() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        String codigo = unico("INV");
        ObjectNode datos = datosVistaPrevia(proveedor);
        datos.put("listaIncluyeIva", true);
        aplicarComoA(xlsx(conEncabezado(fila(codigo, "Nuevo", n("1210")))), datos, true).andExpect(status().isCreated());
        long articulo = idPorCodigo(proveedor, codigo);

        jdbc.update("update proveedor set lista_incluye_iva = false where id = ?", proveedor);

        assertBase(articulo, null, null, null, true);
    }

    @Test
    void unArticuloEnFalseQuedaEnTrueSiLaImportacionDeclaraLaListaConIva() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        String codigo = unico("F2T");
        long articulo = articuloDe(dueA, proveedor, codigo, "100");
        conBase(articulo, "7", false);
        ObjectNode datos = datosVistaPrevia(proveedor);
        datos.put("listaIncluyeIva", true);

        aplicarComoA(xlsx(conEncabezado(fila(codigo, "x", n("1210")))), datos, false).andExpect(status().isCreated());

        assertBase(articulo, null, null, null, true);
        assertThat(decimal(articulo, "costo")).isEqualByComparingTo("1000.00");
    }

    // --- T-215: sin cambio, filas sin tocar, rollback, aislamiento y otras operaciones ---

    @Test
    void unaFilaSinCambioElegidaGuardaLaBaseYMarcaLaImportacion() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        String codigo = unico("SC");
        long articulo = articuloDe(dueA, proveedor, codigo, "100");
        ObjectNode datos = datosVistaPrevia(proveedor);
        datos.put("bonificacion1", n("50"));

        String cuerpo = texto(aplicarComoA(xlsx(conEncabezado(fila(codigo, "x", n("200")))), datos, false)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conteos.sinCambio", is(1))));

        assertBase(articulo, "50", null, null, false);
        assertThat(ultimaImportacion(articulo)).isEqualTo(objectMapper.readTree(cuerpo).get("id").asText());
        assertThat(decimal(articulo, "costo")).isEqualByComparingTo("100");
        assertThat(contar("select count(*) from historial_precio where origen = 'IMPORTACION' and articulo_id = ?", articulo))
                .isZero();
    }

    @Test
    void unaFilaConErrorOUnaNoElegidaNoTocanElArticuloNiSuBase() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        String s = unico("");
        long elegida = articuloDe(dueA, proveedor, "EL" + s, "100");
        long noElegida = articuloDe(dueA, proveedor, "NE" + s, "100");
        long conError = articuloDe(dueA, proveedor, "ER" + s, "100");
        for (long articulo : List.of(elegida, noElegida, conError)) {
            conBase(articulo, "7", false);
        }
        Map<String, Object> noElegidaAntes = filaDeArticulo(noElegida);
        Map<String, Object> conErrorAntes = filaDeArticulo(conError);
        MockMultipartFile lista = xlsx(conEncabezado(
                fila("EL" + s, "x", n("120")), fila("NE" + s, "x", n("120")), fila("ER" + s, "x", "abc")));
        ObjectNode datos = datosVistaPrevia(proveedor);
        datos.put("bonificacion1", n("12"));

        aplicarCon(dueA, lista, datosAplicarCon(datos, conteos9(1, 0, 0, 0, 0, 1, 0, 0, 0), false, null, 0, 2))
                .andExpect(status().isCreated());

        assertBase(elegida, "12", null, null, false);
        assertThat(filaDeArticulo(noElegida)).isEqualTo(noElegidaAntes);
        assertThat(filaDeArticulo(conError)).isEqualTo(conErrorAntes);
    }

    @Test
    void siFallaElHistorialNingunArticuloCambiaNiSuBase() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        String s = unico("");
        List<List<Object>> filas = new ArrayList<>();
        filas.add(ENCABEZADO);
        for (String clave : List.of("U", "D", "T")) {
            conBase(articuloDe(dueA, proveedor, clave + s, "100"), "7", true);
            filas.add(fila(clave + s, "x", n("120")));
        }
        Map<String, Object> antes = instantanea(proveedor);
        ObjectNode datos = datosVistaPrevia(proveedor);
        datos.put("bonificacion1", n("12"));
        MockMultipartFile lista = xlsx(filas);
        JsonNode previa = vistaPreviaDe(dueA, lista, datos);
        doThrow(new IllegalStateException("falla simulada")).when(historialPrecioRepository).saveAll(any());

        aplicarCon(dueA, lista, datosAplicarCon(datos, previa.get("conteos"), false, null))
                .andExpect(status().isInternalServerError());

        assertThat(instantanea(proveedor)).isEqualTo(antes);
        assertThat(contar("select count(*) from articulo where proveedor_id = ? and bonificacion1 = 7"
                + " and lista_incluye_iva = true", proveedor)).isEqualTo(3);
    }

    @Test
    void laImportacionDeUnaFerreteriaNoTocaLosArticulosDeOtraConElMismoProveedorLogico() throws Exception {
        sembrarAyB();
        String nombre = unico("P");
        long proveedorA = crearProveedor(dueA, nombre);
        long proveedorB = crearProveedor(dueB, nombre);
        String codigo = unico("ISO");
        articuloDe(dueA, proveedorA, codigo, "100");
        long deB = articuloDe(dueB, proveedorB, codigo, "100");
        conBase(deB, "7", true);
        Map<String, Object> articuloBAntes = filaDeArticulo(deB);
        Map<String, Object> proveedorBAntes = jdbc.queryForMap("select * from proveedor where id = ?", proveedorB);
        ObjectNode datos = datosVistaPrevia(proveedorA);
        datos.put("bonificacion1", n("12"));

        aplicarComoA(xlsx(conEncabezado(fila(codigo, "x", n("1000")))), datos, false).andExpect(status().isCreated());

        assertBase(idPorCodigo(proveedorA, codigo), "12", null, null, false);
        assertThat(filaDeArticulo(deB)).isEqualTo(articuloBAntes);
        assertThat(jdbc.queryForMap("select * from proveedor where id = ?", proveedorB)).isEqualTo(proveedorBAntes);
    }

    @Test
    void elMasivoLaEdicionManualYElCambioDeProveedorDejanLaBaseGuardadaIntacta() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        long otroProveedor = crearProveedor(dueA, unico("P"));
        String codigo = unico("OPE");
        ObjectNode datos = datosVistaPrevia(proveedor);
        datos.put("bonificacion1", n("10")).put("bonificacion2", n("5"));
        aplicarComoA(xlsx(conEncabezado(fila(codigo, "Nuevo", n("1000")))), datos, true).andExpect(status().isCreated());
        long articulo = idPorCodigo(proveedor, codigo);
        assertBase(articulo, "10", "5", null, false);

        masivoComoA(proveedor, "COSTO_PORCENTAJE", "10");
        assertBase(articulo, "10", "5", null, false);
        masivoComoA(proveedor, "MARGEN", "40");
        assertBase(articulo, "10", "5", null, false);

        ObjectNode pedido = objectMapper.createObjectNode()
                .put("codigoInterno", (String) filaDeArticulo(articulo).get("codigo_interno"))
                .put("codigoProveedor", codigo)
                .put("descripcion", "Editado a mano")
                .put("unidad", "UNIDAD")
                .put("proveedorId", proveedor)
                .put("margenPorcentaje", new BigDecimal("30"))
                .put("costo", new BigDecimal("700"));
        putJson("/api/articulos/" + articulo, dueA, conLaVersionVigente(articulo, objectMapper.writeValueAsString(pedido))).andExpect(status().isOk());
        assertBase(articulo, "10", "5", null, false);

        pedido.put("proveedorId", otroProveedor);
        putJson("/api/articulos/" + articulo, dueA, conLaVersionVigente(articulo, objectMapper.writeValueAsString(pedido))).andExpect(status().isOk());
        assertBase(articulo, "10", "5", null, false);
        pedido.putNull("proveedorId");
        putJson("/api/articulos/" + articulo, dueA, conLaVersionVigente(articulo, objectMapper.writeValueAsString(pedido))).andExpect(status().isOk());
        assertBase(articulo, "10", "5", null, false);
    }

    // --- T-216: las importaciones siguientes usan los ajustes editados (R-119) ---

    /** Articulo de lista 1.000, bonificacion 10 y un ajuste1 editado a mano; el costo ya es el de la lista. */
    private long conAjusteEditado(long proveedor, String codigo, String ajuste1, String costo) throws Exception {
        long articulo = articuloDe(dueA, proveedor, codigo, costo);
        jdbc.update("update articulo set precio_lista_proveedor = 1000.00, ajuste1 = ?, bonificacion1 = 10,"
                + " bonificacion2 = NULL, bonificacion3 = NULL, lista_incluye_iva = false where id = ?",
                new BigDecimal(ajuste1), articulo);
        return articulo;
    }

    @Test
    void unaListaSinColumnasDeAjusteConservaElAjusteEditadoYLoAplicaAlCostoNuevo() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        String codigo = unico("AJC");
        long articulo = conAjusteEditado(proveedor, codigo, "-5.00", "855");
        ObjectNode datos = datosVistaPrevia(proveedor);
        datos.put("bonificacion1", n("10"));

        aplicarComoA(xlsx(conEncabezado(fila(codigo, "x", n("1100")))), datos, false).andExpect(status().isCreated());

        assertThat(decimal(articulo, "costo")).isEqualByComparingTo("940.50");
        assertThat(decimal(articulo, "ajuste1")).isEqualByComparingTo("-5");
    }

    @Test
    void unaColumnaDeAjusteMapeadaConLaCeldaVaciaBorraElAjusteYElCostoSeRecalcula() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        String codigo = unico("AJV");
        long articulo = conAjusteEditado(proveedor, codigo, "-5.00", "855");
        MockMultipartFile lista = archivo("lista.xlsx", ListasDePrueba.xlsx(List.of(
                filaExtra("Codigo", "Descripcion", "Costo", "Desc1"), filaExtra(codigo, "x", n("1100"), null))));
        ObjectNode datos = datosCon(proveedor, mapeoNode(null, null, 3, null, null, null));
        datos.put("bonificacion1", n("10"));

        aplicarComoA(lista, datos, false).andExpect(status().isCreated());

        assertThat(decimal(articulo, "ajuste1")).isNull();
        assertThat(decimal(articulo, "costo")).isEqualByComparingTo("990.00");
    }

    @Test
    void unMasivoSobreUnArticuloConAjusteNoLoCambiaYElCostoYaNoCoincideConElDeLaLista() throws Exception {
        sembrarAyB();
        long proveedor = crearProveedor(dueA, unico("P"));
        long articulo = conAjusteEditado(proveedor, unico("AJM"), "-10.00", "810");

        masivoComoA(proveedor, "COSTO_PORCENTAJE", "10");

        assertThat(decimal(articulo, "ajuste1")).isEqualByComparingTo("-10");
        assertThat(decimal(articulo, "costo")).isEqualByComparingTo("891.00");
        // La lista (1.000 x 0,90 x 0,90) daria 810: el costo guardado ya no coincide.
        assertThat(decimal(articulo, "costo")).isNotEqualByComparingTo("810");
    }

    private static boolean sinValor(JsonNode nodo, String campo) {
        JsonNode valor = nodo.path(campo);
        return valor.isMissingNode() || valor.isNull();
    }
}
