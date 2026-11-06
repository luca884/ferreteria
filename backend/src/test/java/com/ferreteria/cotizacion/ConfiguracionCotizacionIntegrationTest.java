package com.ferreteria.cotizacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.cotizacion.model.LadoCotizacion;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

/**
 * GET y PUT /api/configuracion-cotizacion: solo el Dueño, y cada ferreteria ve y cambia solo la suya (la ferreteria sale
 * del JWT). Ferreterias propias (CUIT 30-7410001x).
 */
class ConfiguracionCotizacionIntegrationTest extends CotizacionTestBase {

    private static final String URL = "/api/configuracion-cotizacion";

    private Long a;
    private Long b;
    private String duenoA;
    private String vendedorA;
    private String duenoB;
    private Long articuloUsdA;

    @BeforeEach
    void sembrar() {
        a = ferreteria("30-74100011-1", "Configuracion A");
        b = ferreteria("30-74100012-2", "Configuracion B");
        duenoA = usuario(a, "dueno-t23a@test.local", "DUENO");
        vendedorA = usuario(a, "vendedor-t23a@test.local", "VENDEDOR");
        duenoB = usuario(b, "dueno-t23b@test.local", "DUENO");
        borrarConfiguracion(a, b);
    }

    @AfterEach
    void limpiar() {
        borrarConfiguracion(a, b);
        if (articuloUsdA != null) {
            jdbc.update("delete from historial_precio where articulo_id = ?", articuloUsdA);
            jdbc.update("delete from articulo where id = ?", articuloUsdA);
            articuloUsdA = null;
        }
    }

    private static String json(boolean activa, String dolar, String lado, String visibles, String hora) {
        return "{\"activa\":" + activa + ",\"dolarPrecios\":" + (dolar == null ? "null" : "\"" + dolar + "\"")
                + ",\"ladoPrecios\":" + (lado == null ? "null" : "\"" + lado + "\"") + ",\"visibles\":" + visibles
                + ",\"horaRecalculo\":" + (hora == null ? "null" : "\"" + hora + "\"") + "}";
    }

    private static String valido() {
        return json(true, "USD:oficial", "VENTA", "[\"USD:oficial\"]", "08:00");
    }

