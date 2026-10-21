package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
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
 *   <li>Vendedor con HEAD: 403 también con un parámetro inválido (el matcher no restringe el método; con solo GET, el HEAD, que
 *       en Spring MVC es el GET, llegaba al binding y daba 400).
 *   <li>POST/PUT/PATCH/DELETE: 405 para el Dueño (no hay handler) y 403 para el Vendedor (lo frena el matcher; CC-101 acepta
 *       403 o 405), sin cuerpo de datos y sin efecto.
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
    @DisplayName("el Vendedor con HEAD recibe 403, también con un parámetro inválido (?tipo=XYZ): el matcher no distingue el método")
    void vendedorHeadEs403() throws Exception {
        Tienda t = tienda();

        for (String consulta : List.of("", "?tipo=XYZ", "?desde=basura", "?page=abc", "?impresion=quizas")) {
            MvcResult r = pedir(HttpMethod.HEAD, t.url() + consulta, t.vendedor());
            assertThat(r.getResponse().getStatus()).as("Vendedor HEAD extracto%s", consulta).isEqualTo(403);
        }
        assertThat(pedir(HttpMethod.HEAD, t.url(), null).getResponse().getStatus()).as("sin token").isEqualTo(401);
        assertThat(pedir(HttpMethod.HEAD, t.url(), t.dueno()).getResponse().getStatus()).as("el Dueño sí pasa el rol").isEqualTo(200);
    }

    @Test
    @DisplayName("POST, PUT, PATCH y DELETE sobre el extracto: 405 «Método no permitido» para el Dueño y 403 para el Vendedor (nunca 500), sin datos y sin efecto")
    void metodosNoPrevistos() throws Exception {
        Tienda t = tienda();

        for (HttpMethod metodo : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            MvcResult dueno = pedir(metodo, t.url(), t.dueno());
            assertThat(dueno.getResponse().getStatus()).as("Dueño %s extracto", metodo).isEqualTo(405);
            assertThat(cuerpo(dueno)).as("Dueño %s", metodo).contains("Método no permitido");
            sinDatosDelExtracto(dueno, "Dueño " + metodo);

            // CC-101 acepta 403 o 405: el matcher del extracto no mira el método, así que el Vendedor recibe 403 con cualquiera
            MvcResult vendedor = pedir(metodo, t.url(), t.vendedor());
            assertThat(vendedor.getResponse().getStatus()).as("Vendedor %s extracto", metodo).isEqualTo(403);
            assertThat(cuerpo(vendedor)).as("Vendedor %s", metodo).isEqualTo(PERMISO);
        }
        sinEfecto(t);
    }

    /**
     * El cuerpo es un error y no trae nada del extracto. Se afirma sobre las CLAVES del JSON y no sobre un texto como «250»: el
     * {@code timestamp} del error lleva microsegundos y alguna vez termina en «…250Z», lo que hacía fallar el test al azar.
     */
    private void sinDatosDelExtracto(MvcResult r, String quien) throws Exception {
        JsonNode json = objectMapper.readTree(cuerpo(r));
        assertThat(json.fieldNames()).toIterable().as("claves del cuerpo de %s", quien)
                .doesNotContain("clienteId", "saldoActual", "saldoFinal", "movimientos", "items");
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
