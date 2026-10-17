package com.ferreteria.catalogo;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ferreteria.catalogo.repository.HistorialPrecioRepository;
import com.ferreteria.cotizacion.model.LadoCotizacion;
import com.ferreteria.support.GanchoDeChoque;
import com.ferreteria.support.ListasDePrueba;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Base de los tests de integracion del bloqueo optimista del articulo (lote 5b, decision 47). Trae la version leida con
 * GET, los pedidos de importacion y de masivo en dos pasos (vista previa y aplicar, como la pantalla) y el
 * <b>gancho de choque</b>: un espia de {@link HistorialPrecioRepository} que, en el primer {@code save} o
 * {@code saveAll} (despues de mutar los articulos y antes del flush), ejecuta algo concurrente y espera a que termine.
 * Sirve con la importacion, el masivo y el recalculo; no con el PUT de ajustes, cuyo lock lo trabaria.
 */
abstract class VersionTestBase extends AjustesTestBase {

    protected static final Instant HACE_UN_RATO = Instant.parse("2026-10-16T11:00:00Z");
    protected static final String MENSAJE_VERSION = "Otro cambio modificó este artículo. Recargá y volvé a intentar.";
    protected static final String LISTA_CAMBIO =
            "La lista cambió desde la vista previa. Volvé a generar la vista previa y confirmá de nuevo.";
    protected static final String DERIVA = "Los artículos cambiaron desde la vista previa. "
            + "Volvé a generar la vista previa y confirmá de nuevo.";

    private static final String IMPORTACIONES = "/api/importaciones";
    private static final String MASIVO = "/api/actualizacion-masiva";

    @SpyBean
    protected HistorialPrecioRepository historialPrecioRepository;

    @Autowired
    private EntityManager entityManager;

    private GanchoDeChoque gancho;

    @BeforeEach
    void armarElGancho() {
        gancho = new GanchoDeChoque(historialPrecioRepository, entityManager);
    }

    @AfterEach
    void limpiarElGancho() {
        gancho.soltar();
    }

    // ---- version y lectura ----

    protected long versionDe(Long articuloId) {
        return jdbc.queryForObject("select version from articulo where id = ?", Long.class, articuloId);
    }

