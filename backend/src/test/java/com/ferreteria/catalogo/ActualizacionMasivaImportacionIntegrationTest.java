package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ferreteria.catalogo.model.HistorialPrecio;
import com.ferreteria.catalogo.repository.HistorialPrecioRepository;
import com.ferreteria.cotizacion.model.LadoCotizacion;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.SpyBean;

/**
 * La actualizacion masiva por importacion y por monto (corte 3, T-226) por HTTP contra MySQL real: el filtro
 * {@code importacionId} (R-201), el modo {@code COSTO_MONTO} (R-204 a R-206) y el aviso de mezcla de monedas (R-207).
 * Una ferreteria propia por clase (CUIT 30-78406101-1, subserie 1x) con un dolar vigente de 1.000; el multiplo de
 * redondeo es 10 y el margen por defecto 30 (costo x 1,573 redondeado hacia arriba a la decena).
 */
class ActualizacionMasivaImportacionIntegrationTest extends AjustesTestBase {

    private static final String VISTA_PREVIA = "/api/actualizacion-masiva/vista-previa";
    private static final String APLICAR = "/api/actualizacion-masiva";
    private static final Instant HACE_UN_RATO = Instant.parse("2026-10-16T11:00:00Z");
    private static final String DERIVA = "Los artículos cambiaron desde la vista previa. "
            + "Volvé a generar la vista previa y confirmá de nuevo.";

    @SpyBean
    HistorialPrecioRepository historialPrecioRepository;

    private Long f;
    private Long usuarioDueno;
    private String dueno;
    private Long proveedorP;
    private Long proveedorQ;
    private Long i1;
    private Long i2;

    @BeforeEach
    void sembrar() throws Exception {
        f = ferreteria("30-78406101-1", "Masiva por importacion");
        borrarDatosDe(f);
        borrarCotizaciones("t226-");
        cotizacion("USD", "t226-oficial", "990", "1000", HACE_UN_RATO);
        configuracion(f, c -> {
            c.setCasaPrecios("t226-oficial");
            c.fijarVigente(new BigDecimal("1000.0000"), "t226-oficial", LadoCotizacion.VENTA, HACE_UN_RATO);
        });
        dueno = login(usuario(f, "dueno-t226@test.local", "DUENO"));
        usuarioDueno = usuarioId("dueno-t226@test.local");
        proveedorP = proveedor(f, "Proveedor P");
        proveedorQ = proveedor(f, "Proveedor Q");
        i1 = importacion(proveedorP, "i1.csv");
        i2 = importacion(proveedorP, "i2.csv");
    }

    @AfterEach
    void limpiar() {
        reset(historialPrecioRepository);
        borrarCotizaciones("t226-");
        borrarDatosDe(f);
    }

    // ---- helpers ----

    private Long importacion(Long proveedorId, String archivo) {
        jdbc.update("insert into importacion_lista (ferreteria_id, proveedor_id, usuario_id, archivo, suben, bajan, "
                + "nuevos, sin_cambio, no_estan, con_error, estado) values (?, ?, ?, ?, 0, 0, 0, 0, 0, 0, 'APLICADA')",
                f, proveedorId, usuarioDueno, archivo);
        return jdbc.queryForObject("select max(id) from importacion_lista where ferreteria_id = ? and archivo = ?",
                Long.class, f, archivo);
    }

    private Long tocado(Long articuloId, Long importacionId) {
        jdbc.update("update articulo set ultima_importacion_id = ? where id = ?", importacionId, articuloId);
        return articuloId;
    }

    /** Un articulo en pesos tocado por esa importacion, de ese proveedor, con la lista, los ajustes y la base de lista. */
    private Long enPesos(String codigo, Long importacionId, String costo, String precio) {
        Long id = importado(f, codigo, proveedorP, "1210.00", true, valores("10", "5", null), valores("-5", "3", null),
                costo, precio);
        return tocado(id, importacionId);
    }

    private Long enDolares(String codigo, Long importacionId, String costo, String precio) {
        return tocado(articuloUsd(f, codigo, costo, precio), importacionId);
    }

    private ObjectNode pedido(String modo, String valor, Long importacionId) {
        ObjectNode pedido = objectMapper.createObjectNode();
        pedido.put("modo", modo);
        pedido.put("valor", new BigDecimal(valor));
        if (importacionId != null) {
            pedido.put("importacionId", importacionId);
        }
        return pedido;
    }

