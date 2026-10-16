package com.ferreteria.catalogo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ferreteria.catalogo.service.lectura.LectorDeListas;
import com.ferreteria.cotizacion.CotizacionTestBase;
import com.ferreteria.cotizacion.model.LadoCotizacion;
import com.ferreteria.support.ListasDePrueba;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Criterio de exito de CU-04: una lista de 5.000 filas se previsualiza y se aplica en menos de 2 minutos.
 * Cada escenario corre en su PROPIA ferreteria (creada por jdbc, con un Dueno que reusa la contrasena
 * demo), asi no ensucia a los tenants demo. El armado de los datos y del xlsx no se cronometra: se mide
 * solo vista previa + aplicar, pasando por el filtro de seguridad, el parseo y la transaccion real.
 *
 * <p>Corte 2 (T-157): un libro sintetico de 70 hojas con las proporciones de LISTA 336, una hoja de 20.000 filas, el
 * aplicar de 4.139 filas elegidas una por una y el tope de 20.000 filas de datos. Los CUIT de esos escenarios son
 * 30-78300002-x a 30-78300005-x.
 */
class ImportacionRendimientoIntegrationTest extends CotizacionTestBase {

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

    // =====================================================================================================
    // Corte 2 (T-157): libro de 70 hojas, hoja de 20.000 filas y tope de filas de datos
    // =====================================================================================================

    private static final int ARTICULOS_336 = 4_139;
    private static final int HOJAS_336 = 70;
    private static final int EN_DOLARES = 186;
    private static final int AL_DIEZ_Y_MEDIO = 164;
    private static final String CASA = "t83r-oficial";

    @SpyBean
    LectorDeListas lector;

    private final List<Long> ferreteriasDelCorte2 = new ArrayList<>();

    @AfterEach
    void limpiarCorte2() {
        borrarCotizaciones("t83r-");
        for (Long id : ferreteriasDelCorte2) {
            borrarDatosDe(id);
        }
        ferreteriasDelCorte2.clear();
    }

    /** Una ferreteria propia con el dolar de precios en 1.000, su Dueno y un proveedor. */
    private Escenario escenarioConDolar(String cuit, String email) {
        long ferreteriaId = ferreteria(cuit, "Rendimiento corte 2 " + cuit);
        ferreteriasDelCorte2.add(ferreteriaId);
        borrarDatosDe(ferreteriaId);
        Instant haceUnRato = Instant.now().minusSeconds(600);
        cotizacion("USD", CASA, "1090", "1100", haceUnRato);
        configuracion(ferreteriaId, c -> {
            c.setCasaPrecios(CASA);
            c.fijarVigente(new BigDecimal("1000.0000"), CASA, LadoCotizacion.VENTA, haceUnRato);
        });
        usuario(ferreteriaId, email, "DUENO");
        long usuarioId = usuarioId(email);
        jdbc.update("insert into proveedor (ferreteria_id, nombre) values (?, 'Proveedor del corte 2')", ferreteriaId);
        long proveedorId = jdbc.queryForObject("select id from proveedor where ferreteria_id = ?", Long.class, ferreteriaId);
        return new Escenario(ferreteriaId, email, proveedorId, usuarioId);
    }

    private static List<Object> fila(Object... celdas) {
        return Arrays.asList(celdas);
    }

    /**
     * LISTA 336 en grande: 70 hojas con 4.139 articulos (59 por hoja y 68 en la ultima), 186 en USD y 164 con IVA
     * de 10,5 %. Columnas: Codigo, Articulo, Sinonimo, Stock, Mon., Lista (unidad), Bulto, Sin IVA, Con IVA.
     */
    private byte[] lista336Grande() {
        Map<String, List<List<Object>>> libro = new LinkedHashMap<>();
        int indice = 0;
        for (int h = 0; h < HOJAS_336; h++) {
            List<List<Object>> filas = new ArrayList<>();
            filas.add(fila("Código", "Artículo", "Sinónimo", "Stock", "Mon.", "Lista (unidad)", "Bulto", "Sin IVA",
                    "Con IVA"));
            int enLaHoja = h < HOJAS_336 - 1 ? 59 : ARTICULOS_336 - 59 * (HOJAS_336 - 1);
            for (int i = 0; i < enLaHoja; i++, indice++) {
                boolean usd = indice % 22 == 0 && indice < EN_DOLARES * 22;
                boolean diezYMedio = indice % 25 == 1 && indice < AL_DIEZ_Y_MEDIO * 25;
                BigDecimal costo = usd ? new BigDecimal(10 + indice % 7) : new BigDecimal(100 + indice);
                BigDecimal conIva = costo.multiply(diezYMedio ? new BigDecimal("1.105") : new BigDecimal("1.21"));
                filas.add(fila(String.format("L%04d", indice), "Articulo " + indice, null, 10, usd ? "USD" : "ARG",
                        costo, 1, costo, conIva));
            }
            libro.put("HOJA " + (h + 1), filas);
        }
        assertThat(indice).isEqualTo(ARTICULOS_336);
        return ListasDePrueba.xlsxHojas(libro);
    }

