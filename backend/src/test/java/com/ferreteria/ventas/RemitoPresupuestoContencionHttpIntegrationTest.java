package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.ventas.service.RegistroPresupuesto;
import com.ferreteria.ventas.service.RegistroRemito;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.AopTestUtils;

/**
 * R-001, R-002, R-003 (RM-004, PP-003): remito y presupuesto BAJO CONTENCIÓN agotada por HTTP real (MockMvc contra MySQL real).
 * Cuando los tres intentos de la fachada fallan por deadlock, la API responde 503 «reintentá» con {@code Retry-After}, nunca
 * 500; no deja rastros (ni documento, ni número consumido, ni stock movido) y reenviar con la MISMA clave sin bloqueo entra
 * (201, número 1). Un solo contexto para los dos: los dos colaboradores van espiados juntos.
 */
class RemitoPresupuestoContencionHttpIntegrationTest extends VentasTestBase {

    @SpyBean
    RegistroRemito remitoEspiado;

    @SpyBean
    RegistroPresupuesto presupuestoEspiado;

    @AfterEach
    void soltarLosEspias() {
        Mockito.reset((RegistroRemito) AopTestUtils.getUltimateTargetObject(remitoEspiado));
        Mockito.reset((RegistroPresupuesto) AopTestUtils.getUltimateTargetObject(presupuestoEspiado));
    }

    private String cuerpoDe(String clave, long articulo) throws Exception {
        Map<String, Object> renglon = new LinkedHashMap<>();
        renglon.put("articuloId", articulo);
        renglon.put("cantidad", new BigDecimal("2"));
        renglon.put("precioUnitario", new BigDecimal("100.00"));
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("clave", clave);
        cuerpo.put("renglones", List.of(renglon));
        cuerpo.put("totalMostrado", new BigDecimal("200.00"));
        return objectMapper.writeValueAsString(cuerpo);
    }

    private MockHttpServletResponse enviar(String url, String token, String cuerpo) throws Exception {
        return postJson(url, token, cuerpo).andReturn().getResponse();
    }

    private JsonNode cuerpoDe(MockHttpServletResponse respuesta) throws Exception {
        return objectMapper.readTree(respuesta.getContentAsString(StandardCharsets.UTF_8));
    }

    private long contar(String tabla, Tienda t) {
        return jdbc.queryForObject("select count(*) from " + tabla + " where ferreteria_id = ?", Long.class, t.id());
    }

    @Test
    @DisplayName("R-001, R-003: POST /api/remitos con los 3 intentos en deadlock responde 503 «reintentá» con Retry-After, sin remito, sin número ni movimiento de stock; reenviar con la MISMA clave entra (201, N.º 1)")
    void remitoConLocksAgotadosEs503() throws Exception {
        Tienda t = tiendaSinCaja();
        long articulo = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        String clave = clave();
        doThrow(new DeadlockLoserDataAccessException("deadlock simulado", null)).when(
                (RegistroRemito) AopTestUtils.getUltimateTargetObject(remitoEspiado)).emitir(any(), any());

        MockHttpServletResponse agotada = enviar("/api/remitos", token, cuerpoDe(clave, articulo));

        assertThat(agotada.getStatus()).as("cuerpo %s", agotada.getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(503);
        assertThat(cuerpoDe(agotada).get("message").asText()).isEqualTo("El sistema está ocupado, reintentá.");
        assertThat(agotada.getHeader("Retry-After")).isEqualTo("2");
        verify((RegistroRemito) AopTestUtils.getUltimateTargetObject(remitoEspiado), times(3)).emitir(any(), any());
        assertThat(contar("remito", t)).as("remitos").isZero();
        assertThat(contar("numerador_remito", t)).as("numerador consumido").isZero();
        assertThat(movimientos(t)).as("movimientos de stock").isZero();
        assertThat(stock(articulo)).isEqualByComparingTo("10");

        Mockito.reset((RegistroRemito) AopTestUtils.getUltimateTargetObject(remitoEspiado));
        MockHttpServletResponse reintento = enviar("/api/remitos", token, cuerpoDe(clave, articulo));

        assertThat(reintento.getStatus()).as("cuerpo %s", reintento.getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(201);
        assertThat(cuerpoDe(reintento).get("numero").asLong()).isEqualTo(1);
        assertThat(contar("remito", t)).isEqualTo(1);
        assertThat(stock(articulo)).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("R-002, R-003: POST /api/presupuestos con los 3 intentos en deadlock responde 503 «reintentá» con Retry-After, sin presupuesto ni número consumido; reenviar con la MISMA clave entra (201, N.º 1)")
    void presupuestoConLocksAgotadosEs503() throws Exception {
        Tienda t = tiendaSinCaja();
        long articulo = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        String clave = clave();
        doThrow(new DeadlockLoserDataAccessException("deadlock simulado", null)).when(
                (RegistroPresupuesto) AopTestUtils.getUltimateTargetObject(presupuestoEspiado)).emitir(any(), any());

        MockHttpServletResponse agotada = enviar("/api/presupuestos", token, cuerpoDe(clave, articulo));

        assertThat(agotada.getStatus()).as("cuerpo %s", agotada.getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(503);
        assertThat(cuerpoDe(agotada).get("message").asText()).isEqualTo("El sistema está ocupado, reintentá.");
        assertThat(agotada.getHeader("Retry-After")).isEqualTo("2");
        verify((RegistroPresupuesto) AopTestUtils.getUltimateTargetObject(presupuestoEspiado), times(3))
                .emitir(any(), any());
        assertThat(contar("presupuesto", t)).as("presupuestos").isZero();
        assertThat(contar("numerador_presupuesto", t)).as("numerador consumido").isZero();
        assertThat(stock(articulo)).isEqualByComparingTo("10");

        Mockito.reset((RegistroPresupuesto) AopTestUtils.getUltimateTargetObject(presupuestoEspiado));
        MockHttpServletResponse reintento = enviar("/api/presupuestos", token, cuerpoDe(clave, articulo));

        assertThat(reintento.getStatus()).as("cuerpo %s", reintento.getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(201);
        assertThat(cuerpoDe(reintento).get("numero").asLong()).isEqualTo(1);
        assertThat(contar("presupuesto", t)).isEqualTo(1);
    }
}
