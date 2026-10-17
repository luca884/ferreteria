package com.ferreteria.cotizacion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.cotizacion.cliente.BluelyticsFuente;
import com.ferreteria.cotizacion.cliente.DolarApiFuente;
import com.ferreteria.cotizacion.model.LadoCotizacion;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;

/**
 * GET /api/cotizacion: lo puede pedir cualquier usuario autenticado y devuelve la cotizacion global mas la
 * configuracion de la ferreteria del token. Las dos fuentes son mocks: si el GET las llamara, el test lo ve.
 * Ferreterias propias (CUIT 30-7410000x) y casas t21-*.
 */
class CotizacionIntegrationTest extends CotizacionTestBase {

    @MockBean
    DolarApiFuente dolarApi;

    @MockBean
    BluelyticsFuente bluelytics;

    private Long a;
    private Long b;
    private String duenoA;
    private String vendedorA;
    private String duenoB;
    private String adminSinFerreteria;

    @BeforeEach
    void sembrar() {
        a = ferreteria("30-74100001-1", "Cotizacion A");
        b = ferreteria("30-74100002-2", "Cotizacion B");
        duenoA = usuario(a, "dueno-t21a@test.local", "DUENO");
        vendedorA = usuario(a, "vendedor-t21a@test.local", "VENDEDOR");
        duenoB = usuario(b, "dueno-t21b@test.local", "DUENO");
        adminSinFerreteria = "admin@demo.local";

        borrarCotizaciones("t21-");
        Instant reciente = Instant.now().minusSeconds(600);
        cotizacion("USD", "t21-oficial", "950", "1000", reciente);
        cotizacion("USD", "t21-blue", "1180", "1200", reciente);
        configuracion(a, c -> {
            c.setCasaPrecios("t21-oficial");
            c.setVisibles(List.of("USD:t21-oficial"));
            c.fijarVigente(new BigDecimal("1000.0000"), "t21-oficial", LadoCotizacion.VENTA, reciente);
        });
        configuracion(b, c -> {
            c.setCasaPrecios("t21-oficial");
            c.setLadoPrecios(LadoCotizacion.COMPRA);
            c.setVisibles(List.of("USD:t21-oficial", "USD:t21-blue"));
            c.fijarVigente(new BigDecimal("950.0000"), "t21-oficial", LadoCotizacion.COMPRA, reciente);
        });
    }

    @AfterEach
    void limpiar() {
        borrarCotizaciones("t21-");
        borrarConfiguracion(a, b);
    }

    private static Set<String> campos(JsonNode nodo) {
        Set<String> campos = new HashSet<>();
        nodo.fieldNames().forEachRemaining(campos::add);
        return campos;
    }

    // ---- roles y autenticacion ----

