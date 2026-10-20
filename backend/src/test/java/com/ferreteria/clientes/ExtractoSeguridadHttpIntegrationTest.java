package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * CCT3B1-605 (CC-072, CC-101): la seguridad por ruta de {@code GET /api/clientes/{id}/cuenta-corriente/extracto}, con los métodos
 * y los parámetros que NO se esperan. El comportamiento real, fijado acá:
 * <ul>
 *   <li>Vendedor con GET: 403 aunque el parámetro sea inválido ({@code ?tipo=XYZ}, {@code ?desde=basura}, {@code desde > hasta}):
 *       el matcher de {@code SecurityConfig} corre antes del binding (sin él, daría 400: el binding va antes del
 *       {@code @PreAuthorize}). Es la mutación obligatoria de abajo.
 *   <li>Vendedor con HEAD: 403 (el matcher es solo de GET; el HEAD de Spring MVC es el GET y cae en el {@code @PreAuthorize}).
 *   <li>POST/PUT/PATCH/DELETE: 405 para Dueño Y Vendedor (no hay handler y nada que proteger; CC-101 tolera 403, pero el motor
 *       responde 405 en ambos casos), sin cuerpo de datos y sin efecto.
 *   <li>SUPER_ADMIN: 403. Sin token: 401 con cualquier método.
 * </ul>
 * Mutación (apply-progress): quitar a la vez el matcher del extracto y el {@code @PreAuthorize} del método hace fallar el test del
 * Vendedor con parámetro inválido (da 400) y deja ver el extracto al Vendedor. CUIT 30-8506NNNN-1.
 */
class ExtractoSeguridadHttpIntegrationTest extends CuentaCorrienteTestBase {

    private static final String PERMISO = "{\"status\":403,\"message\":\"No tenés permiso para hacer esto\"}";

    @Override
    protected String prefijoDeCuit() {
        return "30-8506";
    }

    /** Una ferretería con su Dueño y su Vendedor y un cliente con deuda (para que una fuga mostrara algo). */
    private record Tienda(Ferreteria f, String dueno, String vendedor, long cliente) {

        String url() {
            return "/api/clientes/" + cliente + "/cuenta-corriente/extracto";
        }
    }

    private Tienda tienda() throws Exception {
        Ferreteria f = ferreteria();
        Logins login = logins(f);
        long cliente = cliente(f, "1000");
        ventaEnLibro(f, cliente, "250");
        return new Tienda(f, login(login.dueno()), login(login.vendedor()), cliente);
    }