    private JsonNode cuerpo(ResultActions resultado) throws Exception {
        return objectMapper.readTree(resultado.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String ladoGuardado(Long ferreteriaId) {
        return jdbc.queryForObject("select lado_precios from configuracion_cotizacion where ferreteria_id = ?",
                String.class, ferreteriaId);
    }

    // ---- GET ----

    @Test
    @DisplayName("sin configuracion propia, el Dueño recibe activada, oficial venta y 08:00 (R-011)")
    void valoresPorDefecto() throws Exception {
        JsonNode r = getJson(URL, login(duenoA));

        assertThat(r.get("activa").asBoolean()).isTrue();
        assertThat(r.get("dolarPrecios").asText()).isEqualTo("USD:oficial");
        assertThat(r.get("ladoPrecios").asText()).isEqualTo("VENTA");
        assertThat(r.get("visibles")).extracting(JsonNode::asText).containsExactly("USD:oficial");
        assertThat(r.get("horaRecalculo").asText()).isEqualTo("08:00");
        assertThat(r.get("vigenteDesde").isNull()).isTrue();
    }

    @Test
    @DisplayName("sin dólar fijado, vigente es el último publicado de la casa y el lado elegidos, el mismo con el que calcula "
            + "una importación, y sin fecha (QA 05/11)")
    void sinFijarVigenteEsElUltimoPublicado() throws Exception {
        cotizacion("USD", "oficial", "1000.00", "1050.00", Instant.parse("2026-11-05T12:00:00Z"));

        JsonNode r = getJson(URL, login(duenoA));

        assertThat(r.get("vigente").decimalValue()).isEqualByComparingTo("1050");
        assertThat(r.get("vigenteDesde").isNull()).isTrue();
    }

    @Test
    @DisplayName("sin dólar fijado ni publicado, vigente es null")
    void sinFijarNiPublicadoVigenteEsNull() throws Exception {
        jdbc.update("delete from cotizacion where moneda = 'USD' and casa = 'oficial'");

        JsonNode r = getJson(URL, login(duenoA));

        assertThat(r.get("vigente").isNull()).isTrue();
        assertThat(r.get("vigenteDesde").isNull()).isTrue();
    }

    @Test
    @DisplayName("con dólar fijado, vigente es el fijado con su fecha aunque haya uno publicado más nuevo")
    void conDolarFijadoVigenteEsElFijado() throws Exception {
        cotizacion("USD", "oficial", "1080.00", "1100.00", Instant.parse("2026-11-05T12:00:00Z"));
        configuracion(a, c -> c.fijarVigente(new BigDecimal("1000.0000"), "oficial", LadoCotizacion.VENTA,
                Instant.parse("2026-10-16T11:00:00Z")));

        JsonNode r = getJson(URL, login(duenoA));

        assertThat(r.get("vigente").decimalValue()).isEqualByComparingTo("1000");
        assertThat(Instant.parse(r.get("vigenteDesde").asText())).isEqualTo(Instant.parse("2026-10-16T11:00:00Z"));
    }

    @Test
    @DisplayName("el Dueño guarda oficial compra y al releer ve oficial compra, con el resto de lo que mando")
    void guardaYRelee() throws Exception {
        String token = login(duenoA);

        JsonNode guardada = cuerpo(putJson(URL, token,
                json(false, "USD:bolsa", "COMPRA", "[\"USD:oficial\",\"USD:blue\",\"EUR:oficial\"]", "09:30"))
                .andExpect(status().isOk()));
        JsonNode releida = getJson(URL, token);

        for (JsonNode r : List.of(guardada, releida)) {
            assertThat(r.get("activa").asBoolean()).isFalse();
            assertThat(r.get("dolarPrecios").asText()).isEqualTo("USD:bolsa");
            assertThat(r.get("ladoPrecios").asText()).isEqualTo("COMPRA");
            assertThat(r.get("visibles")).extracting(JsonNode::asText)
                    .containsExactly("USD:oficial", "USD:blue", "EUR:oficial");
            assertThat(r.get("horaRecalculo").asText()).isEqualTo("09:30");
        }
    }

    @Test
    @DisplayName("visibles con USD:oficial y EUR:oficial se aceptan (R-027)")
    void visiblesConEuro() throws Exception {
        putJson(URL, login(duenoA), json(true, "USD:oficial", "VENTA", "[\"USD:oficial\",\"EUR:oficial\"]", "08:00"))
                .andExpect(status().isOk());
    }

    // ---- umbral del aviso del dolar (mejoras-ferreteros-bloque-1, S1) ----

    private static String conUmbral(String umbral) {
        return valido().replace("}", ",\"umbralDesvio\":" + umbral + "}");
    }

    private BigDecimal umbralGuardado(Long ferreteriaId) {
        return jdbc.queryForObject("select umbral_desvio_porcentaje from configuracion_cotizacion where ferreteria_id = ?",
                BigDecimal.class, ferreteriaId);
    }

    @Test
    @DisplayName("sin configuracion propia el aviso del dolar es al 5 %")
    void umbralPorDefecto() throws Exception {
        assertThat(getJson(URL, login(duenoA)).get("umbralDesvio").decimalValue()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("el Dueño guarda el aviso al 8 %, lo relee y B sigue en 5 %")
    void guardaElUmbral() throws Exception {
        String token = login(duenoA);

        JsonNode guardada = cuerpo(putJson(URL, token, conUmbral("8")).andExpect(status().isOk()));

        assertThat(guardada.get("umbralDesvio").decimalValue()).isEqualByComparingTo("8");
        assertThat(getJson(URL, token).get("umbralDesvio").decimalValue()).isEqualByComparingTo("8");
        assertThat(umbralGuardado(a)).isEqualByComparingTo("8");
        assertThat(getJson(URL, login(duenoB)).get("umbralDesvio").decimalValue()).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("un PUT sin umbralDesvio conserva el que estaba")
    void sinUmbralConservaElGuardado() throws Exception {
        String token = login(duenoA);
        putJson(URL, token, conUmbral("12.5")).andExpect(status().isOk());

        putJson(URL, token, valido()).andExpect(status().isOk());

        assertThat(umbralGuardado(a)).isEqualByComparingTo("12.5");
    }

    @Test
    @DisplayName("un aviso fuera de 1 % a 50 % o con mas de 2 decimales: 400 en español y no cambia nada")
    void umbralInvalido() throws Exception {
        String mensaje = "El aviso del dólar tiene que estar entre 1 % y 50 %, con hasta 2 decimales.";
        for (String umbral : List.of("0.5", "50.01", "5.001", "-5")) {
            rechaza(conUmbral(umbral), "umbralDesvio", mensaje);
            assertThat(umbralGuardado(a)).isEqualByComparingTo("5");
        }
    }

    @Test
    @DisplayName("el Vendedor no puede cambiar el aviso del dolar")
    void elVendedorNoCambiaElUmbral() throws Exception {
        putJson(URL, login(vendedorA), conUmbral("30")).andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("select count(*) from configuracion_cotizacion where ferreteria_id = ?",
                Integer.class, a)).isZero();
    }

    // ---- roles ----

    @Test
    @DisplayName("el Vendedor recibe 403 en GET y en PUT, y el PUT no cambia nada")
    void elVendedorRecibe403() throws Exception {
        String token = login(vendedorA);

        getResult(URL, token).andExpect(status().isForbidden());
        putJson(URL, token, json(false, "USD:blue", "COMPRA", "[\"USD:blue\"]", "10:00")).andExpect(status().isForbidden());
        // Aun con datos invalidos: el rol se exige antes que la validacion.
        putJson(URL, token, "{}").andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("select count(*) from configuracion_cotizacion where ferreteria_id = ?",
                Integer.class, a)).isZero();
    }

    @Test
    @DisplayName("sin token responde 401 en GET y en PUT")
    void sinTokenDa401() throws Exception {
        mvc.perform(get(URL)).andExpect(status().isUnauthorized());
        mvc.perform(put(URL).contentType(MediaType.APPLICATION_JSON).content(valido()))
                .andExpect(status().isUnauthorized());
    }

    // ---- validacion ----

    private void rechaza(String json, String campo, String mensaje) throws Exception {
        String token = login(duenoA);
        putJson(URL, token, valido()).andExpect(status().isOk());

        JsonNode error = cuerpo(putJson(URL, token, json).andExpect(status().isBadRequest()));

        assertThat(error.get("errors").get(campo).asText()).isEqualTo(mensaje);
        JsonNode igual = getJson(URL, token);
        assertThat(igual.get("dolarPrecios").asText()).isEqualTo("USD:oficial");
        assertThat(igual.get("ladoPrecios").asText()).isEqualTo("VENTA");
        assertThat(igual.get("horaRecalculo").asText()).isEqualTo("08:00");
    }

    @Test
    @DisplayName("dos tipos de dolar a la vez: 400 en español y no cambia nada")
    void dosTiposDeDolar() throws Exception {
        rechaza(json(true, "USD:oficial,USD:blue", "COMPRA", "[\"USD:oficial\"]", "09:00"), "dolarPrecios",
                "Elegí un solo dólar para los precios.");
    }

    @Test
    @DisplayName("hora 25:00: 400 en español y no cambia nada")
    void horaInvalida() throws Exception {
        rechaza(json(true, "USD:oficial", "COMPRA", "[\"USD:oficial\"]", "25:00"), "horaRecalculo",
                "La hora tiene que tener el formato HH:mm, por ejemplo 08:00.");
    }

    @Test
    @DisplayName("euro oficial o real oficial como dolar de precios: 400 y no cambia nada")
    void euroYRealComoDolarDePrecios() throws Exception {
        String mensaje = "El dólar de los precios tiene que ser un tipo de dólar. El euro y el real son solo para mostrar.";
        rechaza(json(true, "EUR:oficial", "COMPRA", "[\"USD:oficial\"]", "09:00"), "dolarPrecios", mensaje);
        rechaza(json(true, "BRL:oficial", "COMPRA", "[\"USD:oficial\"]", "09:00"), "dolarPrecios", mensaje);
    }

    @Test
    @DisplayName("visibles vacio o con un elemento desconocido: 400 y no cambia nada")
    void visiblesInvalidos() throws Exception {
        rechaza(json(true, "USD:oficial", "COMPRA", "[]", "09:00"), "visibles",
                "Elegí al menos una cotización para mostrar.");
        rechaza(json(true, "USD:oficial", "COMPRA", "[\"USD:oficial\",\"USD:inventado\"]", "09:00"), "visibles",
                "La cotización «USD:inventado» no existe.");
    }

    @Test
    @DisplayName("un lado que no es COMPRA ni VENTA da 400 en español, no 500")
    void ladoInvalido() throws Exception {
        String token = login(duenoA);

        JsonNode error = cuerpo(putJson(URL, token, json(true, "USD:oficial", "MEDIO", "[\"USD:oficial\"]", "08:00"))
                .andExpect(status().isBadRequest()));

        assertThat(error.get("message").asText()).contains("ladoPrecios");
    }

    // ---- R-028: guardar no recalcula ----

    @Test
    @DisplayName("R-028: cambiar a oficial compra no toca el precio de un articulo USD ni deja historial")
    void guardarNoCambiaPrecios() throws Exception {
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, moneda, precio_final) "
                + "values (?, 't23-usd', 'Articulo en dolares', 10.00, 'USD', 15730.00)", a);
        articuloUsdA = jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = 't23-usd'",
                Long.class, a);
        configuracion(a, c -> c.fijarVigente(new BigDecimal("1000.0000"), "oficial", LadoCotizacion.VENTA,
                Instant.parse("2026-10-16T11:00:00Z")));

        putJson(URL, login(duenoA), json(true, "USD:oficial", "COMPRA", "[\"USD:oficial\"]", "08:00"))
                .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("select precio_final from articulo where id = ?", BigDecimal.class, articuloUsdA))
                .isEqualByComparingTo("15730");
        assertThat(jdbc.queryForObject("select count(*) from historial_precio where articulo_id = ?", Integer.class,
                articuloUsdA)).isZero();
        assertThat(configuracionDe(a).getVigente()).isEqualByComparingTo("1000");
        assertThat(configuracionDe(a).getLadoPrecios()).isEqualTo(LadoCotizacion.COMPRA);
    }

    // ---- aislamiento (R-022) ----

    @Test
    @DisplayName("A guarda oficial compra y B oficial venta: cada Dueño ve la suya y el PUT de A no toca la fila de B")
    void cadaDuenoVeLaSuya() throws Exception {
        String tokenA = login(duenoA);
        String tokenB = login(duenoB);
        putJson(URL, tokenB, json(true, "USD:oficial", "VENTA", "[\"USD:oficial\",\"USD:blue\"]", "10:15"))
                .andExpect(status().isOk());
        String antesDeB = jdbc.queryForObject(
                "select concat(lado_precios, '|', visibles, '|', hora_recalculo) from configuracion_cotizacion "
                        + "where ferreteria_id = ?", String.class, b);

        putJson(URL, tokenA, json(true, "USD:oficial", "COMPRA", "[\"USD:oficial\"]", "08:00"))
                .andExpect(status().isOk());

        assertThat(getJson(URL, tokenA).get("ladoPrecios").asText()).isEqualTo("COMPRA");
        JsonNode deB = getJson(URL, tokenB);
        assertThat(deB.get("ladoPrecios").asText()).isEqualTo("VENTA");
        assertThat(deB.get("horaRecalculo").asText()).isEqualTo("10:15");
        assertThat(deB.get("visibles")).hasSize(2);
        assertThat(jdbc.queryForObject(
                "select concat(lado_precios, '|', visibles, '|', hora_recalculo) from configuracion_cotizacion "
                        + "where ferreteria_id = ?", String.class, b)).isEqualTo(antesDeB);
        assertThat(ladoGuardado(a)).isEqualTo("COMPRA");
    }

    @Test
    @DisplayName("un ferreteriaId por parametro, por body o por header se ignora: la ferreteria sale solo del JWT")
    void ferreteriaIdSeIgnora() throws Exception {
        String tokenA = login(duenoA);
        String tokenB = login(duenoB);
        putJson(URL, tokenB, valido()).andExpect(status().isOk());

        String conIntrusos = "{\"ferreteriaId\":" + b + ",\"activa\":true,\"dolarPrecios\":\"USD:oficial\","
                + "\"ladoPrecios\":\"COMPRA\",\"visibles\":[\"USD:oficial\"],\"horaRecalculo\":\"07:00\"}";
        mvc.perform(put(URL + "?ferreteriaId=" + b).header("Authorization", "Bearer " + tokenA)
                        .header("X-Ferreteria-Id", b.toString())
                        .contentType(MediaType.APPLICATION_JSON).content(conIntrusos))
                .andExpect(status().isOk());

        assertThat(ladoGuardado(a)).isEqualTo("COMPRA");
        assertThat(ladoGuardado(b)).isEqualTo("VENTA");
        JsonNode lecturaDeA = objectMapper.readTree(mvc.perform(get(URL + "?ferreteriaId=" + b)
                        .header("Authorization", "Bearer " + tokenA).header("X-Ferreteria-Id", b.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(lecturaDeA.get("ladoPrecios").asText()).isEqualTo("COMPRA");
        assertThat(getJson(URL, tokenB).get("horaRecalculo").asText()).isEqualTo("08:00");
    }
}