    @Test
    @DisplayName("el Vendedor autenticado recibe 200")
    void elVendedorLaLee() throws Exception {
        getResult("/api/cotizacion", login(vendedorA)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("el Dueño recibe 200")
    void elDuenoLaLee() throws Exception {
        getResult("/api/cotizacion", login(duenoA)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("sin token responde 401")
    void sinTokenDa401() throws Exception {
        mvc.perform(get("/api/cotizacion")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("un usuario sin ferreteria (super admin) recibe 403, como en las otras configuraciones")
    void sinFerreteriaDa403() throws Exception {
        getResult("/api/cotizacion", login(adminSinFerreteria)).andExpect(status().isForbidden());
    }

    // ---- forma ----

    @Test
    @DisplayName("la respuesta trae la forma del diseño y la fecha y hora de cada valor")
    void formaDeLaRespuesta() throws Exception {
        JsonNode r = getJson("/api/cotizacion", login(vendedorA));

        assertThat(campos(r)).containsExactlyInAnyOrder("activa", "desactualizada", "ultimaRespuesta", "visibles", "precios");
        assertThat(r.get("activa").asBoolean()).isTrue();
        assertThat(r.get("desactualizada").asBoolean()).isFalse();
        assertThat(r.get("ultimaRespuesta").isTextual()).isTrue();
        assertThat(r.get("visibles")).hasSize(1);
        JsonNode oficial = r.get("visibles").get(0);
        assertThat(campos(oficial)).containsExactlyInAnyOrder("codigo", "nombre", "compra", "venta", "fuente",
                "actualizadaEn", "obtenidaEn", "desactualizada", "esDePrecios");
        assertThat(oficial.get("codigo").asText()).isEqualTo("USD:t21-oficial");
        assertThat(oficial.get("compra").decimalValue()).isEqualByComparingTo("950");
        assertThat(oficial.get("venta").decimalValue()).isEqualByComparingTo("1000");
        assertThat(oficial.get("fuente").asText()).isEqualTo("DOLARAPI");
        assertThat(Instant.parse(oficial.get("actualizadaEn").asText())).isBefore(Instant.now());
        assertThat(Instant.parse(oficial.get("obtenidaEn").asText())).isBefore(Instant.now());
        assertThat(oficial.get("desactualizada").asBoolean()).isFalse();
        assertThat(oficial.get("esDePrecios").asBoolean()).isTrue();
        JsonNode precios = r.get("precios");
        assertThat(campos(precios)).containsExactlyInAnyOrder("codigo", "lado", "valor", "desde", "aConfirmar");
        assertThat(precios.get("aConfirmar").isNull()).isTrue();
        assertThat(precios.get("codigo").asText()).isEqualTo("USD:t21-oficial");
        assertThat(precios.get("lado").asText()).isEqualTo("VENTA");
        assertThat(precios.get("valor").decimalValue()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("un valor viejo (mas de 1 hora sin respuesta) sale como desactualizado con el mismo valor")
    void desactualizada() throws Exception {
        borrarCotizaciones("t21-");
        cotizacion("USD", "t21-oficial", "950", "1000", Instant.now().minusSeconds(2 * 3600));

        JsonNode r = getJson("/api/cotizacion", login(duenoA));

        assertThat(r.get("desactualizada").asBoolean()).isTrue();
        assertThat(r.get("visibles").get(0).get("desactualizada").asBoolean()).isTrue();
        assertThat(r.get("visibles").get(0).get("venta").decimalValue()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("el GET solo lee la base: no llama a ninguna fuente externa")
    void noLlamaAFuentesExternas() throws Exception {
        getJson("/api/cotizacion", login(vendedorA));
        getJson("/api/cotizacion", login(duenoA));

        verifyNoInteractions(dolarApi, bluelytics);
    }

    // ---- aislamiento (R-022) ----

    @Test
    @DisplayName("A ve solo el oficial y B ve el oficial y el blue; cada una con su dolar de precios")
    void cadaFerreteriaVeLoSuyo() throws Exception {
        JsonNode deA = getJson("/api/cotizacion", login(duenoA));
        JsonNode deB = getJson("/api/cotizacion", login(duenoB));

        assertThat(deA.get("visibles")).extracting(n -> n.get("codigo").asText()).containsExactly("USD:t21-oficial");
        assertThat(deB.get("visibles")).extracting(n -> n.get("codigo").asText())
                .containsExactly("USD:t21-oficial", "USD:t21-blue");
        assertThat(deA.get("precios").get("lado").asText()).isEqualTo("VENTA");
        assertThat(deA.get("precios").get("valor").decimalValue()).isEqualByComparingTo("1000");
        assertThat(deB.get("precios").get("lado").asText()).isEqualTo("COMPRA");
        assertThat(deB.get("precios").get("valor").decimalValue()).isEqualByComparingTo("950");
    }

    @Test
    @DisplayName("la respuesta no trae ningun dato de ninguna ferreteria: ni ids ni los vigentes de la otra")
    void sinDatosDeFerreterias() throws Exception {
        String cuerpo = getJson("/api/cotizacion", login(duenoA)).toString();

        assertThat(cuerpo).doesNotContain("ferreteria").doesNotContain("Ferreteria");
        assertThat(cuerpo).doesNotContain("\"id\"").doesNotContain("t21-blue");
    }

    @Test
    @DisplayName("un ferreteriaId por parametro o por header se ignora: la ferreteria sale solo del token")
    void ferreteriaIdSeIgnora() throws Exception {
        String token = login(duenoA);

        JsonNode porParametro = objectMapper.readTree(mvc.perform(get("/api/cotizacion?ferreteriaId=" + b)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        JsonNode porHeader = objectMapper.readTree(mvc.perform(get("/api/cotizacion")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Ferreteria-Id", b.toString()).header("X-Tenant-Id", b.toString()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        assertThat(porParametro.get("visibles")).hasSize(1);
        assertThat(porParametro.get("precios").get("valor").decimalValue()).isEqualByComparingTo("1000");
        assertThat(porHeader.get("visibles")).hasSize(1);
        assertThat(porHeader.get("precios").get("lado").asText()).isEqualTo("VENTA");
    }

    @Test
    @DisplayName("sin configuracion propia, la ferreteria ve los valores por defecto y consultar no crea ninguna fila")
    void sinConfiguracionPropia() throws Exception {
        borrarConfiguracion(a);
        borrarCotizaciones("t21-");
        cotizacion("USD", "oficial", "950", "1000", Instant.now().minusSeconds(60));
        try {
            JsonNode r = getJson("/api/cotizacion", login(duenoA));

            assertThat(r.get("activa").asBoolean()).isTrue();
            assertThat(r.get("visibles")).extracting(n -> n.get("codigo").asText()).containsExactly("USD:oficial");
            assertThat(r.get("precios").get("lado").asText()).isEqualTo("VENTA");
            assertThat(r.get("precios").get("valor").decimalValue()).isEqualByComparingTo("1000");
            assertThat(jdbc.queryForObject("select count(*) from configuracion_cotizacion where ferreteria_id = ?",
                    Integer.class, a)).isZero();
        } finally {
            jdbc.update("delete from cotizacion where moneda = 'USD' and casa = 'oficial'");
        }
    }
}