    protected static String texto(ResultActions resultado) throws Exception {
        return resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    protected JsonNode json(ResultActions resultado) throws Exception {
        return objectMapper.readTree(texto(resultado));
    }

    protected JsonNode articuloJson(String token, Long id) throws Exception {
        return getJson("/api/articulos/" + id, token);
    }

    /** Un cuerpo de PUT con lo que el formulario manda, tomado del articulo tal como esta (sin version). */
    protected ObjectNode cuerpoDe(JsonNode articulo) {
        ObjectNode cuerpo = objectMapper.createObjectNode();
        for (String campo : List.of("codigoInterno", "codigoBarras", "codigoProveedor", "descripcion", "rubroId",
                "proveedorId", "costo", "margenPorcentaje", "alicuotaIva", "unidad", "stockMinimo", "ubicacion",
                "activo", "moneda")) {
            cuerpo.set(campo, articulo.get(campo));
        }
        return cuerpo;
    }

    protected ObjectNode cuerpoDe(String token, Long id) throws Exception {
        return cuerpoDe(articuloJson(token, id));
    }

    protected ResultActions putArticulo(String token, Long id, ObjectNode cuerpo) throws Exception {
        return putJson("/api/articulos/" + id, token, cuerpo.toString());
    }

    protected ResultActions putAjustes(String token, Long id, String json) throws Exception {
        return putJson("/api/articulos/" + id + "/ajustes", token, json);
    }

    // ---- siembra ----

    /** Una cotizacion propia del test: el dolar vigente es {@code vigente} y el ultimo guardado {@code ultimo}. */
    protected void conDolar(Long ferreteriaId, String casa, String vigente, String ultimo) {
        borrarCotizaciones(casa);
        cotizacion("USD", casa, ultimo, ultimo, HACE_UN_RATO);
        configuracion(ferreteriaId, c -> {
            c.setCasaPrecios(casa);
            c.fijarVigente(new BigDecimal(vigente + ".0000"), casa, LadoCotizacion.VENTA, HACE_UN_RATO);
        });
    }

    /** Un articulo importado (lista 1.000, bonificacion 10, costo 900, precio 1.420) con su codigo de proveedor. */
    protected Long deLista(Long ferreteriaId, String codigo, Long proveedorId) {
        return deLista(ferreteriaId, codigo, proveedorId, "900.00", "1420.00");
    }

    protected Long deLista(Long ferreteriaId, String codigo, Long proveedorId, String costo, String precioFinal) {
        Long id = importado(ferreteriaId, codigo, proveedorId, "1000.00", false, valores("10", null, null), NADA, costo,
                precioFinal);
        jdbc.update("update articulo set codigo_proveedor = ? where id = ?", codigo, id);
        return id;
    }

    // ---- importacion ----

    protected static List<List<Object>> lista(List<Object>... filas) {
        List<List<Object>> todas = new ArrayList<>();
        todas.add(Arrays.asList("Codigo", "Descripcion", "Costo"));
        todas.addAll(Arrays.asList(filas));
        return todas;
    }

    protected static List<Object> fila(String codigo, String descripcion, String costo) {
        return Arrays.asList(codigo, descripcion, new BigDecimal(costo));
    }

    protected static MockMultipartFile xlsx(List<List<Object>> filas) {
        return archivo("lista.xlsx", ListasDePrueba.xlsx(filas));
    }

    /** Los datos de la vista previa: codigo en A, descripcion en B, costo en C, desde la fila 2, con la bonificacion 1. */
    protected ObjectNode datosDeImportacion(Long proveedorId, String bonificacion1) {
        ObjectNode datos = objectMapper.createObjectNode().put("proveedorId", proveedorId).put("listaIncluyeIva", false);
        if (bonificacion1 != null) {
            datos.put("bonificacion1", new BigDecimal(bonificacion1));
        }
        datos.set("mapeo", objectMapper.createObjectNode().put("hoja", 0).put("filaDesde", 2)
                .put("columnaCodigo", 0).put("columnaDescripcion", 1).put("columnaCosto", 2));
        return datos;
    }

    protected JsonNode previaDeImportacion(String token, MockMultipartFile archivo, ObjectNode datos) throws Exception {
        return json(postMultipart(IMPORTACIONES + "/vista-previa", token, archivo, datos).andExpect(status().isOk()));
    }

    /** Aplica con los contadores de la vista previa de esos datos (lo que hace la pantalla). */
    protected ResultActions aplicarImportacion(String token, MockMultipartFile archivo, ObjectNode datos,
                                               boolean crearNuevos) throws Exception {
        return aplicarImportacion(token, archivo, datos, previaDeImportacion(token, archivo, datos), crearNuevos);
    }

    protected ResultActions aplicarImportacion(String token, MockMultipartFile archivo, ObjectNode datos,
                                               JsonNode previa, boolean crearNuevos) throws Exception {
        ObjectNode aplicar = datos.deepCopy();
        aplicar.put("crearNuevos", crearNuevos);
        aplicar.set("conteosEsperados", previa.get("conteos"));
        return postMultipart(IMPORTACIONES, token, archivo, aplicar);
    }

    // ---- masivo ----

    protected ObjectNode pedidoMasivo(String modo, String valor, Long proveedorId) {
        ObjectNode pedido = objectMapper.createObjectNode().put("modo", modo).put("valor", new BigDecimal(valor));
        if (proveedorId != null) {
            pedido.put("proveedorId", proveedorId);
        }
        return pedido;
    }

    protected JsonNode previaMasivo(String token, ObjectNode pedido) throws Exception {
        return json(postJson(MASIVO + "/vista-previa", token, pedido.toString()).andExpect(status().isOk()));
    }

    protected ResultActions aplicarMasivo(String token, ObjectNode pedido, JsonNode previa) throws Exception {
        return postJson(MASIVO, token, pedido.deepCopy().put("huella", previa.get("huella").asText()).toString());
    }

    // ---- el gancho de choque ----

    protected void ganchoEnSaveAll(GanchoDeChoque.Accion concurrente) {
        gancho.enSaveAll(concurrente);
    }

    protected void ganchoEnSave(GanchoDeChoque.Accion concurrente) {
        gancho.enSave(concurrente);
    }

    /** Como {@code saveAll}, pero choca SIEMPRE (cada intento del recalculo). */
    protected void ganchoSiempreEnSaveAll(GanchoDeChoque.Accion concurrente) {
        gancho.siempreEnSaveAll(concurrente);
    }

    protected void soltarElGancho() {
        gancho.soltar();
    }
}
