package com.ferreteria.catalogo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ferreteria.catalogo.repository.HistorialPrecioRepository;
import com.ferreteria.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Actualizacion masiva de precios (CU-05) por HTTP contra MySQL real. La base se comparte y no se
 * revierte, y la operacion con filtro vacio alcanza a TODO el catalogo de una ferreteria: por eso cada
 * test crea sus PROPIAS ferreterias por jdbc (con un Dueno y un Vendedor que reusan la contrasena demo)
 * y nunca modifica los datos de los tenants demo.
 */
class ActualizacionMasivaIntegrationTest extends IntegrationTestBase {

    @SpyBean
    HistorialPrecioRepository historialPrecioRepository;

    private static final String VISTA_PREVIA = "/api/actualizacion-masiva/vista-previa";
    private static final String APLICAR = "/api/actualizacion-masiva";
    private static final List<String> ENDPOINTS = List.of(VISTA_PREVIA, APLICAR);

    /** Una ferreteria aislada con su Dueno y su Vendedor ya autenticados. */
    private record Tenant(long ferreteriaId, long duenoId, String dueno, String vendedor) {}

    // --- Helpers ---

    private Tenant crearTenant() throws Exception {
        String cuit = "33-" + String.format("%08d", ThreadLocalRandom.current().nextInt(100_000_000)) + "-3";
        jdbc.update("insert into ferreteria (razon_social, cuit, condicion_iva) "
                + "values (?, ?, 'RESPONSABLE_INSCRIPTO')", "Masiva " + cuit, cuit);
        long ferreteriaId = jdbc.queryForObject("select id from ferreteria where cuit = ?", Long.class, cuit);
        String hash = jdbc.queryForObject(
                "select password_hash from usuario where email = 'dueno@tornillo.demo'", String.class);
        String emailDueno = unico("dueno") + "@masiva.test";
        String emailVendedor = unico("vendedor") + "@masiva.test";
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, ?, 'Dueno Masiva', 'DUENO')", ferreteriaId, emailDueno, hash);
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, ?, 'Vendedor Masiva', 'VENDEDOR')", ferreteriaId, emailVendedor, hash);
        long duenoId = jdbc.queryForObject("select id from usuario where email = ?", Long.class, emailDueno);
        return new Tenant(ferreteriaId, duenoId, login(emailDueno), login(emailVendedor));
    }

    private long crearProveedor(Tenant t) {
        jdbc.update("insert into proveedor (ferreteria_id, nombre) values (?, ?)", t.ferreteriaId(), unico("Prov"));
        return jdbc.queryForObject("select max(id) from proveedor where ferreteria_id = ?", Long.class, t.ferreteriaId());
    }

    /** Articulo sin proveedor ni rubro, margen heredado, IVA 21 y activo. */
    private long insertarArticulo(Tenant t, String costo, String precio) {
        return insertarArticulo(t, null, null, costo, null, precio, true);
    }

    private long insertarArticulo(
            Tenant t, Long proveedorId, Long rubroId, String costo, String margenPropio, String precio, boolean activo) {
        String codigo = unico("ART").substring(0, 12);
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, proveedor_id, rubro_id, costo, "
                        + "margen_porcentaje, alicuota_iva, precio_final, unidad, activo) "
                        + "values (?, ?, ?, ?, ?, ?, ?, 'IVA_21', ?, 'UNIDAD', ?)",
                t.ferreteriaId(), codigo, "Articulo " + codigo, proveedorId, rubroId, new BigDecimal(costo),
                margenPropio == null ? null : new BigDecimal(margenPropio), new BigDecimal(precio), activo);
        return jdbc.queryForObject(
                "select id from articulo where ferreteria_id = ? and codigo_interno = ?", Long.class, t.ferreteriaId(), codigo);
    }

    /** Pedido de vista previa: modo y valor (el valor va como texto para poder mandar 0, 10.555, etc.). */
    private ObjectNode pedido(String modo, String valor) {
        ObjectNode pedido = objectMapper.createObjectNode();
        if (modo != null) {
            pedido.put("modo", modo);
        }
        if (valor != null) {
            pedido.put("valor", new BigDecimal(valor));
        }
        return pedido;
    }

    private ObjectNode conHuella(ObjectNode pedido, String huella) {
        return pedido.put("huella", huella);
    }

    private List<Map<String, Object>> articulos(Tenant t) {
        return jdbc.queryForList("select * from articulo where ferreteria_id = ? order by id", t.ferreteriaId());
    }

    private long historial(Tenant t) {
        return jdbc.queryForObject("select count(*) from historial_precio where ferreteria_id = ?", Long.class, t.ferreteriaId());
    }

    /** Inserta cantidad articulos (costo 100.00, precio 160.00, sin proveedor ni rubro) en lotes por jdbc. */
    private void insertarEnLote(Tenant t, int cantidad, boolean activos) {
        long desde = jdbc.queryForObject(
                "select count(*) from articulo where ferreteria_id = ?", Long.class, t.ferreteriaId());
        List<Object[]> filas = new java.util.ArrayList<>(cantidad);
        for (long i = desde; i < desde + cantidad; i++) {
            String codigo = String.format("L-%06d", i);
            filas.add(new Object[] {t.ferreteriaId(), codigo, "Articulo " + codigo, activos});
        }
        jdbc.batchUpdate("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, alicuota_iva, "
                + "precio_final, unidad, activo) values (?, ?, ?, 100.00, 'IVA_21', 160.00, 'UNIDAD', ?)", filas);
    }

    private long crearRubro(Tenant t, String margen) {
        jdbc.update("insert into rubro (ferreteria_id, nombre, margen_porcentaje) values (?, ?, ?)",
                t.ferreteriaId(), unico("Rubro"), new BigDecimal(margen));
        return jdbc.queryForObject("select max(id) from rubro where ferreteria_id = ?", Long.class, t.ferreteriaId());
    }

    private Map<String, Object> fila(long articuloId) {
        return jdbc.queryForMap("select * from articulo where id = ?", articuloId);
    }

    private BigDecimal decimal(long articuloId, String columna) {
        return jdbc.queryForObject("select " + columna + " from articulo where id = ?", BigDecimal.class, articuloId);
    }

    private String codigoDe(long articuloId) {
        return jdbc.queryForObject("select codigo_interno from articulo where id = ?", String.class, articuloId);
    }

    /** Las entradas de historial de un articulo, la mas vieja primero. */
    private List<Map<String, Object>> entradas(long articuloId) {
        return jdbc.queryForList("select * from historial_precio where articulo_id = ? order by id", articuloId);
    }

    private static BigDecimal dec(JsonNode nodo) {
        return nodo.decimalValue();
    }

    /** La fila sin el costo ni el precio final: lo que una actualizacion de costo no debe tocar. */
    private static Map<String, Object> sinCostoNiPrecio(Map<String, Object> fila) {
        Map<String, Object> copia = new java.util.LinkedHashMap<>(fila);
        copia.remove("costo");
        copia.remove("precio_final");
        return copia;
    }

    /** Vista previa que espera 200 y devuelve el cuerpo. */
    private JsonNode vistaPrevia(String token, ObjectNode pedido) throws Exception {
        String cuerpo = postJson(VISTA_PREVIA, token, pedido.toString())
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(cuerpo);
    }

    /** Aplica con la huella de la vista previa y espera 201. */
    private JsonNode aplicar(String token, ObjectNode pedido, JsonNode vistaPrevia) throws Exception {
        String cuerpo = postJson(APLICAR, token, conHuella(pedido.deepCopy(), vistaPrevia.get("huella").asText()).toString())
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return objectMapper.readTree(cuerpo);
    }

    // --- Acceso (R-001) ---

    @Test
    void elVendedorRecibe403EnLosDosEndpointsConUnCuerpoValidoYNoCambiaNada() throws Exception {
        Tenant a = crearTenant();
        insertarArticulo(a, "100.00", "160.00");
        insertarArticulo(a, "200.00", "320.00");
        List<Map<String, Object>> antes = articulos(a);

        postJson(VISTA_PREVIA, a.vendedor(), pedido("COSTO_PORCENTAJE", "10").toString())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", is("No tenés permiso para hacer esto")));
        postJson(APLICAR, a.vendedor(), conHuella(pedido("COSTO_PORCENTAJE", "10"), "abc").toString())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", is("No tenés permiso para hacer esto")));

        assertThat(antes).hasSize(2);
        assertThat(articulos(a)).isEqualTo(antes);
        assertThat(historial(a)).isZero();
    }

    @Test
    void elVendedorRecibe403AntesQueCualquierValidacionDelCuerpo() throws Exception {
        Tenant a = crearTenant();
        Tenant b = crearTenant();
        long proveedorDeB = crearProveedor(b);
        insertarArticulo(a, "100.00", "160.00");
        List<Map<String, Object>> antes = articulos(a);

        for (String url : ENDPOINTS) {
            // valor 0 (400 para el Dueno), proveedor ajeno (404 para el Dueno), modo desconocido (400 para el Dueno)
            // y un cuerpo que ni es JSON: para el Vendedor todo es 403, porque la ruta se corta antes de leer el cuerpo.
            postJson(url, a.vendedor(), conHuella(pedido("COSTO_PORCENTAJE", "0"), "abc").toString())
                    .andExpect(status().isForbidden());
            postJson(url, a.vendedor(),
                    conHuella(pedido("COSTO_PORCENTAJE", "10").put("proveedorId", proveedorDeB), "abc").toString())
                    .andExpect(status().isForbidden());
            postJson(url, a.vendedor(), conHuella(pedido("OTRO", "10"), "abc").toString())
                    .andExpect(status().isForbidden());
            postJson(url, a.vendedor(), "no es json").andExpect(status().isForbidden());
        }

        assertThat(articulos(a)).isEqualTo(antes);
        assertThat(historial(a)).isZero();
    }

    @Test
    void sinTokenLosDosEndpointsDan401() throws Exception {
        for (String url : ENDPOINTS) {
            mvc.perform(post(url).contentType(MediaType.APPLICATION_JSON)
                            .content(conHuella(pedido("COSTO_PORCENTAJE", "10"), "abc").toString()))
                    .andExpect(status().isUnauthorized());
        }
    }

    // --- Humo del Dueno (R-001): el controlador responde 200 y 201 ---

    @Test
    void elDuenoRecibe200EnLaVistaPreviaY201AlAplicar() throws Exception {
        Tenant a = crearTenant();
        long uno = insertarArticulo(a, "100.00", "160.00");
        long dos = insertarArticulo(a, "200.00", "320.00");
        ObjectNode pedido = pedido("COSTO_PORCENTAJE", "10");

        JsonNode previa = vistaPrevia(a.dueno(), pedido);
        assertThat(previa.at("/conteos/total").asInt()).isEqualTo(2);
        assertThat(previa.get("huella").asText()).hasSize(64);

        postJson(APLICAR, a.dueno(), conHuella(pedido.deepCopy(), previa.get("huella").asText()).toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.conteos.modificados", is(2)))
                .andExpect(jsonPath("$.entradasDeHistorial", is(2)));

        assertThat(jdbc.queryForObject("select costo from articulo where id = ?", BigDecimal.class, uno))
                .isEqualByComparingTo("110.00");
        assertThat(jdbc.queryForObject("select costo from articulo where id = ?", BigDecimal.class, dos))
                .isEqualByComparingTo("220.00");
    }

    // --- Mapeo de errores sin handlers nuevos (R-002, R-006) ---

    @Test
    void unModoDesconocidoOAusenteDa400ConElMensajeDelModoEnLosDosEndpoints() throws Exception {
        Tenant a = crearTenant();
        insertarArticulo(a, "100.00", "160.00");
        String mensaje = "Elegí qué querés hacer: cambiar el costo en porcentaje, sumarle un monto al costo, fijar el "
                + "margen, cambiar el precio final en porcentaje o en monto, o cambiar el rubro.";

        for (String url : ENDPOINTS) {
            // "OTRO": modo es String, asi que no es el error de Jackson para enumeraciones.
            postJson(url, a.dueno(), conHuella(pedido("OTRO", "10"), "abc").toString())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", is("Datos inválidos")))
                    .andExpect(jsonPath("$.errors.modo", is(mensaje)));
            postJson(url, a.dueno(), conHuella(pedido(null, "10"), "abc").toString())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.modo", is(mensaje)));
        }
    }

    @Test
    void unValorQueNoEsUnNumeroDa400PorJsonIlegible() throws Exception {
        Tenant a = crearTenant();
        insertarArticulo(a, "100.00", "160.00");

        for (String url : ENDPOINTS) {
            postJson(url, a.dueno(), "{\"modo\":\"MARGEN\",\"valor\":\"abc\",\"huella\":\"abc\"}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", is("El cuerpo del pedido no es válido")));
            postJson(url, a.dueno(), "{\"modo\":").andExpect(status().isBadRequest());
        }
    }

    @Test
    void losDatosInvalidosDan400ConUnMensajePorCampo() throws Exception {
        Tenant a = crearTenant();
        insertarArticulo(a, "100.00", "160.00");

        for (String url : ENDPOINTS) {
            postJson(url, a.dueno(), conHuella(pedido("COSTO_PORCENTAJE", "0"), "abc").toString())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message", is("Datos inválidos")))
                    .andExpect(jsonPath("$.errors.porcentajeDistintoDeCero", is("El porcentaje no puede ser cero.")));
            postJson(url, a.dueno(), conHuella(pedido("MARGEN", "10.555"), "abc").toString())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.valor", is("El valor puede tener como máximo 2 decimales.")));
            postJson(url, a.dueno(), conHuella(pedido("MARGEN", null), "abc").toString())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.valor", is("Ingresá un valor.")));
        }
        postJson(APLICAR, a.dueno(), pedido("MARGEN", "30").toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.huella", is("Falta la vista previa. Generala de nuevo antes de confirmar.")));
    }

    @Test
    void unFerreteriaIdEnElCuerpoSeIgnoraYSoloCuentaLaFerreteriaDelToken() throws Exception {
        Tenant a = crearTenant();
        Tenant b = crearTenant();
        for (int i = 0; i < 3; i++) {
            insertarArticulo(a, "100.00", "160.00");
        }
        for (int i = 0; i < 5; i++) {
            insertarArticulo(b, "100.00", "160.00");
        }
        List<Map<String, Object>> deBAntes = articulos(b);
        ObjectNode pedido = pedido("COSTO_PORCENTAJE", "10");
        pedido.put("ferreteriaId", b.ferreteriaId());

        JsonNode previa = vistaPrevia(a.dueno(), pedido);
        aplicar(a.dueno(), pedido, previa);

        assertThat(previa.at("/conteos/total").asInt()).isEqualTo(3);
        assertThat(articulos(b)).isEqualTo(deBAntes);
        assertThat(historial(b)).isZero();
        assertThat(historial(a)).isEqualTo(3);
    }

    @Test
    void unProveedorOUnRubroAjenoDa404ConElCuerpoDeErrorEstandar() throws Exception {
        Tenant a = crearTenant();
        Tenant b = crearTenant();
        long proveedorDeB = crearProveedor(b);
        jdbc.update("insert into rubro (ferreteria_id, nombre, margen_porcentaje) values (?, ?, 40)",
                b.ferreteriaId(), unico("Rubro"));
        long rubroDeB = jdbc.queryForObject("select max(id) from rubro where ferreteria_id = ?", Long.class, b.ferreteriaId());

        for (String url : ENDPOINTS) {
            postJson(url, a.dueno(),
                    conHuella(pedido("COSTO_PORCENTAJE", "10").put("proveedorId", proveedorDeB), "abc").toString())
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status", is(404)))
                    .andExpect(jsonPath("$.message", is("Proveedor no encontrado")));
            postJson(url, a.dueno(),
                    conHuella(pedido("COSTO_PORCENTAJE", "10").put("rubroId", rubroDeB), "abc").toString())
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.status", is(404)))
                    .andExpect(jsonPath("$.message", is("Rubro no encontrado")));
        }
    }

    @Test
    void unaHuellaQueNoCorrespondeDa409YUnAlcanceVacioDa400ConSuTexto() throws Exception {
        Tenant a = crearTenant();
        insertarArticulo(a, "100.00", "160.00");
        Tenant vacia = crearTenant();
        List<Map<String, Object>> antes = articulos(a);

        postJson(APLICAR, a.dueno(), conHuella(pedido("COSTO_PORCENTAJE", "10"), "abc").toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.message", is("Los artículos cambiaron desde la vista previa. "
                        + "Volvé a generar la vista previa y confirmá de nuevo.")));
        postJson(APLICAR, vacia.dueno(), conHuella(pedido("COSTO_PORCENTAJE", "10"), "abc").toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status", is(400)))
                .andExpect(jsonPath("$.message", is("No hay artículos para actualizar con ese filtro.")));

        assertThat(articulos(a)).isEqualTo(antes);
        assertThat(historial(a)).isZero();
    }

    // --- Caminos felices (R-007..R-013, R-016, R-017, R-020) ---

    @Test
    void laVistaPreviaMarcaMargenHeredadoSoloEnElArticuloSinMargenPropio() throws Exception {
        Tenant t = crearTenant();
        long heredado = insertarArticulo(t, "100.00", "160.00");
        long propio = insertarArticulo(t, null, null, "100.00", "50.00", "181.50", true);

        JsonNode cambios = vistaPrevia(t.dueno(), pedido("COSTO_PORCENTAJE", "10")).get("cambios");

        assertThat(cambios).hasSize(2);
        assertThat(cambios.get(0).get("articuloId").asLong()).isEqualTo(heredado);
        assertThat(cambios.get(0).get("margenHeredado").asBoolean()).isTrue();
        assertThat(cambios.get(1).get("articuloId").asLong()).isEqualTo(propio);
        assertThat(cambios.get(1).get("margenHeredado").asBoolean()).isFalse();
    }

    @Test
    void laVistaPreviaDelEscenarioA1AA5InformaTodoYNoEscribeNada() throws Exception {
        Tenant t = crearTenant();
        long a1 = insertarArticulo(t, "100.00", "160.00");
        long a2 = insertarArticulo(t, "200.00", "320.00");
        long a3 = insertarArticulo(t, "1.00", "10.00");
        long a4 = insertarArticulo(t, "0.00", "0.00");
        insertarArticulo(t, "0.04", "10.00");
        List<Map<String, Object>> antes = articulos(t);

        JsonNode previa = vistaPrevia(t.dueno(), pedido("COSTO_PORCENTAJE", "10"));
        JsonNode previa2 = vistaPrevia(t.dueno(), pedido("COSTO_PORCENTAJE", "10"));

        JsonNode conteos = previa.get("conteos");
        assertThat(conteos.get("total").asInt()).isEqualTo(5);
        assertThat(conteos.get("suben").asInt()).isEqualTo(2);
        assertThat(conteos.get("bajan").asInt()).isZero();
        assertThat(conteos.get("sinCambioDePrecio").asInt()).isEqualTo(2);
        assertThat(conteos.get("conError").asInt()).isEqualTo(1);
        assertThat(conteos.get("conAdvertencia").asInt()).isZero();
        assertThat(conteos.get("pasanAMargenPropio").asInt()).isZero();
        assertThat(conteos.get("modificados").asInt()).isEqualTo(3);
        assertThat(previa.get("detalleRecortado").asBoolean()).isFalse();

        JsonNode cambios = previa.get("cambios");
        assertThat(cambios).hasSize(3);
        assertThat(cambios.get(0).get("articuloId").asLong()).isEqualTo(a1);
        assertThat(cambios.get(1).get("articuloId").asLong()).isEqualTo(a2);
        assertThat(cambios.get(2).get("articuloId").asLong()).isEqualTo(a3);
        JsonNode primero = cambios.get(0);
        assertThat(primero.get("codigo").asText()).isEqualTo(codigoDe(a1));
        assertThat(dec(primero.get("costoAnterior"))).isEqualByComparingTo("100.00");
        assertThat(dec(primero.get("costoNuevo"))).isEqualByComparingTo("110.00");
        assertThat(dec(primero.get("margenAnterior"))).isEqualByComparingTo("30");
        assertThat(dec(primero.get("margenNuevo"))).isEqualByComparingTo("30");
        assertThat(dec(primero.get("precioAnterior"))).isEqualByComparingTo("160.00");
        assertThat(dec(primero.get("precioNuevo"))).isEqualByComparingTo("180.00");
        assertThat(primero.get("advertencia").isNull()).isTrue();
        assertThat(dec(cambios.get(1).get("costoNuevo"))).isEqualByComparingTo("220.00");
        assertThat(dec(cambios.get(1).get("precioNuevo"))).isEqualByComparingTo("350.00");
        assertThat(dec(cambios.get(2).get("costoNuevo"))).isEqualByComparingTo("1.10");
        assertThat(dec(cambios.get(2).get("precioNuevo"))).isEqualByComparingTo("10.00");

        JsonNode errores = previa.get("errores");
        assertThat(errores).hasSize(1);
        assertThat(errores.get(0).get("articuloId").asLong()).isEqualTo(a4);
        assertThat(errores.get(0).get("motivo").asText()).isEqualTo("El costo nuevo da 0. Revisá el porcentaje.");

        // La vista previa no escribe: base identica, sin historial; y es determinista (misma huella).
        assertThat(articulos(t)).isEqualTo(antes);
        assertThat(historial(t)).isZero();
        assertThat(previa2.get("huella").asText()).isEqualTo(previa.get("huella").asText());
    }

    @Test
    void aplicarElEscenarioA1AA5EscribeSoloLosModificadosYSusEntradasMasivo() throws Exception {
        Tenant t = crearTenant();
        long a1 = insertarArticulo(t, "100.00", "160.00");
        long a2 = insertarArticulo(t, "200.00", "320.00");
        long a3 = insertarArticulo(t, "1.00", "10.00");
        long a4 = insertarArticulo(t, "0.00", "0.00");
        long a5 = insertarArticulo(t, "0.04", "10.00");
        // Campos que la operacion no debe tocar: precio de lista, stock y ubicacion.
        jdbc.update("update articulo set precio_lista_proveedor = 1210.00, stock_actual = 12, ubicacion = 'B4' where id = ?", a1);
        Map<String, Object> a1Antes = fila(a1);
        Map<String, Object> a4Antes = fila(a4);
        Map<String, Object> a5Antes = fila(a5);
        ObjectNode pedido = pedido("COSTO_PORCENTAJE", "10");

        JsonNode previa = vistaPrevia(t.dueno(), pedido);
        JsonNode resultado = aplicar(t.dueno(), pedido, previa);

        assertThat(resultado.at("/conteos/total").asInt()).isEqualTo(5);
        assertThat(resultado.at("/conteos/modificados").asInt()).isEqualTo(3);
        assertThat(resultado.get("entradasDeHistorial").asInt()).isEqualTo(3);
        assertThat(resultado.get("conteos")).isEqualTo(previa.get("conteos"));
        assertThat(decimal(a1, "costo")).isEqualByComparingTo("110.00");
        assertThat(decimal(a1, "precio_final")).isEqualByComparingTo("180.00");
        assertThat(decimal(a2, "costo")).isEqualByComparingTo("220.00");
        assertThat(decimal(a2, "precio_final")).isEqualByComparingTo("350.00");
        assertThat(decimal(a3, "costo")).isEqualByComparingTo("1.10");
        assertThat(decimal(a3, "precio_final")).isEqualByComparingTo("10.00");
        // El error (A4) y el que no cambia nada (A5) quedan identicos, y no tienen historial.
        assertThat(fila(a4)).isEqualTo(a4Antes);
        assertThat(fila(a5)).isEqualTo(a5Antes);
        assertThat(entradas(a4)).isEmpty();
        assertThat(entradas(a5)).isEmpty();
        // Lo que no es costo ni precio no cambia (precio de lista, margen, IVA, stock, ubicacion, activo...).
        // La version sube en 1 en los que el masivo cambio (V8) y lo demas queda igual.
        Map<String, Object> a1Esperado = sinCostoNiPrecio(a1Antes);
        a1Esperado.put("version", (Long) a1Antes.get("version") + 1);
        assertThat(sinCostoNiPrecio(fila(a1))).isEqualTo(a1Esperado);
        assertThat(jdbc.queryForObject("select version from articulo where id = ?", Long.class, a2)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select version from articulo where id = ?", Long.class, a3)).isEqualTo(1L);
        assertThat(decimal(a1, "precio_lista_proveedor")).isEqualByComparingTo("1210.00");
        assertThat(historial(t)).isEqualTo(3);

        // A1: costo 100 -> 110, precio 160 -> 180, origen MASIVO, el usuario del Dueno que aplico.
        List<Map<String, Object>> deA1 = entradas(a1);
        assertThat(deA1).hasSize(1);
        assertThat(deA1.get(0).get("origen")).isEqualTo("MASIVO");
        assertThat(deA1.get(0).get("usuario_id")).isEqualTo(t.duenoId());
        assertThat(deA1.get(0).get("ferreteria_id")).isEqualTo(t.ferreteriaId());
        assertThat((BigDecimal) deA1.get(0).get("costo_anterior")).isEqualByComparingTo("100.00");
        assertThat((BigDecimal) deA1.get(0).get("costo_nuevo")).isEqualByComparingTo("110.00");
        assertThat((BigDecimal) deA1.get(0).get("precio_anterior")).isEqualByComparingTo("160.00");
        assertThat((BigDecimal) deA1.get(0).get("precio_nuevo")).isEqualByComparingTo("180.00");
        // A3: cambio el costo aunque el precio no: tiene su entrada.
        List<Map<String, Object>> deA3 = entradas(a3);
        assertThat(deA3).hasSize(1);
        assertThat((BigDecimal) deA3.get(0).get("costo_anterior")).isEqualByComparingTo("1.00");
        assertThat((BigDecimal) deA3.get(0).get("costo_nuevo")).isEqualByComparingTo("1.10");
        assertThat((BigDecimal) deA3.get(0).get("precio_anterior")).isEqualByComparingTo("10.00");
        assertThat((BigDecimal) deA3.get(0).get("precio_nuevo")).isEqualByComparingTo("10.00");
        assertThat(entradas(a2)).hasSize(1);
    }

    @Test
    void elModoMargenFijaElMargenPropioYCuentaLosQueDejanDeHeredar() throws Exception {
        Tenant t = crearTenant();
        long rubro = crearRubro(t, "40");
        long b1 = insertarArticulo(t, null, rubro, "100.00", null, "170.00", true);
        long b2 = insertarArticulo(t, null, rubro, "100.00", "50.00", "190.00", true);
        long b3 = insertarArticulo(t, null, rubro, "100.00", "30.00", "160.00", true);
        Map<String, Object> b2Antes = fila(b2);
        ObjectNode pedido = pedido("MARGEN", "50");

        JsonNode previa = vistaPrevia(t.dueno(), pedido);
        JsonNode conteos = previa.get("conteos");
        assertThat(conteos.get("total").asInt()).isEqualTo(3);
        assertThat(conteos.get("suben").asInt()).isEqualTo(2);
        assertThat(conteos.get("bajan").asInt()).isZero();
        assertThat(conteos.get("sinCambioDePrecio").asInt()).isEqualTo(1);
        assertThat(conteos.get("conError").asInt()).isZero();
        assertThat(conteos.get("pasanAMargenPropio").asInt()).isEqualTo(1);
        assertThat(conteos.get("modificados").asInt()).isEqualTo(2);
        assertThat(previa.get("cambios")).hasSize(2);
        // B1 hereda el 40 del rubro: margen efectivo 40 -> 50.
        assertThat(dec(previa.get("cambios").get(0).get("margenAnterior"))).isEqualByComparingTo("40");
        assertThat(dec(previa.get("cambios").get(0).get("margenNuevo"))).isEqualByComparingTo("50");

        JsonNode resultado = aplicar(t.dueno(), pedido, previa);

        assertThat(resultado.at("/conteos/modificados").asInt()).isEqualTo(2);
        assertThat(resultado.get("entradasDeHistorial").asInt()).isEqualTo(2);
        assertThat(decimal(b1, "margen_porcentaje")).isEqualByComparingTo("50");
        assertThat(decimal(b1, "precio_final")).isEqualByComparingTo("190.00");
        assertThat(decimal(b1, "costo")).isEqualByComparingTo("100.00");
        assertThat(decimal(b3, "margen_porcentaje")).isEqualByComparingTo("50");
        assertThat(decimal(b3, "precio_final")).isEqualByComparingTo("190.00");
        assertThat(fila(b2)).isEqualTo(b2Antes);
        assertThat(entradas(b2)).isEmpty();
        List<Map<String, Object>> deB1 = entradas(b1);
        assertThat(deB1).hasSize(1);
        assertThat(deB1.get(0).get("origen")).isEqualTo("MASIVO");
        assertThat(deB1.get(0).get("usuario_id")).isEqualTo(t.duenoId());
        assertThat((BigDecimal) deB1.get(0).get("costo_anterior")).isEqualByComparingTo("100.00");
        assertThat((BigDecimal) deB1.get(0).get("costo_nuevo")).isEqualByComparingTo("100.00");
        assertThat((BigDecimal) deB1.get(0).get("precio_anterior")).isEqualByComparingTo("170.00");
        assertThat((BigDecimal) deB1.get(0).get("precio_nuevo")).isEqualByComparingTo("190.00");
        assertThat(entradas(b3)).hasSize(1);
        assertThat(historial(t)).isEqualTo(2);
    }

    @Test
    void despuesDeAplicarMargenUnCambioDelMargenDelRubroYaNoRecalculaAlArticulo() throws Exception {
        Tenant t = crearTenant();
        long rubro = crearRubro(t, "40");
        // Sin margen propio: hereda el 40 del rubro. El control sigue heredando y prueba que la cascada corre.
        long articulo = insertarArticulo(t, null, rubro, "100.00", null, "170.00", true);
        long control = insertarArticulo(t, null, rubro, "100.00", null, "170.00", true);
        ObjectNode pedido = pedido("MARGEN", "50");
        // Solo el articulo entra en el alcance de la operacion: el control no se toca.
        jdbc.update("update articulo set activo = false where id = ?", control);

        JsonNode previa = vistaPrevia(t.dueno(), pedido);
        aplicar(t.dueno(), pedido, previa);
        jdbc.update("update articulo set activo = true where id = ?", control);
        assertThat(decimal(articulo, "margen_porcentaje")).isEqualByComparingTo("50");
        assertThat(decimal(articulo, "precio_final")).isEqualByComparingTo("190.00");
        assertThat(jdbc.queryForObject("select margen_porcentaje from articulo where id = ?", BigDecimal.class, control))
                .isNull();
        String nombre = jdbc.queryForObject("select nombre from rubro where id = ?", String.class, rubro);

        // El margen del rubro pasa de 40 a 45: la cascada recalcula al heredero pero saltea al que ya tiene margen propio.
        putJson("/api/rubros/" + rubro, t.dueno(),
                objectMapper.createObjectNode().put("nombre", nombre).put("margenPorcentaje", new BigDecimal("45")).toString())
                .andExpect(status().isOk());

        assertThat(decimal(articulo, "margen_porcentaje")).isEqualByComparingTo("50");
        assertThat(decimal(articulo, "precio_final")).isEqualByComparingTo("190.00");
        assertThat(entradas(articulo)).hasSize(1);
        assertThat(decimal(control, "precio_final")).isNotEqualByComparingTo("170.00");
    }

    @Test
    void siSoloCambiaElMargenPropioSeEscribeElArticuloPeroNoElHistorial() throws Exception {
        Tenant t = crearTenant();
        // Sin rubro, hereda el 30 de la ferreteria: precio 160 con costo 100. MARGEN 30 no cambia el precio.
        long articulo = insertarArticulo(t, "100.00", "160.00");
        ObjectNode pedido = pedido("MARGEN", "30");

        JsonNode previa = vistaPrevia(t.dueno(), pedido);
        assertThat(previa.at("/conteos/modificados").asInt()).isEqualTo(1);
        assertThat(previa.at("/conteos/sinCambioDePrecio").asInt()).isEqualTo(1);
        assertThat(previa.at("/conteos/pasanAMargenPropio").asInt()).isEqualTo(1);
        JsonNode resultado = aplicar(t.dueno(), pedido, previa);

        assertThat(resultado.get("entradasDeHistorial").asInt()).isZero();
        assertThat(decimal(articulo, "margen_porcentaje")).isEqualByComparingTo("30");
        assertThat(decimal(articulo, "precio_final")).isEqualByComparingTo("160.00");
        assertThat(historial(t)).isZero();
    }

    @Test
    void conTotalMayorACeroYNadaPorModificarAplicarResponde201SinEscribir() throws Exception {
        Tenant t = crearTenant();
        long conError = crearProveedor(t);
        long sinCambios = crearProveedor(t);
        insertarArticulo(t, conError, null, "0.00", null, "0.00", true);
        insertarArticulo(t, sinCambios, null, "100.00", "30.00", "160.00", true);
        List<Map<String, Object>> antes = articulos(t);

        // Todo con error: costo 0 con +10.
        ObjectNode soloError = pedido("COSTO_PORCENTAJE", "10").put("proveedorId", conError);
        JsonNode previaError = vistaPrevia(t.dueno(), soloError);
        assertThat(previaError.at("/conteos/total").asInt()).isEqualTo(1);
        assertThat(previaError.at("/conteos/modificados").asInt()).isZero();
        JsonNode resultadoError = aplicar(t.dueno(), soloError, previaError);
        assertThat(resultadoError.get("entradasDeHistorial").asInt()).isZero();
        assertThat(resultadoError.at("/conteos/conError").asInt()).isEqualTo(1);

        // Nada cambia: margen propio ya en 30 y precio ya en 160.
        ObjectNode sinCambio = pedido("MARGEN", "30").put("proveedorId", sinCambios);
        JsonNode previaSinCambio = vistaPrevia(t.dueno(), sinCambio);
        assertThat(previaSinCambio.at("/conteos/total").asInt()).isEqualTo(1);
        assertThat(previaSinCambio.at("/conteos/modificados").asInt()).isZero();
        JsonNode resultadoSinCambio = aplicar(t.dueno(), sinCambio, previaSinCambio);
        assertThat(resultadoSinCambio.get("entradasDeHistorial").asInt()).isZero();

        assertThat(articulos(t)).isEqualTo(antes);
        assertThat(historial(t)).isZero();
    }

    // --- Aislamiento entre ferreterias (R-003, R-021; regla 7 de CLAUDE.md) ---

    @Test
    void elProveedorOElRubroDeOtraFerreteriaDa404EnAmbosEndpointsYSusArticulosNoCambian() throws Exception {
        Tenant a = crearTenant();
        Tenant b = crearTenant();
        long proveedorDeB = crearProveedor(b);
        long rubroDeB = crearRubro(b, "40");
        long enProveedor = insertarArticulo(b, proveedorDeB, null, "100.00", null, "160.00", true);
        long enRubro = insertarArticulo(b, null, rubroDeB, "200.00", "50.00", "400.00", true);
        insertarArticulo(a, "100.00", "160.00");
        List<Map<String, Object>> deBAntes = articulos(b);
        List<Map<String, Object>> deAAntes = articulos(a);

        for (String modo : List.of("COSTO_PORCENTAJE", "MARGEN")) {
            ObjectNode conProveedor = pedido(modo, "10").put("proveedorId", proveedorDeB);
            ObjectNode conRubro = pedido(modo, "10").put("rubroId", rubroDeB);
            for (String url : ENDPOINTS) {
                postJson(url, a.dueno(), conHuella(conProveedor.deepCopy(), "abc").toString())
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.message", is("Proveedor no encontrado")));
                postJson(url, a.dueno(), conHuella(conRubro.deepCopy(), "abc").toString())
                        .andExpect(status().isNotFound())
                        .andExpect(jsonPath("$.message", is("Rubro no encontrado")));
            }
        }

        // Los articulos de B conservan costo, margen y precio; no hay historial en ninguna de las dos.
        assertThat(articulos(b)).isEqualTo(deBAntes);
        assertThat(decimal(enProveedor, "costo")).isEqualByComparingTo("100.00");
        assertThat(decimal(enRubro, "margen_porcentaje")).isEqualByComparingTo("50.00");
        assertThat(decimal(enRubro, "precio_final")).isEqualByComparingTo("400.00");
        assertThat(articulos(a)).isEqualTo(deAAntes);
        assertThat(historial(a)).isZero();
        assertThat(historial(b)).isZero();
    }

    @Test
    void conElFiltroVacioSoloSeCuentanYSeModificanLosArticulosDeLaFerreteriaDelToken() throws Exception {
        Tenant a = crearTenant();
        Tenant b = crearTenant();
        List<Long> deA = List.of(
                insertarArticulo(a, "100.00", "160.00"),
                insertarArticulo(a, "200.00", "320.00"),
                insertarArticulo(a, "50.00", "80.00"));
        for (int i = 0; i < 5; i++) {
            insertarArticulo(b, "100.00", "160.00");
        }
        List<Map<String, Object>> deBAntes = articulos(b);
        assertThat(deBAntes).hasSize(5);
        ObjectNode pedido = pedido("COSTO_PORCENTAJE", "10");

        JsonNode previa = vistaPrevia(a.dueno(), pedido);
        JsonNode resultado = aplicar(a.dueno(), pedido, previa);

        assertThat(previa.at("/conteos/total").asInt()).isEqualTo(3);
        assertThat(resultado.at("/conteos/total").asInt()).isEqualTo(3);
        assertThat(resultado.get("entradasDeHistorial").asInt()).isEqualTo(3);
        assertThat(articulos(b)).isEqualTo(deBAntes);
        assertThat(historial(b)).isZero();
        assertThat(historial(a)).isEqualTo(3);
        // Las entradas de A son de A y de articulos de A: ninguna entrada cruza de ferreteria.
        assertThat(jdbc.queryForObject("select count(*) from historial_precio h join articulo x on x.id = h.articulo_id "
                + "where h.ferreteria_id <> x.ferreteria_id", Long.class)).isZero();
        for (long articulo : deA) {
            assertThat(entradas(articulo)).hasSize(1);
            // B no ve los articulos de A (404), con o sin la operacion.
            getResult("/api/articulos/" + articulo, b.dueno()).andExpect(status().isNotFound());
        }
    }

    @Test
    void ladoAladoLaOperacionDeCadaFerreteriaSoloTocaLosSuyos() throws Exception {
        Tenant a = crearTenant();
        Tenant b = crearTenant();
        long deA = insertarArticulo(a, "100.00", "160.00");
        long deB = insertarArticulo(b, "100.00", "160.00");

        ObjectNode pedidoA = pedido("COSTO_PORCENTAJE", "10");
        aplicar(a.dueno(), pedidoA, vistaPrevia(a.dueno(), pedidoA));
        assertThat(decimal(deA, "costo")).isEqualByComparingTo("110.00");
        assertThat(decimal(deB, "costo")).isEqualByComparingTo("100.00");

        ObjectNode pedidoB = pedido("MARGEN", "50");
        aplicar(b.dueno(), pedidoB, vistaPrevia(b.dueno(), pedidoB));
        assertThat(decimal(deB, "margen_porcentaje")).isEqualByComparingTo("50");
        assertThat(jdbc.queryForObject("select margen_porcentaje from articulo where id = ?", BigDecimal.class, deA))
                .isNull();
        assertThat(entradas(deA)).hasSize(1);
        assertThat(entradas(deB)).hasSize(1);
        assertThat(entradas(deA).get(0).get("usuario_id")).isEqualTo(a.duenoId());
        assertThat(entradas(deB).get(0).get("usuario_id")).isEqualTo(b.duenoId());
    }

    // --- Orden de validacion 403/404/400 y tope (R-001, R-002, R-006, R-015, R-017) ---

    @Test
    void unProveedorAjenoDa404AunqueElValorSeaInvalidoYUnoPropioDa400() throws Exception {
        Tenant a = crearTenant();
        Tenant b = crearTenant();
        long proveedorDeB = crearProveedor(b);
        long proveedorPropio = crearProveedor(a);
        insertarArticulo(a, proveedorPropio, null, "100.00", null, "160.00", true);

        for (String url : ENDPOINTS) {
            // 404 antes que el 400 del valor 0, del modo invalido y de la huella ausente.
            postJson(url, a.dueno(), pedido("COSTO_PORCENTAJE", "0").put("proveedorId", proveedorDeB).toString())
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.message", is("Proveedor no encontrado")));
            postJson(url, a.dueno(), pedido("OTRO", null).put("proveedorId", proveedorDeB).toString())
                    .andExpect(status().isNotFound());
            // El mismo valor 0 con un proveedor propio llega al 400.
            postJson(url, a.dueno(), conHuella(pedido("COSTO_PORCENTAJE", "0").put("proveedorId", proveedorPropio), "abc").toString())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.porcentajeDistintoDeCero", is("El porcentaje no puede ser cero.")));
        }
    }

    @Test
    void sinHuellaAplicarDa400YNoCambiaNada() throws Exception {
        Tenant a = crearTenant();
        insertarArticulo(a, "100.00", "160.00");
        List<Map<String, Object>> antes = articulos(a);
        String mensaje = "Falta la vista previa. Generala de nuevo antes de confirmar.";

        postJson(APLICAR, a.dueno(), pedido("COSTO_PORCENTAJE", "10").toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.huella", is(mensaje)));
        postJson(APLICAR, a.dueno(), conHuella(pedido("COSTO_PORCENTAJE", "10"), "").toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.huella", is(mensaje)));
        postJson(APLICAR, a.dueno(), conHuella(pedido("COSTO_PORCENTAJE", "10"), "   ").toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.huella", is(mensaje)));

        assertThat(articulos(a)).isEqualTo(antes);
        assertThat(historial(a)).isZero();
    }

    @Test
    void unAlcanceVacioEsUnaVistaPreviaValidaEnCeroYAplicarDa400() throws Exception {
        Tenant a = crearTenant();
        long sinArticulos = crearProveedor(a);
        insertarArticulo(a, "100.00", "160.00");
        ObjectNode pedido = pedido("COSTO_PORCENTAJE", "10").put("proveedorId", sinArticulos);

        JsonNode previa = vistaPrevia(a.dueno(), pedido);
        for (String contador : List.of("total", "suben", "bajan", "sinCambioDePrecio", "conError",
                "pasanAMargenPropio", "conAdvertencia", "modificados")) {
            assertThat(previa.at("/conteos/" + contador).asInt()).as(contador).isZero();
        }
        assertThat(previa.get("cambios")).isEmpty();
        assertThat(previa.get("errores")).isEmpty();
        assertThat(previa.get("detalleRecortado").asBoolean()).isFalse();

        // Con una huella vieja cualquiera: el vacio se evalua antes que la deriva (400, no 409).
        postJson(APLICAR, a.dueno(), conHuella(pedido.deepCopy(), "huella-vieja").toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("No hay artículos para actualizar con ese filtro.")));
        assertThat(historial(a)).isZero();
    }

    @Test
    void elTopeDe20000ArticulosSeControlaContandoYNoCalculaNiEscribeNada() throws Exception {
        Tenant a = crearTenant();
        insertarEnLote(a, 20_000, true);
        // Dados de baja: con el filtro por defecto no cuentan para el tope.
        insertarEnLote(a, 5, false);
        ObjectNode pedido = pedido("COSTO_PORCENTAJE", "10");

        // Exactamente 20.000 (activos) pasa; el detalle se recorta a 1.000 filas.
        JsonNode previa = vistaPrevia(a.dueno(), pedido);
        assertThat(previa.at("/conteos/total").asInt()).isEqualTo(20_000);
        assertThat(previa.get("cambios")).hasSize(1_000);
        assertThat(previa.get("detalleRecortado").asBoolean()).isTrue();

        // Con los dados de baja son 20.005: 400 del tope en los dos endpoints.
        String mensaje = "El filtro abarca 20.005 artículos y el máximo es 20.000. Afiná el filtro.";
        postJson(VISTA_PREVIA, a.dueno(), pedido.deepCopy().put("incluirInactivos", true).toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is(mensaje)));
        postJson(APLICAR, a.dueno(), conHuella(pedido.deepCopy().put("incluirInactivos", true), "abc").toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is(mensaje)));

        // Crece de 20.000 a 20.001 despues de la vista previa: aplicar da el 400 del tope, no el 409.
        insertarEnLote(a, 1, true);
        String mensaje20001 = "El filtro abarca 20.001 artículos y el máximo es 20.000. Afiná el filtro.";
        postJson(VISTA_PREVIA, a.dueno(), pedido.toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is(mensaje20001)));
        postJson(APLICAR, a.dueno(), conHuella(pedido.deepCopy(), previa.get("huella").asText()).toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is(mensaje20001)));

        // Nada cambio: todos siguen con costo 100.00 y precio 160.00 (los 20.006 insertados), y no hay historial.
        assertThat(jdbc.queryForObject("select count(*) from articulo where ferreteria_id = ?",
                Long.class, a.ferreteriaId())).isEqualTo(20_006L);
        assertThat(jdbc.queryForObject("select count(*) from articulo where ferreteria_id = ? "
                + "and (costo <> 100.00 or precio_final <> 160.00 or margen_porcentaje is not null)",
                Long.class, a.ferreteriaId())).isZero();
        assertThat(historial(a)).isZero();
    }

    // --- Deriva y 409 (R-018, R-021) ---

    private static final String DERIVA = "Los artículos cambiaron desde la vista previa. "
            + "Volvé a generar la vista previa y confirmá de nuevo.";

    /** Un proveedor con un rubro (margen 40) y tres articulos de ese rubro que heredan el margen. */
    private record Escenario(Tenant tenant, long proveedor, long rubro, List<Long> articulos) {}

    private Escenario escenarioDeDeriva() throws Exception {
        Tenant t = crearTenant();
        long proveedor = crearProveedor(t);
        long rubro = crearRubro(t, "40");
        List<Long> articulos = List.of(
                insertarArticulo(t, proveedor, rubro, "100.00", null, "170.00", true),
                insertarArticulo(t, proveedor, rubro, "200.00", null, "340.00", true),
                insertarArticulo(t, proveedor, rubro, "300.00", null, "510.00", true));
        return new Escenario(t, proveedor, rubro, articulos);
    }

    /** Edita un articulo por la API (PUT con el cuerpo que devuelve el GET, modificado). */
    private void editarArticulo(Tenant t, long id, java.util.function.Consumer<ObjectNode> cambio) throws Exception {
        ObjectNode cuerpo = (ObjectNode) getJson("/api/articulos/" + id, t.dueno());
        cambio.accept(cuerpo);
        putJson("/api/articulos/" + id, t.dueno(), cuerpo.toString()).andExpect(status().isOk());
    }

    /** Aplicar con esa huella da 409 y no cambia nada: ni articulos ni historial. */
    private void assertConflicto(Tenant t, ObjectNode pedido, String huella) throws Exception {
        List<Map<String, Object>> articulosAntes = articulos(t);
        long historialAntes = historial(t);
        postJson(APLICAR, t.dueno(), conHuella(pedido.deepCopy(), huella).toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status", is(409)))
                .andExpect(jsonPath("$.message", is(DERIVA)));
        assertThat(articulos(t)).isEqualTo(articulosAntes);
        assertThat(historial(t)).isEqualTo(historialAntes);
    }

    @Test
    void siSeEditaAMano_elCostoDeUnArticuloDelAlcance_aplicarDa409() throws Exception {
        Escenario e = escenarioDeDeriva();
        ObjectNode pedido = pedido("COSTO_PORCENTAJE", "10").put("proveedorId", e.proveedor());
        JsonNode previa = vistaPrevia(e.tenant().dueno(), pedido);

        editarArticulo(e.tenant(), e.articulos().get(1), c -> c.put("costo", new BigDecimal("250.00")));

        assertConflicto(e.tenant(), pedido, previa.get("huella").asText());
        // Y la vista previa nueva refleja el costo editado y se puede aplicar.
        JsonNode nueva = vistaPrevia(e.tenant().dueno(), pedido);
        assertThat(nueva.get("huella").asText()).isNotEqualTo(previa.get("huella").asText());
        aplicar(e.tenant().dueno(), pedido, nueva);
        assertThat(decimal(e.articulos().get(1), "costo")).isEqualByComparingTo("275.00");
    }

    @Test
    void siCambiaElMargenDelRubroFiltrado_aplicarDa409() throws Exception {
        Escenario e = escenarioDeDeriva();
        ObjectNode pedido = pedido("COSTO_PORCENTAJE", "10").put("rubroId", e.rubro());
        JsonNode previa = vistaPrevia(e.tenant().dueno(), pedido);
        String nombre = jdbc.queryForObject("select nombre from rubro where id = ?", String.class, e.rubro());

        putJson("/api/rubros/" + e.rubro(), e.tenant().dueno(),
                objectMapper.createObjectNode().put("nombre", nombre).put("margenPorcentaje", new BigDecimal("45")).toString())
                .andExpect(status().isOk());

        assertConflicto(e.tenant(), pedido, previa.get("huella").asText());
    }

    @Test
    void siCambiaElMultiploDeRedondeoDeLaFerreteria_aplicarDa409() throws Exception {
        Escenario e = escenarioDeDeriva();
        ObjectNode pedido = pedido("COSTO_PORCENTAJE", "10").put("proveedorId", e.proveedor());
        JsonNode previa = vistaPrevia(e.tenant().dueno(), pedido);

        jdbc.update("update ferreteria set redondeo_multiplo = 50 where id = ?", e.tenant().ferreteriaId());

        assertConflicto(e.tenant(), pedido, previa.get("huella").asText());
    }

    @Test
    void siEntraUnArticuloNuevoAlFiltroOUnoSeDaDeBaja_aplicarDa409() throws Exception {
        Escenario e = escenarioDeDeriva();
        ObjectNode pedido = pedido("COSTO_PORCENTAJE", "10").put("proveedorId", e.proveedor());
        JsonNode previa = vistaPrevia(e.tenant().dueno(), pedido);

        insertarArticulo(e.tenant(), e.proveedor(), e.rubro(), "50.00", null, "90.00", true);
        assertConflicto(e.tenant(), pedido, previa.get("huella").asText());

        // Otra vista previa (con el articulo nuevo) y ahora se da de baja uno del alcance.
        JsonNode segunda = vistaPrevia(e.tenant().dueno(), pedido);
        delete("/api/articulos/" + e.articulos().get(0), e.tenant().dueno()).andExpect(status().isNoContent());
        assertConflicto(e.tenant(), pedido, segunda.get("huella").asText());
    }

    @Test
    void laHuellaSoloValeParaElMismoPedido_otroValorOtroTextoOtroModoOtraBandera_dan409() throws Exception {
        Escenario e = escenarioDeDeriva();
        ObjectNode pedido = pedido("COSTO_PORCENTAJE", "10").put("proveedorId", e.proveedor());
        String huella = vistaPrevia(e.tenant().dueno(), pedido).get("huella").asText();

        // valor 10 en la vista previa y 15 al aplicar, con la misma huella.
        assertConflicto(e.tenant(), pedido("COSTO_PORCENTAJE", "15").put("proveedorId", e.proveedor()), huella);
        // Otro texto de busqueda (aunque alcance a los mismos articulos), otro modo, la bandera y sin proveedor.
        assertConflicto(e.tenant(), pedido("COSTO_PORCENTAJE", "10").put("proveedorId", e.proveedor()).put("q", "ART"), huella);
        assertConflicto(e.tenant(), pedido("MARGEN", "10").put("proveedorId", e.proveedor()), huella);
        assertConflicto(e.tenant(), pedido("COSTO_PORCENTAJE", "10").put("proveedorId", e.proveedor())
                .put("incluirInactivos", true), huella);
        assertConflicto(e.tenant(), pedido("COSTO_PORCENTAJE", "10"), huella);
        assertConflicto(e.tenant(), pedido("COSTO_PORCENTAJE", "10").put("proveedorId", e.proveedor()), "abc");
    }

    @Test
    void loQueNoEntraEnLaHuellaNoDa409_descripcionDeUnoDelAlcanceCostoDeUnoFueraDelAlcanceOSinCambios() throws Exception {
        Escenario e = escenarioDeDeriva();
        long fuera = insertarArticulo(e.tenant(), null, null, "100.00", null, "160.00", true);
        ObjectNode pedido = pedido("COSTO_PORCENTAJE", "10").put("proveedorId", e.proveedor());

        // Sin cambios entre la vista previa y aplicar: 201.
        aplicar(e.tenant().dueno(), pedido, vistaPrevia(e.tenant().dueno(), pedido));
        assertThat(decimal(e.articulos().get(0), "costo")).isEqualByComparingTo("110.00");

        // La descripcion de uno del alcance y el costo de uno fuera del alcance no son parte de la huella.
        JsonNode previa = vistaPrevia(e.tenant().dueno(), pedido);
        editarArticulo(e.tenant(), e.articulos().get(2), c -> c.put("descripcion", "Descripcion editada a mano"));
        editarArticulo(e.tenant(), fuera, c -> c.put("costo", new BigDecimal("999.00")));

        JsonNode resultado = aplicar(e.tenant().dueno(), pedido, previa);

        assertThat(resultado.at("/conteos/modificados").asInt()).isEqualTo(3);
        assertThat(decimal(e.articulos().get(2), "costo")).isEqualByComparingTo("363.00");
        assertThat(decimal(fuera, "costo")).isEqualByComparingTo("999.00");
        assertThat(jdbc.queryForObject("select descripcion from articulo where id = ?", String.class, e.articulos().get(2)))
                .isEqualTo("Descripcion editada a mano");
    }

    // --- Atomicidad y rollback (R-017, R-021) ---

    @Test
    void siFallaElGuardadoDelHistorialNingunArticuloCambiaYNoQuedaNingunaEntradaMasivo() throws Exception {
        Escenario e = escenarioDeDeriva();
        Tenant t = e.tenant();
        ObjectNode pedido = pedido("COSTO_PORCENTAJE", "10").put("proveedorId", e.proveedor());
        JsonNode previa = vistaPrevia(t.dueno(), pedido);
        List<Map<String, Object>> antes = articulos(t);
        doThrow(new IllegalStateException("falla simulada")).when(historialPrecioRepository).saveAll(any());

        postJson(APLICAR, t.dueno(), conHuella(pedido.deepCopy(), previa.get("huella").asText()).toString())
                .andExpect(status().isInternalServerError());

        // Ni el costo, ni el margen, ni el precio de ningun articulo: todo se revirtio.
        assertThat(articulos(t)).isEqualTo(antes);
        assertThat(jdbc.queryForObject("select count(*) from historial_precio where ferreteria_id = ? and origen = 'MASIVO'",
                Long.class, t.ferreteriaId())).isZero();
        assertThat(historial(t)).isZero();

        // El mismo pedido, sin la falla, funciona: 201 con los tres articulos y sus tres entradas.
        reset(historialPrecioRepository);
        JsonNode resultado = aplicar(t.dueno(), pedido, previa);
        assertThat(resultado.get("entradasDeHistorial").asInt()).isEqualTo(3);
        assertThat(decimal(e.articulos().get(0), "costo")).isEqualByComparingTo("110.00");
        assertThat(decimal(e.articulos().get(1), "costo")).isEqualByComparingTo("220.00");
        assertThat(decimal(e.articulos().get(2), "costo")).isEqualByComparingTo("330.00");
        assertThat(jdbc.queryForObject("select count(*) from historial_precio where ferreteria_id = ? and origen = 'MASIVO'",
                Long.class, t.ferreteriaId())).isEqualTo(3L);
    }

    @Test
    void siFallaElGuardadoDelHistorialEnModoMargenNoSeFijaNingunMargen() throws Exception {
        Escenario e = escenarioDeDeriva();
        Tenant t = e.tenant();
        ObjectNode pedido = pedido("MARGEN", "50").put("proveedorId", e.proveedor());
        JsonNode previa = vistaPrevia(t.dueno(), pedido);
        List<Map<String, Object>> antes = articulos(t);
        doThrow(new IllegalStateException("falla simulada")).when(historialPrecioRepository).saveAll(any());

        postJson(APLICAR, t.dueno(), conHuella(pedido.deepCopy(), previa.get("huella").asText()).toString())
                .andExpect(status().isInternalServerError());

        assertThat(articulos(t)).isEqualTo(antes);
        assertThat(jdbc.queryForObject("select count(*) from articulo where ferreteria_id = ? and margen_porcentaje is not null",
                Long.class, t.ferreteriaId())).isZero();
        assertThat(historial(t)).isZero();
    }

    // --- Filtros e inactivos por HTTP, paridad con el listado (R-004, R-005, R-022) ---

    /** Articulo (costo 100.00, precio 160.00) con la descripcion dada. */
    private long articuloDescrito(Tenant t, String descripcion, Long proveedorId, Long rubroId, boolean activo) {
        long id = insertarArticulo(t, proveedorId, rubroId, "100.00", null, "160.00", activo);
        jdbc.update("update articulo set descripcion = ? where id = ?", descripcion, id);
        return id;
    }

    private int total(Tenant t, ObjectNode pedido) throws Exception {
        return vistaPrevia(t.dueno(), pedido).at("/conteos/total").asInt();
    }

    @Test
    void proveedorRubroYTextoSeCombinanConAndYCadaCondicionAusenteNoRestringe() throws Exception {
        Tenant t = crearTenant();
        long p1 = crearProveedor(t);
        long p2 = crearProveedor(t);
        long electricidad = crearRubro(t, "40");
        long plomeria = crearRubro(t, "35");
        // P1: 10 articulos, 4 de Electricidad (2 con "cable"), 3 de Plomeria y 3 sin rubro.
        List<Long> cables = List.of(
                articuloDescrito(t, "Cable 2x1.5 mm", p1, electricidad, true),
                articuloDescrito(t, "Cable unipolar 4 mm", p1, electricidad, true));
        articuloDescrito(t, "Llave de luz", p1, electricidad, true);
        articuloDescrito(t, "Toma doble", p1, electricidad, true);
        for (int i = 0; i < 3; i++) {
            articuloDescrito(t, "Canilla " + i, p1, plomeria, true);
        }
        for (int i = 0; i < 3; i++) {
            articuloDescrito(t, "Varios " + i, p1, null, true);
        }
        // P2: una lampara de Electricidad y un caño de Plomeria.
        articuloDescrito(t, "Lampara led", p2, electricidad, true);
        articuloDescrito(t, "Caño de agua", p2, plomeria, true);

        assertThat(total(t, pedido("COSTO_PORCENTAJE", "10").put("proveedorId", p1))).isEqualTo(10);
        assertThat(total(t, pedido("COSTO_PORCENTAJE", "10").put("proveedorId", p1).put("rubroId", electricidad))).isEqualTo(4);
        assertThat(total(t, pedido("COSTO_PORCENTAJE", "10").put("proveedorId", p1).put("rubroId", electricidad)
                .put("q", "cable"))).isEqualTo(2);
        // Solo el rubro: de cualquier proveedor. Solo el texto: de cualquier proveedor y rubro.
        assertThat(total(t, pedido("COSTO_PORCENTAJE", "10").put("rubroId", electricidad))).isEqualTo(5);
        assertThat(total(t, pedido("COSTO_PORCENTAJE", "10").put("q", "cable"))).isEqualTo(2);
        assertThat(total(t, pedido("COSTO_PORCENTAJE", "10").put("proveedorId", p2).put("q", "cable"))).isZero();

        // Aplicar con proveedor + rubro + texto toca solo esos dos.
        ObjectNode pedido = pedido("COSTO_PORCENTAJE", "10").put("proveedorId", p1).put("rubroId", electricidad).put("q", "cable");
        aplicar(t.dueno(), pedido, vistaPrevia(t.dueno(), pedido));
        assertThat(jdbc.queryForObject("select count(*) from articulo where ferreteria_id = ? and costo <> 100.00",
                Long.class, t.ferreteriaId())).isEqualTo(2L);
        for (long cable : cables) {
            assertThat(decimal(cable, "costo")).isEqualByComparingTo("110.00");
        }
    }

    @Test
    void elFiltroVacioAlcanzaATodoElCatalogoActivoYElTextoEnBlancoEquivaleASuAusencia() throws Exception {
        Tenant t = crearTenant();
        for (int i = 0; i < 7; i++) {
            articuloDescrito(t, "Activo " + i, null, null, true);
        }
        articuloDescrito(t, "De baja 1", null, null, false);
        articuloDescrito(t, "De baja 2", null, null, false);

        assertThat(total(t, pedido("COSTO_PORCENTAJE", "10"))).isEqualTo(7);
        assertThat(total(t, pedido("COSTO_PORCENTAJE", "10").put("q", "   "))).isEqualTo(7);
        assertThat(total(t, pedido("COSTO_PORCENTAJE", "10").put("q", ""))).isEqualTo(7);
        assertThat(total(t, pedido("COSTO_PORCENTAJE", "10").put("incluirInactivos", true))).isEqualTo(9);
        // Un texto sin espacios sobrantes da lo mismo que con ellos.
        assertThat(total(t, pedido("COSTO_PORCENTAJE", "10").put("q", "  Activo  "))).isEqualTo(7);
    }

    @Test
    void losDadosDeBajaSeExcluyenPorDefectoYConLaBanderaSeActualizanYSiguenDadosDeBaja() throws Exception {
        Tenant t = crearTenant();
        long rubro = crearRubro(t, "40");
        List<Long> activos = List.of(
                articuloDescrito(t, "Activo A", null, rubro, true),
                articuloDescrito(t, "Activo B", null, rubro, true));
        List<Long> deBaja = List.of(
                articuloDescrito(t, "De baja A", null, rubro, false),
                articuloDescrito(t, "De baja B", null, rubro, false));
        List<Map<String, Object>> deBajaAntes = deBaja.stream().map(this::fila).toList();

        // Sin la bandera: 2 en el alcance; los dados de baja conservan todo y no tienen historial nuevo.
        ObjectNode sinBandera = pedido("COSTO_PORCENTAJE", "10").put("rubroId", rubro);
        JsonNode previa = vistaPrevia(t.dueno(), sinBandera);
        assertThat(previa.at("/conteos/total").asInt()).isEqualTo(2);
        aplicar(t.dueno(), sinBandera, previa);
        assertThat(deBaja.stream().map(this::fila).toList()).isEqualTo(deBajaAntes);
        deBaja.forEach(id -> assertThat(entradas(id)).isEmpty());
        activos.forEach(id -> assertThat(decimal(id, "costo")).isEqualByComparingTo("110.00"));

        // Con la bandera: 4 en el alcance; los dos dados de baja se actualizan y siguen dados de baja.
        ObjectNode conBandera = pedido("COSTO_PORCENTAJE", "10").put("rubroId", rubro).put("incluirInactivos", true);
        JsonNode previaConBandera = vistaPrevia(t.dueno(), conBandera);
        assertThat(previaConBandera.at("/conteos/total").asInt()).isEqualTo(4);
        aplicar(t.dueno(), conBandera, previaConBandera);
        for (long id : deBaja) {
            assertThat(decimal(id, "costo")).isEqualByComparingTo("110.00");
            assertThat(fila(id).get("activo")).isEqualTo(false);
            assertThat(entradas(id)).hasSize(1);
        }
        activos.forEach(id -> assertThat(fila(id).get("activo")).isEqualTo(true));
    }

    @Test
    void elTextoMatcheaLosMismosArticulosQueElListadoYLosComodinesSonLiterales() throws Exception {
        Tenant t = crearTenant();
        articuloDescrito(t, "Cable 2x1.5 mm", null, null, true);
        articuloDescrito(t, "CABLE unipolar 4 mm", null, null, true);
        articuloDescrito(t, "Cañería de cobre", null, null, true);
        articuloDescrito(t, "Tuerca 50% zincada", null, null, true);
        articuloDescrito(t, "Tuerca 500 zincada", null, null, true);
        articuloDescrito(t, "Perno 50_x", null, null, true);
        articuloDescrito(t, "Perno 50ax", null, null, true);
        articuloDescrito(t, "Cable de baja", null, null, false);
        // texto -> cuantos articulos activos coinciden (mayusculas y tildes no importan; % y _ son literales)
        Map<String, Integer> esperados = new java.util.LinkedHashMap<>();
        esperados.put("cable", 2);
        esperados.put("CABLE", 2);
        esperados.put("caneria", 1);
        esperados.put("CAÑERÍA", 1);
        esperados.put("50%", 1);
        esperados.put("50_", 1);
        esperados.put("tuerca", 2);
        esperados.put("  cable  ", 2);
        esperados.put("zzz-sin-coincidencias", 0);

        for (Map.Entry<String, Integer> caso : esperados.entrySet()) {
            for (boolean inactivos : List.of(false, true)) {
                // MARGEN con un valor que cambia a todos (margen propio nulo hoy): el detalle trae todo el alcance.
                JsonNode previa = vistaPrevia(t.dueno(),
                        pedido("MARGEN", "77").put("q", caso.getKey()).put("incluirInactivos", inactivos));
                List<Long> delAlcance = new java.util.ArrayList<>();
                previa.get("cambios").forEach(c -> delAlcance.add(c.get("articuloId").asLong()));
                int esperado = caso.getValue() + (inactivos && caso.getKey().trim().equalsIgnoreCase("cable") ? 1 : 0);

                assertThat(delAlcance).as("alcance de '" + caso.getKey() + "' inactivos=" + inactivos).hasSize(esperado);
                assertThat(previa.at("/conteos/total").asInt()).isEqualTo(esperado);
                assertThat(idsDelListado(t, caso.getKey(), inactivos))
                        .as("listado de '" + caso.getKey() + "' inactivos=" + inactivos)
                        .containsExactlyInAnyOrderElementsOf(delAlcance);
            }
        }
    }

    @Test
    void elListadoDeArticulosIgnoraProveedorIdYRubroId() throws Exception {
        Tenant t = crearTenant();
        long p1 = crearProveedor(t);
        long p2 = crearProveedor(t);
        long rubro = crearRubro(t, "40");
        String marca = unico("marca");
        articuloDescrito(t, "Uno " + marca, p1, rubro, true);
        articuloDescrito(t, "Dos " + marca, p2, null, true);

        List<Long> todos = idsDelListado(t, marca, false);
        assertThat(todos).hasSize(2);
        for (String extra : List.of("proveedorId=" + p1, "rubroId=" + rubro)) {
            JsonNode pagina = getJson("/api/articulos?q=" + marca + "&size=100&" + extra, t.dueno());
            assertThat(pagina.get("totalElements").asInt()).isEqualTo(2);
        }
        // En cambio la actualizacion masiva SI filtra por proveedor y por rubro.
        assertThat(total(t, pedido("COSTO_PORCENTAJE", "10").put("q", marca).put("proveedorId", p1))).isEqualTo(1);
        assertThat(total(t, pedido("COSTO_PORCENTAJE", "10").put("q", marca).put("rubroId", rubro))).isEqualTo(1);
    }

    /** Los ids que devuelve GET /api/articulos para ese texto (el texto viaja como parametro codificado). */
    private List<Long> idsDelListado(Tenant t, String q, boolean incluirInactivos) throws Exception {
        String cuerpo = mvc.perform(MockMvcRequestBuilders.get("/api/articulos")
                        .header("Authorization", "Bearer " + t.dueno())
                        .param("q", q).param("size", "100").param("incluirInactivos", String.valueOf(incluirInactivos)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        List<Long> ids = new java.util.ArrayList<>();
        objectMapper.readTree(cuerpo).get("items").forEach(n -> ids.add(n.get("id").asLong()));
        return ids;
    }

    // --- Precio manual y ajustes sobre el precio final (CU-14) ---

    @Test
    void costoMasDiezPorCientoRespetaElPrecioManualYAplicaLosAjustesComoLaVistaPrevia() throws Exception {
        Tenant t = crearTenant();
        long manual = insertarArticulo(t, "100.00", "500.00");
        jdbc.update("update articulo set precio_manual = true where id = ?", manual);
        long conAjuste = insertarArticulo(t, "100.00", "180.00");
        jdbc.update("update articulo set ajustes_precio = ? where id = ?",
                "[{\"nombre\":\"Flete\",\"tipo\":\"PORCENTAJE\",\"valor\":10}]", conAjuste);
        ObjectNode pedido = pedido("COSTO_PORCENTAJE", "10");

        JsonNode previa = vistaPrevia(t.dueno(), pedido);
        JsonNode cambios = previa.get("cambios");
        assertThat(cambios).hasSize(2);
        assertThat(cambios.get(0).get("articuloId").asLong()).isEqualTo(manual);
        assertThat(dec(cambios.get(0).get("precioNuevo"))).isEqualByComparingTo("500.00");
        // 110 x 1,30 x 1,21 = 173,03 (180 al multiplo 10); +10 % = 198, 200 al multiplo 10.
        assertThat(cambios.get(1).get("articuloId").asLong()).isEqualTo(conAjuste);
        assertThat(dec(cambios.get(1).get("precioNuevo"))).isEqualByComparingTo("200.00");

        aplicar(t.dueno(), pedido, previa);

        assertThat(decimal(manual, "costo")).isEqualByComparingTo("110.00");
        assertThat(decimal(manual, "precio_final")).isEqualByComparingTo("500.00");
        assertThat(jdbc.queryForObject("select precio_manual from articulo where id = ?", Boolean.class, manual))
                .isTrue();
        assertThat(decimal(conAjuste, "precio_final")).isEqualByComparingTo("200.00");
    }

    @Test
    void precioFinalMasDiezPorCientoDejaPrecioManualSinTocarElCostoYGuardaHistorialMasivo() throws Exception {
        Tenant t = crearTenant();
        jdbc.update("update ferreteria set redondeo_multiplo = 0 where id = ?", t.ferreteriaId());
        long id = insertarArticulo(t, "100.00", "170.00");
        ObjectNode pedido = pedido("PRECIO_PORCENTAJE", "10");

        JsonNode previa = vistaPrevia(t.dueno(), pedido);
        assertThat(dec(previa.at("/cambios/0/precioNuevo"))).isEqualByComparingTo("187.00");

        aplicar(t.dueno(), pedido, previa);

        assertThat(decimal(id, "costo")).isEqualByComparingTo("100.00");
        assertThat(decimal(id, "precio_final")).isEqualByComparingTo("187.00");
        assertThat(jdbc.queryForObject("select precio_manual from articulo where id = ?", Boolean.class, id)).isTrue();
        assertThat(entradas(id)).singleElement().satisfies(e -> {
            assertThat(e.get("origen")).isEqualTo("MASIVO");
            assertThat((BigDecimal) e.get("precio_anterior")).isEqualByComparingTo("170.00");
            assertThat((BigDecimal) e.get("precio_nuevo")).isEqualByComparingTo("187.00");
        });
    }

    @Test
    void precioFinalEnMontoSumaPesosYSusValidacionesSonLasDelCosto() throws Exception {
        Tenant t = crearTenant();
        jdbc.update("update ferreteria set redondeo_multiplo = 0 where id = ?", t.ferreteriaId());
        insertarArticulo(t, "100.00", "170.00");

        assertThat(dec(vistaPrevia(t.dueno(), pedido("PRECIO_MONTO", "-20.50")).at("/cambios/0/precioNuevo")))
                .isEqualByComparingTo("149.50");
        postJson(VISTA_PREVIA, t.dueno(), pedido("PRECIO_PORCENTAJE", "0").toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.porcentajeDistintoDeCero", is("El porcentaje no puede ser cero.")));
        postJson(VISTA_PREVIA, t.dueno(), pedido("PRECIO_PORCENTAJE", "1000.01").toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.porcentajeEnRango", is("El porcentaje tiene que estar entre -99,99 y 1.000.")));
        postJson(VISTA_PREVIA, t.dueno(), pedido("PRECIO_MONTO", "0").toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.montoDistintoDeCero", is("El monto no puede ser cero.")));
    }

    // --- Cambiar el rubro (CU-05, #14) ---

    /** Pedido de cambio de rubro: sin valor, con el destino. */
    private ObjectNode cambiarRubro(long destino) {
        return pedido("CAMBIAR_RUBRO", null).put("rubroDestinoId", destino);
    }

    private long rubroDe(long articuloId) {
        return jdbc.queryForObject("select rubro_id from articulo where id = ?", Long.class, articuloId);
    }

    @Test
    void cambiarElRubroConElFiltroDeUnProveedorMueveLosTresYSoloElHeredadoCambiaDePrecioConHistorial() throws Exception {
        Tenant t = crearTenant();
        long proveedor = crearProveedor(t);
        long origen = crearRubro(t, "40");
        long destino = crearRubro(t, "60");
        // 100 x 1,40 x 1,21 = 169,40 -> 170; con el 60 % del destino, 193,60 -> 200. Margen propio 50: 190 en los dos.
        long heredado = insertarArticulo(t, proveedor, origen, "100.00", null, "170.00", true);
        long propio = insertarArticulo(t, proveedor, origen, "100.00", "50.00", "190.00", true);
        long manual = insertarArticulo(t, proveedor, origen, "100.00", null, "500.00", true);
        jdbc.update("update articulo set precio_manual = true where id = ?", manual);
        long deOtroProveedor = insertarArticulo(t, null, origen, "100.00", null, "170.00", true);
        ObjectNode pedido = cambiarRubro(destino).put("proveedorId", proveedor);
        List<Map<String, Object>> antes = articulos(t);

        JsonNode previa = vistaPrevia(t.dueno(), pedido);

        assertThat(articulos(t)).isEqualTo(antes);
        assertThat(previa.at("/conteos/total").asInt()).isEqualTo(3);
        assertThat(previa.at("/conteos/modificados").asInt()).isEqualTo(3);
        assertThat(previa.at("/conteos/suben").asInt()).isEqualTo(1);
        assertThat(previa.at("/conteos/sinCambioDePrecio").asInt()).isEqualTo(2);
        JsonNode cambios = previa.get("cambios");
        assertThat(cambios).hasSize(3);
        assertThat(cambios.get(0).get("articuloId").asLong()).isEqualTo(heredado);
        assertThat(dec(cambios.get(0).get("precioAnterior"))).isEqualByComparingTo("170.00");
        assertThat(dec(cambios.get(0).get("precioNuevo"))).isEqualByComparingTo("200.00");
        assertThat(dec(cambios.get(1).get("precioNuevo"))).isEqualByComparingTo("190.00");
        assertThat(dec(cambios.get(2).get("precioNuevo"))).isEqualByComparingTo("500.00");
        for (JsonNode cambio : cambios) {
            assertThat(cambio.get("rubroAnteriorId").asLong()).isEqualTo(origen);
            assertThat(cambio.get("rubroNuevoId").asLong()).isEqualTo(destino);
        }

        JsonNode resultado = aplicar(t.dueno(), pedido, previa);

        assertThat(resultado.at("/conteos/modificados").asInt()).isEqualTo(3);
        assertThat(resultado.get("entradasDeHistorial").asInt()).isEqualTo(1);
        assertThat(List.of(rubroDe(heredado), rubroDe(propio), rubroDe(manual))).containsOnly(destino);
        assertThat(rubroDe(deOtroProveedor)).isEqualTo(origen);
        assertThat(decimal(heredado, "precio_final")).isEqualByComparingTo("200.00");
        assertThat(decimal(propio, "precio_final")).isEqualByComparingTo("190.00");
        assertThat(decimal(manual, "precio_final")).isEqualByComparingTo("500.00");
        assertThat(jdbc.queryForObject("select precio_manual from articulo where id = ?", Boolean.class, manual))
                .isTrue();
        assertThat(entradas(heredado)).singleElement().satisfies(e -> {
            assertThat(e.get("origen")).isEqualTo("MASIVO");
            assertThat((BigDecimal) e.get("precio_anterior")).isEqualByComparingTo("170.00");
            assertThat((BigDecimal) e.get("precio_nuevo")).isEqualByComparingTo("200.00");
        });
        assertThat(entradas(propio)).isEmpty();
        assertThat(entradas(manual)).isEmpty();
    }

    @Test
    void cambiarElRubroSinDestinoOConValorYUnDestinoConOtroModoDan400PorCampo() throws Exception {
        Tenant t = crearTenant();
        long destino = crearRubro(t, "60");
        insertarArticulo(t, "100.00", "160.00");
        List<Map<String, Object>> antes = articulos(t);

        for (String url : ENDPOINTS) {
            postJson(url, t.dueno(), conHuella(pedido("CAMBIAR_RUBRO", null), "abc").toString())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.rubroDestinoElegido", is("Elegí el rubro al que pasan los artículos.")));
            postJson(url, t.dueno(), conHuella(pedido("CAMBIAR_RUBRO", "10").put("rubroDestinoId", destino), "abc").toString())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.sinValorAlCambiarRubro", is("Al cambiar el rubro no va un valor.")));
            postJson(url, t.dueno(), conHuella(pedido("MARGEN", "30").put("rubroDestinoId", destino), "abc").toString())
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors.rubroDestinoSoloAlCambiarRubro",
                            is("El rubro destino va solo al cambiar el rubro.")));
        }

        assertThat(articulos(t)).isEqualTo(antes);
        assertThat(historial(t)).isZero();
    }

    @Test
    void siCambiaElRubroDeUnArticuloEntreLaVistaPreviaYAplicarElCambioDeRubroDa409() throws Exception {
        Escenario e = escenarioDeDeriva();
        // Otro rubro con el mismo margen (40): mover el articulo ahi no cambia su precio, solo su rubro.
        long otro = crearRubro(e.tenant(), "40");
        long destino = crearRubro(e.tenant(), "60");
        ObjectNode pedido = cambiarRubro(destino).put("proveedorId", e.proveedor());
        JsonNode previa = vistaPrevia(e.tenant().dueno(), pedido);

        editarArticulo(e.tenant(), e.articulos().get(1), c -> c.put("rubroId", otro));

        assertConflicto(e.tenant(), pedido, previa.get("huella").asText());
    }

    // FIN-DE-TESTS
}
