package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.clientes.CuentaCorrienteTestBase;
import java.nio.charset.StandardCharsets;
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
 * S2 del verify de cuenta-corriente-tramo-3a: los matchers de {@code SecurityConfig} para {@code /api/cobranzas/**},
 * {@code /api/recibos/**} y {@code GET …/pendientes}, probados con los MÉTODOS NO PREVISTOS (PUT, DELETE, PATCH, HEAD...). Los
 * demás tests HTTP de cobranza solo usan los métodos que existen y el {@code @PreAuthorize} de la clase los absorbe, así que
 * sin esta clase nadie notaría que se cae un matcher.
 *
 * <p>El comportamiento real, fijado acá:
 * <ul>
 *   <li>Sin token: 401 en todo.
 *   <li>Vendedor: 403 en TODO método de {@code /api/cobranzas/**} y {@code /api/recibos/**} (el matcher de ruta corre antes de
 *       buscar el handler, así que el método no existente no llega a dar 405 ni 404); en {@code …/pendientes} el matcher es solo
 *       de GET: un HEAD no lo cumple pero igual da 403 por el {@code @PreAuthorize} del método (el HEAD de Spring MVC es el GET), y
 *       un método sin handler da 405 (no hay nada que proteger: no existe).
 *   <li>Dueño con un método que no existe: 405, nunca 500.
 * </ul>
 * CUIT 30-8411NNNN-1.
 */
class CobranzaSeguridadHttpIntegrationTest extends CuentaCorrienteTestBase {

    private static final String PERMISO = "{\"status\":403,\"message\":\"No tenés permiso para hacer esto\"}";

    @Override
    protected String prefijoDeCuit() {
        return "30-8411";
    }

    /** Una ferretería con su Dueño y su Vendedor, y un cliente al que cobrarle (para que un cobro indebido dejara rastro). */
    private record Tienda(Ferreteria f, String dueno, String vendedor, long cliente) {}

    private Tienda tienda() throws Exception {
        Ferreteria f = ferreteria();
        Logins login = logins(f);
        cajaAbiertaHoy(f.id());
        return new Tienda(f, login(login.dueno()), login(login.vendedor()), cliente(f, "1000"));
    }

    private int estado(HttpMethod metodo, String url, String token) throws Exception {
        return respuesta(metodo, url, token).getResponse().getStatus();
    }

    private MvcResult respuesta(HttpMethod metodo, String url, String token) throws Exception {
        MockHttpServletRequestBuilder pedido = MockMvcRequestBuilders.request(metodo, url).contentType(MediaType.APPLICATION_JSON)
                .content("{\"clave\":\"00000000-0000-4000-8000-000000000001\",\"clienteId\":1,\"medio\":\"EFECTIVO\",\"total\":10}");
        if (token != null) {
            pedido.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(pedido).andReturn();
    }

    /** Todo método y ruta de la cobranza y del recibo, existan o no: el rol se exige por ruta, antes de buscar el handler. */
    private static final Map<String, List<HttpMethod>> RUTAS_DE_LA_COBRANZA = Map.of(
            "/api/cobranzas", List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE),
            "/api/cobranzas/1", List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE),
            "/api/recibos", List.of(HttpMethod.GET, HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE),
            "/api/recibos/1", List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE));

    /** Los métodos que NO existen en esas rutas (los que el Dueño ve como 405). */
    private static final Map<String, List<HttpMethod>> METODOS_SIN_HANDLER = Map.of(
            "/api/cobranzas", List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE),
            "/api/recibos/1", List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE));

    private static final String PENDIENTES = "/api/clientes/1/cuenta-corriente/pendientes";

    @Test
    @DisplayName("el Vendedor recibe 403 con PUT, PATCH, DELETE, HEAD, GET y POST de cualquier ruta de /api/cobranzas/** y /api/recibos/** (existan o no), con el cuerpo de permiso")
    void vendedorTodoMetodoEs403() throws Exception {
        Tienda t = tienda();

        for (Map.Entry<String, List<HttpMethod>> ruta : RUTAS_DE_LA_COBRANZA.entrySet()) {
            for (HttpMethod metodo : ruta.getValue()) {
                MvcResult r = respuesta(metodo, ruta.getKey(), t.vendedor());
                assertThat(r.getResponse().getStatus()).as("Vendedor %s %s", metodo, ruta.getKey()).isEqualTo(403);
                if (metodo != HttpMethod.HEAD) {
                    assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).as("cuerpo de %s %s", metodo, ruta.getKey())
                            .isEqualTo(PERMISO);
                }
            }
        }
        assertThat(jdbc.queryForObject("select count(*) from recibo where ferreteria_id = ?", Long.class, t.f().id())).isZero();
    }

    @Test
    @DisplayName("…/pendientes: el Vendedor recibe 403 con GET y con HEAD (el matcher es de GET y el @PreAuthorize del método cubre el HEAD); con un método sin handler recibe 405")
    void vendedorEnPendientes() throws Exception {
        Tienda t = tienda();
        String url = "/api/clientes/" + t.cliente() + "/cuenta-corriente/pendientes";

        assertThat(estado(HttpMethod.GET, url, t.vendedor())).as("Vendedor GET").isEqualTo(403);
        assertThat(estado(HttpMethod.HEAD, url, t.vendedor())).as("Vendedor HEAD").isEqualTo(403);
        assertThat(estado(HttpMethod.PUT, url, t.vendedor())).as("Vendedor PUT (no existe)").isEqualTo(405);
        assertThat(estado(HttpMethod.DELETE, url, t.vendedor())).as("Vendedor DELETE (no existe)").isEqualTo(405);
        assertThat(estado(HttpMethod.POST, url, t.vendedor())).as("Vendedor POST (no existe)").isEqualTo(405);
        // y no hay ningún dato en esas respuestas
        assertThat(respuesta(HttpMethod.GET, url, t.vendedor()).getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(PERMISO);
    }

    @Test
    @DisplayName("sin token es 401 con cualquier método de /api/cobranzas/**, /api/recibos/** y …/pendientes")
    void sinTokenEs401() throws Exception {
        for (Map.Entry<String, List<HttpMethod>> ruta : RUTAS_DE_LA_COBRANZA.entrySet()) {
            for (HttpMethod metodo : ruta.getValue()) {
                assertThat(estado(metodo, ruta.getKey(), null)).as("sin token %s %s", metodo, ruta.getKey()).isEqualTo(401);
            }
        }
        for (HttpMethod metodo : List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE,
                HttpMethod.POST)) {
            assertThat(estado(metodo, PENDIENTES, null)).as("sin token %s pendientes", metodo).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("el Dueño con un método que no existe recibe 405 «Método no permitido» (no 500, ni 403 ni 404) y no se registra nada")
    void duenoMetodoNoPrevistoEs405() throws Exception {
        Tienda t = tienda();
        String pendientes = "/api/clientes/" + t.cliente() + "/cuenta-corriente/pendientes";

        for (Map.Entry<String, List<HttpMethod>> ruta : METODOS_SIN_HANDLER.entrySet()) {
            for (HttpMethod metodo : ruta.getValue()) {
                MvcResult r = respuesta(metodo, ruta.getKey(), t.dueno());
                assertThat(r.getResponse().getStatus()).as("Dueño %s %s", metodo, ruta.getKey()).isEqualTo(405);
                if (metodo != HttpMethod.HEAD) {
                    assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("Método no permitido");
                }
            }
        }
        for (HttpMethod metodo : List.of(HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE, HttpMethod.POST)) {
            assertThat(estado(metodo, pendientes, t.dueno())).as("Dueño %s pendientes", metodo).isEqualTo(405);
        }
        assertThat(jdbc.queryForObject("select count(*) from recibo where ferreteria_id = ?", Long.class, t.f().id())).isZero();
        assertThat(jdbc.queryForList("select ultimo_numero from numerador_recibo where ferreteria_id = ?", Long.class, t.f().id()))
                .as("no se consumió ningún número").isEmpty();
    }

    @Test
    @DisplayName("el Dueño sí entra por los métodos previstos: POST /api/cobranzas (400 por cuerpo inválido, ya pasó el rol), GET /api/recibos/{id} (404) y GET …/pendientes (200)")
    void duenoPorLosMetodosPrevistos() throws Exception {
        Tienda t = tienda();

        assertThat(postJson("/api/cobranzas", t.dueno(), "{\"clave\":\"no-es-uuid\"}").andReturn().getResponse().getStatus())
                .as("pasó el rol y llegó a la validación del cuerpo").isEqualTo(400);
        assertThat(estado(HttpMethod.GET, "/api/recibos/" + Long.MAX_VALUE, t.dueno())).isEqualTo(404);
        assertThat(estado(HttpMethod.GET, "/api/clientes/" + t.cliente() + "/cuenta-corriente/pendientes", t.dueno())).isEqualTo(200);
    }
}