    private ObjectNode mapeo336(int hojas) {
        ObjectNode mapeo = objectMapper.createObjectNode().put("hoja", 0).put("filaDesde", 2).put("columnaCodigo", 0)
                .put("columnaDescripcion", 1).put("columnaCosto", 5).put("columnaMoneda", 4)
                .put("columnaSinIva", 7).put("columnaConIva", 8);
        ArrayNode elegidas = mapeo.putArray("hojas");
        for (int h = 0; h < hojas; h++) {
            elegidas.add(h);
        }
        return mapeo;
    }

    private ObjectNode datosCon(long proveedorId, ObjectNode mapeo) {
        ObjectNode datos = objectMapper.createObjectNode().put("proveedorId", proveedorId).put("listaIncluyeIva", false);
        datos.set("mapeo", mapeo);
        return datos;
    }

    private static int gzip(String texto) throws Exception {
        ByteArrayOutputStream salida = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(salida)) {
            gz.write(texto.getBytes(StandardCharsets.UTF_8));
        }
        return salida.size();
    }

    @Test
    void unLibroDeSetentaHojasConCuatroMilCientoTreintaYNueveArticulosSeVeCompletoYElLibroSeAbreUnaVez() throws Exception {
        Escenario e = escenarioConDolar("30-78300002-1", "dueno-t83r2@test.local");
        String token = login(e.email());
        MockMultipartFile lista = archivo("lista-336-grande.xlsx", lista336Grande());
        clearInvocations(lector);

        long inicio = System.nanoTime();
        String cuerpo = postMultipart("/api/importaciones/vista-previa", token, lista, datosCon(e.proveedorId(), mapeo336(HOJAS_336)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Duration total = Duration.ofNanos(System.nanoTime() - inicio);
        LOG.info("RENDIMIENTO corte 2, vista previa de 70 hojas y {} articulos: {} ms, json {} bytes, gzip {} bytes",
                ARTICULOS_336, total.toMillis(), cuerpo.getBytes(StandardCharsets.UTF_8).length, gzip(cuerpo));

        JsonNode previa = objectMapper.readTree(cuerpo);
        assertThat(previa.get("conteos").get("nuevos").asInt()).isEqualTo(ARTICULOS_336);
        assertThat(previa.get("conteos").get("conError").asInt()).isZero();
        assertThat(previa.get("nuevos")).hasSize(ARTICULOS_336);
        long enDolares = 0;
        long alDiezYMedio = 0;
        for (JsonNode alta : previa.get("nuevos")) {
            enDolares += "USD".equals(alta.get("moneda").asText()) ? 1 : 0;
            alDiezYMedio += "IVA_10_5".equals(alta.get("alicuotaIva").asText()) ? 1 : 0;
        }
        assertThat(enDolares).isEqualTo(EN_DOLARES);
        assertThat(alDiezYMedio).isEqualTo(AL_DIEZ_Y_MEDIO);
        // Las 70 hojas se leen con UNA apertura del libro: leer(byte[]) se llama una sola vez por pedido.
        verify(lector, times(1)).leer(any(byte[].class));
        assertThat(total).as("vista previa de 70 hojas").isLessThan(LIMITE);
    }

    @Test
    void aplicarCuatroMilCientoTreintaYNueveFilasElegidasUnaPorUnaEscribeTodoEnUnaImportacion() throws Exception {
        Escenario e = escenarioConDolar("30-78300003-2", "dueno-t83r3@test.local");
        String token = login(e.email());
        MockMultipartFile lista = archivo("lista-336-grande.xlsx", lista336Grande());
        ObjectNode datos = datosCon(e.proveedorId(), mapeo336(HOJAS_336));
        JsonNode previa = objectMapper.readTree(postMultipart("/api/importaciones/vista-previa", token, lista, datos)
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

        ObjectNode aplicar = datos.deepCopy();
        aplicar.put("crearNuevos", true);
        aplicar.set("conteosEsperados", previa.get("conteos"));
        ArrayNode elegidas = aplicar.putArray("filasElegidas");
        for (JsonNode alta : previa.get("nuevos")) {
            elegidas.add(objectMapper.createObjectNode().put("hoja", alta.get("hoja").asInt()).put("fila", alta.get("fila").asInt()));
        }
        assertThat(elegidas).hasSize(ARTICULOS_336);

        long inicio = System.nanoTime();
        postMultipart("/api/importaciones", token, lista, aplicar)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conteos.nuevos", is(ARTICULOS_336)));
        Duration total = Duration.ofNanos(System.nanoTime() - inicio);
        LOG.info("RENDIMIENTO corte 2, aplicar {} filas elegidas de 70 hojas: {} ms", ARTICULOS_336, total.toMillis());

        // Una sola importacion, y todos los articulos creados y marcados con ella.
        assertThat(contar("select count(*) from importacion_lista where ferreteria_id = ?", e.ferreteriaId())).isEqualTo(1);
        long importacionId = jdbc.queryForObject("select id from importacion_lista where ferreteria_id = ?", Long.class, e.ferreteriaId());
        assertThat(contar("select count(*) from articulo where ferreteria_id = ?", e.ferreteriaId())).isEqualTo(ARTICULOS_336);
        assertThat(contar("select count(*) from articulo where ferreteria_id = ? and ultima_importacion_id = ?",
                e.ferreteriaId(), importacionId)).isEqualTo(ARTICULOS_336);
        assertThat(contar("select count(*) from articulo where ferreteria_id = ? and moneda = 'USD'", e.ferreteriaId()))
                .isEqualTo(EN_DOLARES);
        assertThat(contar("select count(*) from articulo where ferreteria_id = ? and alicuota_iva = 'IVA_10_5'", e.ferreteriaId()))
                .isEqualTo(AL_DIEZ_Y_MEDIO);
        assertThat(contar("select count(*) from historial_precio where ferreteria_id = ? and origen = 'IMPORTACION'",
                e.ferreteriaId())).isEqualTo(ARTICULOS_336);
        assertThat(total).as("aplicar 4.139 filas").isLessThan(LIMITE);
    }

    @Test
    void unaHojaDeVeinteMilFilasDevuelveElDetalleCompletoYComprimidoPesaMenosDeUnMega() throws Exception {
        Escenario e = escenarioConDolar("30-78300004-3", "dueno-t83r4@test.local");
        String token = login(e.email());
        List<List<Object>> filas = new ArrayList<>(20_001);
        filas.add(fila("Codigo", "Descripcion", "Costo"));
        for (int i = 1; i <= 20_000; i++) {
            filas.add(fila(String.format("V-%05d", i), "Articulo de la lista grande numero " + i, new BigDecimal(100 + i)));
        }
        MockMultipartFile lista = archivo("veinte-mil.xlsx", ListasDePrueba.xlsx(filas));
        ObjectNode mapeo = objectMapper.createObjectNode().put("hoja", 0).put("filaDesde", 2).put("columnaCodigo", 0)
                .put("columnaDescripcion", 1).put("columnaCosto", 2);

        long inicio = System.nanoTime();
        String cuerpo = postMultipart("/api/importaciones/vista-previa", token, lista, datosCon(e.proveedorId(), mapeo))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        Duration total = Duration.ofNanos(System.nanoTime() - inicio);
        int crudo = cuerpo.getBytes(StandardCharsets.UTF_8).length;
        int comprimido = gzip(cuerpo);
        LOG.info("RENDIMIENTO corte 2, vista previa de 20.000 filas en una hoja: {} ms, json {} bytes, gzip {} bytes",
                total.toMillis(), crudo, comprimido);

        JsonNode previa = objectMapper.readTree(cuerpo);
        assertThat(previa.get("conteos").get("nuevos").asInt()).isEqualTo(20_000);
        assertThat(previa.get("nuevos")).hasSize(20_000);
        assertThat(previa.get("detalleRecortado").asBoolean()).isFalse();
        // Chico de verdad: el JSON sin comprimir pesa varios MB y con gzip (como lo sirve el servidor) baja de 1 MB.
        assertThat(crudo).isGreaterThan(1_000_000);
        assertThat(comprimido).isLessThan(1_000_000);
        assertThat(total).as("vista previa de 20.000 filas").isLessThan(LIMITE);
    }

    @Test
    void dosHojasDeDoceMilFilasDeDatosDan400PeroUnaSolaElegidaPasa() throws Exception {
        Escenario e = escenarioConDolar("30-78300005-4", "dueno-t83r5@test.local");
        String token = login(e.email());
        Map<String, List<List<Object>>> libro = new LinkedHashMap<>();
        for (int h = 1; h <= 2; h++) {
            List<List<Object>> filas = new ArrayList<>(12_001);
            filas.add(fila("Codigo", "Descripcion", "Costo"));
            for (int i = 1; i <= 12_000; i++) {
                filas.add(fila(String.format("H%d-%05d", h, i), "Articulo " + h + "-" + i, new BigDecimal(100 + i)));
            }
            libro.put("HOJA " + h, filas);
        }
        MockMultipartFile lista = archivo("dos-hojas-grandes.xlsx", ListasDePrueba.xlsxHojas(libro));
        ObjectNode dos = objectMapper.createObjectNode().put("hoja", 0).put("filaDesde", 2).put("columnaCodigo", 0)
                .put("columnaDescripcion", 1).put("columnaCosto", 2);
        dos.putArray("hojas").add(0).add(1);
        ObjectNode una = dos.deepCopy();
        una.putArray("hojas").add(0);

        postMultipart("/api/importaciones/vista-previa", token, lista, datosCon(e.proveedorId(), dos))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("La lista tiene más de 20.000 filas. Dividila en archivos más chicos.")));
        assertThat(contar("select count(*) from articulo where ferreteria_id = ?", e.ferreteriaId())).isZero();

        postMultipart("/api/importaciones/vista-previa", token, lista, datosCon(e.proveedorId(), una))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.conteos.nuevos", is(12_000)));
    }
}
