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
 *
 * <p>CCT3B1-604 (CC-055, CC-101): lo mismo para {@code POST /api/recibos/{id}/anulacion}: cae bajo {@code /api/recibos/**}, así que
 * el Vendedor recibe 403 con TODO método y con cualquier cuerpo (el matcher actúa antes de leer el cuerpo; sin él, un cuerpo
 * ilegible daría 400, porque el binding corre antes del {@code @PreAuthorize}), y el Dueño recibe 405 con los métodos que no
 * existen en esa ruta y 400 con un cuerpo ilegible. Mutaciones (registradas en apply-progress): quitar el matcher
 * {@code /api/recibos/**} rompe estos tests; quitar el matcher y el {@code @PreAuthorize} a la vez deja anular al Vendedor.
 * CUIT 30-8411NNNN-1.
 */
class CobranzaSeguridadHttpIntegrationTest extends CuentaCorrienteTestBase {

    private static final String ANULACION = "/api/recibos/1/anulacion";

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
            "/api/recibos/1", List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE),
            ANULACION, List.of(HttpMethod.POST, HttpMethod.GET, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE));

    /** Los métodos que NO existen en esas rutas (los que el Dueño ve como 405). */
    private static final Map<String, List<HttpMethod>> METODOS_SIN_HANDLER = Map.of(
            "/api/cobranzas", List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE),
            "/api/recibos/1", List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE),
            ANULACION, List.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE));

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

    // ------------------------------------------------------------------ CCT3B1-604: POST /api/recibos/{id}/anulacion

    /** Un recibo ya cobrado y asentado (por SQL) del cliente de la tienda: lo que hay que anular. */
    private long reciboDe(Tienda t) {
        ventaEnLibro(t.f(), t.cliente(), "300");
        return cobroEnLibro(t.f(), t.cliente(), "300");
    }

    private MvcResult anular(String token, long recibo, String cuerpo) throws Exception {
        MockHttpServletRequestBuilder pedido = MockMvcRequestBuilders.post("/api/recibos/" + recibo + "/anulacion")
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo);
        if (token != null) {
            pedido.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(pedido).andReturn();
    }

    private void reciboIntacto(Tienda t, long recibo) {
        assertThat(jdbc.queryForObject("select estado from recibo where id = ?", String.class, recibo)).isEqualTo("EMITIDO");
        assertThat(jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente where cliente_id = ? and tipo = 'ANULACION'",
                Long.class, t.cliente())).isZero();
        assertThat(saldo(t.cliente())).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("CC-055: el Vendedor recibe 403 al anular con un motivo válido, vacío, un cuerpo ilegible o sin cuerpo (el matcher actúa antes del binding: no es 400), y el recibo, el libro y el saldo quedan intactos")
    void vendedorNoAnulaConNingunCuerpo() throws Exception {
        Tienda t = tienda();
        long recibo = reciboDe(t);

        Map<String, Integer> estados = new java.util.LinkedHashMap<>();
        Map<String, String> respuestas = new java.util.LinkedHashMap<>();
        for (String cuerpo : List.of("{\"motivo\":\"Cobro mal cargado\"}", "{\"motivo\":\"\"}", "{}", "esto no es json", "")) {
            MvcResult r = anular(t.vendedor(), recibo, cuerpo);
            estados.put(cuerpo, r.getResponse().getStatus());
            respuestas.put(cuerpo, r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        }
        assertThat(estados).as("estado por cuerpo: 403 siempre, nunca 400 ni 200").containsOnlyKeys(estados.keySet())
                .allSatisfy((cuerpo, estado) -> assertThat(estado).as("Vendedor, cuerpo «%s»", cuerpo).isEqualTo(403));
        assertThat(respuestas.values()).as("el cuerpo del matcher, sin datos").containsOnly(PERMISO);

        reciboIntacto(t, recibo);
        assertInvariante(t.cliente());
    }

    @Test
    @DisplayName("CC-055: el SUPER_ADMIN (sin ferretería) recibe 403 y sin token 401 al anular, sin cambios")
    void superAdminYSinTokenNoAnulan() throws Exception {
        Tienda t = tienda();
        long recibo = reciboDe(t);

        assertThat(anular(login("admin@demo.local"), recibo, "{\"motivo\":\"Cobro mal cargado\"}").getResponse().getStatus()).isEqualTo(403);
        assertThat(anular(null, recibo, "{\"motivo\":\"Cobro mal cargado\"}").getResponse().getStatus()).isEqualTo(401);

        reciboIntacto(t, recibo);
    }

    @Test
    @DisplayName("CC-055/CC-101: el Dueño sí entra por POST …/anulacion: un cuerpo ilegible es 400 (ya pasó el rol, no 403) y con un motivo válido anula (200)")
    void duenoAnula() throws Exception {
        Tienda t = tienda();
        long recibo = reciboDe(t);

        MvcResult ilegible = anular(t.dueno(), recibo, "esto no es json");
        assertThat(ilegible.getResponse().getStatus()).as("ilegible: el Dueño pasa el rol y falla el binding").isEqualTo(400);
        reciboIntacto(t, recibo);

        assertThat(anular(t.dueno(), recibo, "{\"motivo\":\"Cobro mal cargado\"}").getResponse().getStatus()).isEqualTo(200);
        assertThat(jdbc.queryForObject("select estado from recibo where id = ?", String.class, recibo)).isEqualTo("ANULADO");
        assertInvariante(t.cliente());
    }
}