    private JsonNode vistaPrevia(ObjectNode pedido) throws Exception {
        return objectMapper.readTree(postJson(VISTA_PREVIA, dueno, pedido.toString())
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private JsonNode aplicar(ObjectNode pedido, JsonNode vistaPrevia) throws Exception {
        ObjectNode cuerpo = pedido.deepCopy().put("huella", vistaPrevia.get("huella").asText());
        return objectMapper.readTree(postJson(APLICAR, dueno, cuerpo.toString())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private BigDecimal costoDe(Long id) {
        return jdbc.queryForObject("select costo from articulo where id = ?", BigDecimal.class, id);
    }

    private long masivosDe(Long id) {
        return jdbc.queryForObject("select count(*) from historial_precio where articulo_id = ? and origen = 'MASIVO'",
                Long.class, id);
    }

    private long historialDeLaFerreteria() {
        return historialDeLaFerreteria(f);
    }

    private static int conteo(JsonNode respuesta, String campo) {
        return respuesta.at("/conteos/" + campo).asInt();
    }

    // ---- R-201: filtro por importacion ----

    @Test
    @DisplayName("I1 toco A, B y C y la posterior I2 toco B: +5 con I1 alcanza a A y C; B, el manual y los de otra importacion no cambian")
    void filtraPorLaUltimaImportacion() throws Exception {
        Long a = enPesos("A", i1, "100.00", "160.00");
        Long b = enPesos("B", i1, "100.00", "160.00");
        Long c = enPesos("C", i1, "200.00", "320.00");
        tocado(b, i2);
        Long manual = manual(f, "MANUAL", "100.00", "160.00");
        Long deQ = tocado(importado(f, "OTRA", proveedorQ, "1210.00", true, NADA, NADA, "100.00", "160.00"), i2);
        ObjectNode pedido = pedido("COSTO_PORCENTAJE", "5", i1);

        JsonNode previa = vistaPrevia(pedido);
        assertThat(conteo(previa, "total")).isEqualTo(2);
        assertThat(previa.get("cambios")).extracting(n -> n.get("articuloId").asLong()).containsExactly(a, c);

        aplicar(pedido, previa);

        assertThat(costoDe(a)).isEqualByComparingTo("105.00");
        assertThat(precioDe(a)).isEqualByComparingTo("170.00");
        assertThat(costoDe(c)).isEqualByComparingTo("210.00");
        for (Long intacto : List.of(b, manual, deQ)) {
            assertThat(costoDe(intacto)).isEqualByComparingTo("100.00");
            assertThat(precioDe(intacto)).isEqualByComparingTo("160.00");
            assertThat(historialDelArticulo(intacto)).isZero();
        }
    }

    @Test
    @DisplayName("la importacion de otro proveedor en el AND: la vista previa da todo en 0 y aplicar 400")
    void importacionYProveedorAjenoDan0() throws Exception {
        enPesos("A", i1, "100.00", "160.00");
        ObjectNode pedido = pedido("COSTO_MONTO", "10", i1).put("proveedorId", proveedorQ);

        JsonNode previa = vistaPrevia(pedido);

        assertThat(conteo(previa, "total")).isZero();
        postJson(APLICAR, dueno, pedido.deepCopy().put("huella", previa.get("huella").asText()).toString())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is("No hay artículos para actualizar con ese filtro.")));
    }

    @Test
    @DisplayName("con el rubro: solo entran los de la importacion que tienen ese rubro")
    void importacionYRubro() throws Exception {
        Long rubro = rubro(f, "Tornilleria", "35.00");
        Long conRubro = enPesos("CON", i1, "100.00", "160.00");
        enPesos("SIN", i1, "100.00", "160.00");
        jdbc.update("update articulo set rubro_id = ? where id = ?", rubro, conRubro);

        JsonNode previa = vistaPrevia(pedido("COSTO_MONTO", "10", i1).put("rubroId", rubro));

        assertThat(conteo(previa, "total")).isEqualTo(1);
        assertThat(previa.at("/cambios/0/articuloId").asLong()).isEqualTo(conRubro);
    }

    @Test
    @DisplayName("un inactivo de la importacion entra solo con incluirInactivos")
    void inactivosSoloConLaBandera() throws Exception {
        enPesos("ACTIVO", i1, "100.00", "160.00");
        Long inactivo = enPesos("INACTIVO", i1, "100.00", "160.00");
        jdbc.update("update articulo set activo = false where id = ?", inactivo);

        assertThat(conteo(vistaPrevia(pedido("COSTO_MONTO", "10", i1)), "total")).isEqualTo(1);
        assertThat(conteo(vistaPrevia(pedido("COSTO_MONTO", "10", i1).put("incluirInactivos", true)), "total"))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("aplicar con otra importacion que la de la vista previa da 409, y tambien si otra importacion toca un articulo del alcance")
    void derivaPorImportacion() throws Exception {
        Long a = enPesos("A", i1, "100.00", "160.00");
        enPesos("C", i1, "200.00", "320.00");
        enPesos("B", i2, "300.00", "480.00");
        ObjectNode pedido = pedido("COSTO_MONTO", "10", i1);
        JsonNode previa = vistaPrevia(pedido);

        postJson(APLICAR, dueno, pedido("COSTO_MONTO", "10", i2).put("huella", previa.get("huella").asText()).toString())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message", is(DERIVA)));

        tocado(a, i2);
        postJson(APLICAR, dueno, pedido.deepCopy().put("huella", previa.get("huella").asText()).toString())
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message", is(DERIVA)));

        assertThat(costoDe(a)).isEqualByComparingTo("100.00");
        assertThat(historialDeLaFerreteria()).isZero();
    }

    // ---- R-204 a R-206: COSTO_MONTO ----

    /** Los tres articulos de R-206: A1 (1.000 / 1.580), A2 (500 / 790) y A4 (50 / 80). */
    private List<Long> tresEnPesos() {
        return List.of(
                enPesos("A1", i1, "1000.00", "1580.00"),
                enPesos("A2", i1, "500.00", "790.00"),
                enPesos("A4", i1, "50.00", "80.00"));
    }

    @Test
    @DisplayName("+100 sobre A1, A2 y A4: 1.100 / $ 1.740, 600 / $ 950 y 150 / $ 240; solo A4 lleva advertencia")
    void masCien() throws Exception {
        List<Long> ids = tresEnPesos();
        ObjectNode pedido = pedido("COSTO_MONTO", "100", i1);

        JsonNode previa = vistaPrevia(pedido);
        JsonNode respuesta = aplicar(pedido, previa);

        assertThat(conteo(previa, "total")).isEqualTo(3);
        assertThat(conteo(previa, "suben")).isEqualTo(3);
        assertThat(conteo(previa, "bajan")).isZero();
        assertThat(conteo(previa, "sinCambioDePrecio")).isZero();
        assertThat(conteo(previa, "conError")).isZero();
        assertThat(conteo(previa, "modificados")).isEqualTo(3);
        assertThat(conteo(previa, "conAdvertencia")).isEqualTo(1);
        assertThat(conteo(previa, "pasanAMargenPropio")).isZero();
        assertThat(respuesta.get("entradasDeHistorial").asInt()).isEqualTo(3);
        assertThat(costoDe(ids.get(0))).isEqualByComparingTo("1100.00");
        assertThat(precioDe(ids.get(0))).isEqualByComparingTo("1740");
        assertThat(costoDe(ids.get(1))).isEqualByComparingTo("600.00");
        assertThat(precioDe(ids.get(1))).isEqualByComparingTo("950");
        assertThat(costoDe(ids.get(2))).isEqualByComparingTo("150.00");
        assertThat(precioDe(ids.get(2))).isEqualByComparingTo("240");
        assertThat(previa.get("cambios").get(2).get("advertencia").asText()).contains("sube un 200 %");
        assertThat(previa.get("cambios").get(0).get("advertencia").isNull()).isTrue();
    }

    @Test
    @DisplayName("-50: A1 pasa a 950 / $ 1.500, A2 a 450 / $ 710 y A4 da error (costo 0) y no se escribe")
    void menosCincuenta() throws Exception {
        List<Long> ids = tresEnPesos();
        ObjectNode pedido = pedido("COSTO_MONTO", "-50", i1);

        JsonNode previa = vistaPrevia(pedido);

        assertThat(conteo(previa, "total")).isEqualTo(3);
        assertThat(conteo(previa, "bajan")).isEqualTo(2);
        assertThat(conteo(previa, "conError")).isEqualTo(1);
        assertThat(conteo(previa, "modificados")).isEqualTo(2);
        assertThat(previa.at("/errores/0/motivo").asText()).isEqualTo("El costo nuevo da 0 o menos. Revisá el monto.");

        JsonNode respuesta = aplicar(pedido, previa);

        assertThat(respuesta.get("entradasDeHistorial").asInt()).isEqualTo(2);
        assertThat(costoDe(ids.get(0))).isEqualByComparingTo("950.00");
        assertThat(precioDe(ids.get(0))).isEqualByComparingTo("1500");
        assertThat(costoDe(ids.get(1))).isEqualByComparingTo("450.00");
        assertThat(precioDe(ids.get(1))).isEqualByComparingTo("710");
        assertThat(costoDe(ids.get(2))).isEqualByComparingTo("50.00");
        assertThat(precioDe(ids.get(2))).isEqualByComparingTo("80");
        assertThat(masivosDe(ids.get(2))).isZero();
    }

    @Test
    @DisplayName("el monto no toca la base guardada, los ajustes ni la importacion: solo el costo y el precio (R-204)")
    void noTocaLaBaseNiLosAjustes() throws Exception {
        Long id = enPesos("A1", i1, "1000.00", "1580.00");
        Map<String, Object> antes = new LinkedHashMap<>(filaDe(id));
        ObjectNode pedido = pedido("COSTO_MONTO", "100", i1);

        aplicar(pedido, vistaPrevia(pedido));

        Map<String, Object> despues = new LinkedHashMap<>(filaDe(id));
        assertThat(despues.get("costo")).isNotEqualTo(antes.get("costo"));
        assertThat(despues.get("precio_final")).isNotEqualTo(antes.get("precio_final"));
        antes.remove("costo");
        antes.remove("precio_final");
        despues.remove("costo");
        despues.remove("precio_final");
        assertThat(despues).isEqualTo(antes);
        assertThat(despues.get("precio_lista_proveedor")).isEqualTo(new BigDecimal("1210.00"));
        assertThat(despues.get("lista_incluye_iva")).isEqualTo(true);
        assertThat(((BigDecimal) despues.get("bonificacion1"))).isEqualByComparingTo("10");
        assertThat(((BigDecimal) despues.get("ajuste1"))).isEqualByComparingTo("-5");
        assertThat(((BigDecimal) despues.get("ajuste2"))).isEqualByComparingTo("3");
        assertThat(despues.get("ultima_importacion_id")).isEqualTo(i1);

        // Ya no coincide con el costo que sale de la lista: lo dice el desglose.
        assertThat(getJson("/api/articulos/" + id + "/desglose", dueno).get("coincide").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("un articulo en USD: costo 10 +5 da 15,00 y $ 23.600 con el dolar vigente 1.000")
    void montoEnDolares() throws Exception {
        Long usd = enDolares("USD1", i1, "10.00", "15730.00");
        ObjectNode pedido = pedido("COSTO_MONTO", "5", i1);

        JsonNode previa = vistaPrevia(pedido);
        aplicar(pedido, previa);

        assertThat(previa.at("/cambios/0/moneda").asText()).isEqualTo("USD");
        assertThat(costoDe(usd)).isEqualByComparingTo("15.00");
        assertThat(precioDe(usd)).isEqualByComparingTo("23600");
        assertThat(jdbc.queryForObject("select moneda from articulo where id = ?", String.class, usd)).isEqualTo("USD");
    }

    @Test
    @DisplayName("33,33 + 0,01: el costo pasa a 33,34 con el precio igual y deja su entrada de historial")
    void soloCambiaElCosto() throws Exception {
        Long id = enPesos("CENT", i1, "33.33", "60.00");
        ObjectNode pedido = pedido("COSTO_MONTO", "0.01", i1);

        JsonNode previa = vistaPrevia(pedido);
        aplicar(pedido, previa);

        assertThat(conteo(previa, "sinCambioDePrecio")).isEqualTo(1);
        assertThat(conteo(previa, "modificados")).isEqualTo(1);
        assertThat(costoDe(id)).isEqualByComparingTo("33.34");
        assertThat(precioDe(id)).isEqualByComparingTo("60");
        assertThat(masivosDe(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("una entrada MASIVO por articulo con cambio, del Dueno que aplico y con la moneda de cada uno")
    void historialConLaMonedaDeCadaArticulo() throws Exception {
        Long pesos = enPesos("P1", i1, "1000.00", "1580.00");
        Long dolares = enDolares("D1", i1, "10.00", "15730.00");
        ObjectNode pedido = pedido("COSTO_MONTO", "5", i1);

        aplicar(pedido, vistaPrevia(pedido));

        assertThat(historialDe(pesos)).singleElement().satisfies(h -> {
            assertThat(h.get("origen")).isEqualTo("MASIVO");
            assertThat(h.get("moneda")).isEqualTo("ARS");
            assertThat(((Number) h.get("usuario_id")).longValue()).isEqualTo(usuarioDueno);
            assertThat((BigDecimal) h.get("costo_anterior")).isEqualByComparingTo("1000.00");
            assertThat((BigDecimal) h.get("costo_nuevo")).isEqualByComparingTo("1005.00");
        });
        assertThat(historialDe(dolares)).singleElement().satisfies(h -> {
            assertThat(h.get("origen")).isEqualTo("MASIVO");
            assertThat(h.get("moneda")).isEqualTo("USD");
            assertThat((BigDecimal) h.get("costo_nuevo")).isEqualByComparingTo("15.00");
        });
        assertThat(historialDeLaFerreteria()).isEqualTo(2);
    }

    // ---- R-207: mezcla de monedas ----

    @Test
    @DisplayName("3 en pesos y 2 en dolares con +100: enPesos 3, enDolares 2, advertencia y aplicar da 201")
    void mezclaAdvierteYNoBloquea() throws Exception {
        for (int i = 1; i <= 3; i++) {
            enPesos("P" + i, i1, "100.00", "160.00");
        }
        Long d1 = enDolares("D1", i1, "10.00", "15730.00");
        enDolares("D2", i1, "20.00", "31460.00");
        ObjectNode pedido = pedido("COSTO_MONTO", "100", i1);

        JsonNode previa = vistaPrevia(pedido);

        assertThat(previa.get("enPesos").asInt()).isEqualTo(3);
        assertThat(previa.get("enDolares").asInt()).isEqualTo(2);
        assertThat(previa.get("advertenciaMezcla").asText()).isEqualTo("La selección mezcla artículos en pesos y en "
                + "dólares. El monto de 100 se suma tal cual a cada costo: $\u00a0100 en los de pesos y US$\u00a0100 en los de dólares.");
        aplicar(pedido, previa);
        assertThat(costoDe(d1)).isEqualByComparingTo("110.00");
    }

    @Test
    @DisplayName("4 en dolares sin advertencia; y COSTO_PORCENTAJE con mezcla tampoco advierte pero cuenta las monedas")
    void sinAdvertenciaCuandoNoCorresponde() throws Exception {
        for (int i = 1; i <= 4; i++) {
            enDolares("D" + i, i1, "10.00", "15730.00");
        }
        JsonNode soloDolares = vistaPrevia(pedido("COSTO_MONTO", "5", i1));
        assertThat(soloDolares.get("enDolares").asInt()).isEqualTo(4);
        assertThat(soloDolares.get("enPesos").asInt()).isZero();
        assertThat(soloDolares.get("advertenciaMezcla").isNull()).isTrue();

        enPesos("P1", i1, "100.00", "160.00");
        JsonNode porcentaje = vistaPrevia(pedido("COSTO_PORCENTAJE", "10", i1));
        assertThat(porcentaje.get("enPesos").asInt()).isEqualTo(1);
        assertThat(porcentaje.get("enDolares").asInt()).isEqualTo(4);
        assertThat(porcentaje.get("advertenciaMezcla").isNull()).isTrue();
    }

    @Test
    @DisplayName("un alcance con dolares y sin ninguna cotizacion guardada da 409; con solo pesos se aplica")
    void sinCotizacionDa409ConDolares() throws Exception {
        Long usd = enDolares("D1", i1, "10.00", "15730.00");
        borrarConfiguracion(f);
        borrarCotizaciones("t226-");
        ObjectNode pedido = pedido("COSTO_MONTO", "5", i1);

        postJson(VISTA_PREVIA, dueno, pedido.toString()).andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is("Todavía no hay cotización del dólar. Probá en unos minutos")));

        assertThat(costoDe(usd)).isEqualByComparingTo("10.00");
        assertThat(historialDeLaFerreteria()).isZero();
    }

    // ---- atomicidad, concurrencia y tope ----

    @Test
    @DisplayName("si falla el historial del tercer articulo no cambia ninguno: una sola transaccion")
    void unaSolaTransaccion() throws Exception {
        List<Long> ids = tresEnPesos();
        Long cuarto = enPesos("A5", i1, "300.00", "480.00");
        ObjectNode pedido = pedido("COSTO_MONTO", "10", i1);
        JsonNode previa = vistaPrevia(pedido);
        Map<Long, Map<String, Object>> antes = new LinkedHashMap<>();
        for (Long id : ids) {
            antes.put(id, filaDe(id));
        }
        doAnswer(invocacion -> {
            int n = 0;
            for (HistorialPrecio entrada : (Iterable<HistorialPrecio>) invocacion.getArgument(0)) {
                if (++n == 3) {
                    throw new IllegalStateException("falla simulada");
                }
                historialPrecioRepository.save(entrada);
            }
            return null;
        }).when(historialPrecioRepository).saveAll(any());

        postJson(APLICAR, dueno, pedido.deepCopy().put("huella", previa.get("huella").asText()).toString())
                .andExpect(status().isInternalServerError());

        for (Long id : ids) {
            assertThat(filaDe(id)).isEqualTo(antes.get(id));
        }
        assertThat(costoDe(cuarto)).isEqualByComparingTo("300.00");
        assertThat(historialDeLaFerreteria()).isZero();
    }

    @Test
    @DisplayName("si otro usuario edita el costo de un articulo del alcance despues de la vista previa, aplicar da 409")
    void edicionConcurrenteDa409() throws Exception {
        Long a = enPesos("A", i1, "100.00", "160.00");
        enPesos("B", i1, "200.00", "320.00");
        ObjectNode pedido = pedido("COSTO_MONTO", "10", i1);
        JsonNode previa = vistaPrevia(pedido);

        ObjectNode cuerpo = (ObjectNode) getJson("/api/articulos/" + a, dueno);
        cuerpo.put("costo", new BigDecimal("150.00"));
        putJson("/api/articulos/" + a, dueno, cuerpo.toString()).andExpect(status().isOk());
        long historialAntes = historialDeLaFerreteria();

        postJson(APLICAR, dueno, pedido.deepCopy().put("huella", previa.get("huella").asText()).toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message", is(DERIVA)));

        assertThat(costoDe(a)).isEqualByComparingTo("150.00");
        assertThat(historialDeLaFerreteria()).isEqualTo(historialAntes);
    }

    @Test
    @DisplayName("20.001 articulos de la importacion con COSTO_MONTO: 400 del tope en los dos endpoints, sin escribir")
    void topeDe20000() throws Exception {
        List<Object[]> filas = new ArrayList<>(20_001);
        for (int i = 0; i < 20_001; i++) {
            filas.add(new Object[] {f, String.format("T-%06d", i), "Articulo " + i, i1});
        }
        jdbc.batchUpdate("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, alicuota_iva, "
                + "precio_final, unidad, activo, ultima_importacion_id) values (?, ?, ?, 100.00, 'IVA_21', 160.00, "
                + "'UNIDAD', true, ?)", filas);
        ObjectNode pedido = pedido("COSTO_MONTO", "10", i1);
        String mensaje = "El filtro abarca 20.001 artículos y el máximo es 20.000. Afiná el filtro.";

        postJson(VISTA_PREVIA, dueno, pedido.toString()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is(mensaje)));
        postJson(APLICAR, dueno, pedido.deepCopy().put("huella", "abc").toString()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", is(mensaje)));

        assertThat(historialDeLaFerreteria()).isZero();
    }

    @Test
    @DisplayName("el monto 0 y el de 3 decimales dan 400 en español, con la importacion propia")
    void montosInvalidosDan400() throws Exception {
        enPesos("A", i1, "100.00", "160.00");

        postJson(VISTA_PREVIA, dueno, pedido("COSTO_MONTO", "0", i1).toString()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.montoDistintoDeCero", is("El monto no puede ser cero.")));
        postJson(VISTA_PREVIA, dueno, pedido("COSTO_MONTO", "10.555", i1).toString()).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.valor", is("El valor puede tener como máximo 2 decimales.")));
    }
}