    private MvcResult pedir(HttpMethod metodo, String url, String token) throws Exception {
        MockHttpServletRequestBuilder pedido = MockMvcRequestBuilders.request(metodo, url).contentType(MediaType.APPLICATION_JSON)
                .content("{\"limite\":1}");
        if (token != null) {
            pedido.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(pedido).andReturn();
    }

    private static String cuerpo(MvcResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    private void sinEfecto(Tienda t) {
        assertThat(saldo(t.cliente())).isEqualByComparingTo("250");
        assertThat(movimientos(t.cliente())).isEqualTo(1);
        assertThat(jdbc.queryForObject("select limite_cuenta_corriente from cliente where id = ?", java.math.BigDecimal.class,
                t.cliente())).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("el Vendedor recibe 403 con GET, y con un parámetro inválido (?tipo=XYZ, ?desde=basura, desde > hasta) TAMBIÉN 403 (no 400); el cuerpo no trae datos")
    void vendedorEs403AunqueElParametroSeaInvalido() throws Exception {
        Tienda t = tienda();
        Map<String, MvcResult> respuestas = new LinkedHashMap<>();
        for (String consulta : List.of("", "?tipo=XYZ", "?desde=basura", "?hasta=2026-13-45", "?desde=2026-10-20&hasta=2026-10-19",
                "?page=abc", "?size=x", "?impresion=quizas")) {
            respuestas.put(consulta, pedir(HttpMethod.GET, t.url() + consulta, t.vendedor()));
        }

        assertThat(respuestas).allSatisfy((consulta, r) -> {
            assertThat(r.getResponse().getStatus()).as("Vendedor GET extracto%s", consulta).isEqualTo(403);
            assertThat(cuerpo(r)).as("cuerpo de %s", consulta).isEqualTo(PERMISO).doesNotContain("250");
        });
        sinEfecto(t);
    }

    @Test
    @DisplayName("el Vendedor con HEAD recibe 403 (cae en el @PreAuthorize: el matcher es solo de GET)")
    void vendedorHeadEs403() throws Exception {
        Tienda t = tienda();

        assertThat(pedir(HttpMethod.HEAD, t.url(), t.vendedor()).getResponse().getStatus()).isEqualTo(403);
        assertThat(pedir(HttpMethod.HEAD, t.url(), null).getResponse().getStatus()).as("sin token").isEqualTo(401);
        assertThat(pedir(HttpMethod.HEAD, t.url(), t.dueno()).getResponse().getStatus()).as("el Dueño sí pasa el rol").isEqualTo(200);
    }

    @Test
    @DisplayName("POST, PUT, PATCH y DELETE sobre el extracto dan 405 «Método no permitido» al Dueño y al Vendedor (no 403 ni 500), sin datos y sin efecto")
    void metodosNoPrevistos() throws Exception {
        Tienda t = tienda();

        for (HttpMethod metodo : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            for (Map.Entry<String, String> quien : Map.of("Dueño", t.dueno(), "Vendedor", t.vendedor()).entrySet()) {
                MvcResult r = pedir(metodo, t.url(), quien.getValue());
                assertThat(r.getResponse().getStatus()).as("%s %s extracto", quien.getKey(), metodo).isIn(405);
                assertThat(cuerpo(r)).as("%s %s", quien.getKey(), metodo).contains("Método no permitido").doesNotContain("250");
            }
        }
        sinEfecto(t);
    }

    @Test
    @DisplayName("sin token es 401 con GET, HEAD, POST, PUT, PATCH y DELETE; el SUPER_ADMIN (sin ferretería) es 403 en el GET")
    void sinTokenYSuperAdmin() throws Exception {
        Tienda t = tienda();

        for (HttpMethod metodo : List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH,
                HttpMethod.DELETE)) {
            assertThat(pedir(metodo, t.url(), null).getResponse().getStatus()).as("sin token %s", metodo).isEqualTo(401);
        }
        MvcResult admin = pedir(HttpMethod.GET, t.url(), login("admin@demo.local"));
        assertThat(admin.getResponse().getStatus()).isEqualTo(403);
        assertThat(cuerpo(admin)).isEqualTo(PERMISO);
    }

    @Test
    @DisplayName("el par del Dueño: con ?tipo=XYZ recibe 400 (ya pasó el rol), con un parámetro válido 200 y con desde > hasta 400 en desde")
    void duenoEntraYValida() throws Exception {
        Tienda t = tienda();

        MvcResult tipo = pedir(HttpMethod.GET, t.url() + "?tipo=XYZ", t.dueno());
        assertThat(tipo.getResponse().getStatus()).isEqualTo(400);
        assertThat(cuerpo(tipo)).contains("Parámetro inválido: tipo");
        assertThat(pedir(HttpMethod.GET, t.url() + "?desde=basura", t.dueno()).getResponse().getStatus()).isEqualTo(400);
        assertThat(pedir(HttpMethod.GET, t.url() + "?desde=2026-10-20&hasta=2026-10-19", t.dueno()).getResponse().getStatus())
                .isEqualTo(400);
        MvcResult ok = pedir(HttpMethod.GET, t.url() + "?tipo=VENTA", t.dueno());
        assertThat(ok.getResponse().getStatus()).isEqualTo(200);
        assertThat(cuerpo(ok)).contains("\"saldoActual\":250.00");
    }
}
