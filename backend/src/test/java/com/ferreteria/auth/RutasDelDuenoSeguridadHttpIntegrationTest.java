package com.ferreteria.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.support.IntegrationTestBase;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * H2 del recorrido del Vendedor (S3): los endpoints solo del Dueño de compras, ajustes de stock, vista previa del margen y
 * escrituras de rubros, proveedores, artículos y formas de pago exigen el rol por ruta en {@code SecurityConfig}, antes de leer
 * el cuerpo o los parámetros. Sin el matcher, el binding (que corre antes del {@code @PreAuthorize}) le daba al Vendedor el 400
 * de la validación en vez del 403. Lo que se fija acá:
 * <ul>
 *   <li>el Vendedor con un pedido inválido recibe 403 con el cuerpo de permiso (también con HEAD en la vista previa del margen:
 *       el matcher no restringe el método, como el del extracto);
 *   <li>el Dueño con el mismo pedido inválido recibe el 400 de siempre, con su mensaje;
 *   <li>lo que el Vendedor ya hacía (leer artículos, rubros, proveedores y formas de pago; alta y edición de clientes) sigue en
 *       200 y 201, y sin token sigue siendo 401.
 * </ul>
 * Usa la ferretería demo «El Tornillo» (perfil test): los pedidos inválidos no escriben nada.
 */
class RutasDelDuenoSeguridadHttpIntegrationTest extends IntegrationTestBase {

    private static final String PERMISO = "{\"status\":403,\"message\":\"No tenés permiso para hacer esto\"}";

    private static final String NO_ES_JSON = "no es json";

    /** Un pedido que no valida y el 400 que le da al Dueño. {@code cuerpo} null: sin cuerpo. */
    private record Pedido(HttpMethod metodo, String url, String cuerpo, String mensajeAlDueno) {

        @Override
        public String toString() {
            return metodo + " " + url + (cuerpo == null ? "" : " " + cuerpo);
        }
    }

    private String dueno;
    private String vendedor;
    private List<Pedido> invalidos;

    @BeforeEach
    void preparar() throws Exception {
        dueno = login("dueno@tornillo.demo");
        vendedor = login("vendedor@tornillo.demo");
        long rubro = getJson("/api/rubros", dueno).get(0).get("id").asLong();
        long proveedor = getJson("/api/proveedores", dueno).get(0).get("id").asLong();
        long articulo = getJson("/api/articulos?q=TOR-001", dueno).get("items").get(0).get("id").asLong();
        long forma = getJson("/api/formas-pago", dueno).get(0).get("id").asLong();
        String margen = "/api/rubros/" + rubro + "/vista-previa-margen";
        invalidos = List.of(
                // Compras (todo /api/compras/**)
                new Pedido(HttpMethod.POST, "/api/compras/vista-previa", "{}", "Datos inválidos"),
                new Pedido(HttpMethod.POST, "/api/compras/vista-previa", NO_ES_JSON, "El cuerpo del pedido no es válido"),
                new Pedido(HttpMethod.POST, "/api/compras", "{}", "Datos inválidos"),
                new Pedido(HttpMethod.GET, "/api/compras?desde=basura", null, "Parámetro inválido: desde"),
                new Pedido(HttpMethod.GET, "/api/compras/abc", null, "Parámetro inválido: id"),
                // Ajuste de stock
                new Pedido(HttpMethod.POST, "/api/articulos/" + articulo + "/ajustes-stock", "{\"modo\":\"XYZ\"}",
                        "El modo no es válido. Usá STOCK_REAL o CANTIDAD."),
                new Pedido(HttpMethod.POST, "/api/articulos/" + articulo + "/ajustes-stock", NO_ES_JSON,
                        "El cuerpo del pedido no es válido"),
                // Vista previa del margen, con GET y con HEAD
                new Pedido(HttpMethod.GET, margen, null, "Falta el parámetro: margenPorcentaje"),
                new Pedido(HttpMethod.GET, margen + "?margenPorcentaje=abc", null, "Parámetro inválido: margenPorcentaje"),
                new Pedido(HttpMethod.HEAD, margen + "?margenPorcentaje=abc", null, null),
                // Rubros
                new Pedido(HttpMethod.POST, "/api/rubros", "{}", "Datos inválidos"),
                new Pedido(HttpMethod.PUT, "/api/rubros/" + rubro, "{}", "Datos inválidos"),
                new Pedido(HttpMethod.PUT, "/api/rubros/abc", "{}", "Parámetro inválido: id"),
                // Proveedores
                new Pedido(HttpMethod.POST, "/api/proveedores", "{}", "Datos inválidos"),
                new Pedido(HttpMethod.PUT, "/api/proveedores/" + proveedor, "{}", "Datos inválidos"),
                new Pedido(HttpMethod.DELETE, "/api/proveedores/abc", null, "Parámetro inválido: id"),
                new Pedido(HttpMethod.POST, "/api/proveedores/abc/reactivacion", null, "Parámetro inválido: id"),
                // Artículos
                new Pedido(HttpMethod.POST, "/api/articulos", "{}", "Datos inválidos"),
                new Pedido(HttpMethod.PUT, "/api/articulos/" + articulo, "{}", "Datos inválidos"),
                new Pedido(HttpMethod.PUT, "/api/articulos/" + articulo + "/oferta", NO_ES_JSON,
                        "El cuerpo del pedido no es válido"),
                new Pedido(HttpMethod.DELETE, "/api/articulos/abc/oferta", null, "Parámetro inválido: id"),
                new Pedido(HttpMethod.POST, "/api/articulos/abc/reactivacion", null, "Parámetro inválido: id"),
                new Pedido(HttpMethod.DELETE, "/api/articulos/abc", null, "Parámetro inválido: id"),
                // Formas de pago
                new Pedido(HttpMethod.POST, "/api/formas-pago", "{}", "Datos inválidos"),
                new Pedido(HttpMethod.PUT, "/api/formas-pago/" + forma, "{}", "Datos inválidos"));
    }

    private MvcResult pedir(Pedido pedido, String token) throws Exception {
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.request(pedido.metodo(), pedido.url());
        if (pedido.cuerpo() != null) {
            builder.contentType(MediaType.APPLICATION_JSON).content(pedido.cuerpo());
        }
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        return mvc.perform(builder).andReturn();
    }

    private static String cuerpo(MvcResult r) {
        return new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("el Vendedor recibe 403 con el cuerpo de permiso en cada ruta solo del Dueño aunque el cuerpo o el parámetro no valide (no 400)")
    void vendedorEs403AunqueElPedidoSeaInvalido() throws Exception {
        for (Pedido pedido : invalidos) {
            MvcResult r = pedir(pedido, vendedor);

            assertThat(r.getResponse().getStatus()).as("Vendedor %s", pedido).isEqualTo(403);
            if (pedido.metodo() != HttpMethod.HEAD) {
                assertThat(cuerpo(r)).as("cuerpo de %s", pedido).isEqualTo(PERMISO);
            }
        }
    }

    @Test
    @DisplayName("el Dueño con el mismo pedido inválido recibe el 400 de siempre, con su mensaje")
    void duenoRecibeEl400DeSiempre() throws Exception {
        for (Pedido pedido : invalidos) {
            MvcResult r = pedir(pedido, dueno);

            assertThat(r.getResponse().getStatus()).as("Dueño %s", pedido).isEqualTo(400);
            if (pedido.mensajeAlDueno() != null) {
                JsonNode error = objectMapper.readTree(cuerpo(r));
                assertThat(error.get("message").asText()).as("mensaje de %s", pedido).isEqualTo(pedido.mensajeAlDueno());
            }
        }
    }

    @Test
    @DisplayName("sin token, los mismos pedidos siguen siendo 401")
    void sinTokenSigueSiendo401() throws Exception {
        for (Pedido pedido : invalidos) {
            assertThat(pedir(pedido, null).getResponse().getStatus()).as("sin token %s", pedido).isEqualTo(401);
        }
    }

    @Test
    @DisplayName("lo que el Vendedor ya hacía sigue igual: lee artículos, rubros, proveedores y formas de pago, y da de alta y edita clientes")
    void loQueElVendedorHaceSigueIgual() throws Exception {
        long articulo = getJson("/api/articulos?q=TOR-001", vendedor).get("items").get(0).get("id").asLong();
        long rubro = getJson("/api/rubros", vendedor).get(0).get("id").asLong();
        long proveedor = getJson("/api/proveedores", vendedor).get(0).get("id").asLong();

        getJson("/api/articulos/" + articulo, vendedor);
        getJson("/api/articulos/mostrador?q=TOR-001", vendedor);
        getJson("/api/rubros/" + rubro, vendedor);
        getJson("/api/proveedores/" + proveedor, vendedor);
        assertThat(getJson("/api/formas-pago?activas=true", vendedor).size()).isPositive();

        String razon = unico("Cliente del Vendedor");
        MvcResult alta = mvc.perform(MockMvcRequestBuilders.post("/api/clientes").header("Authorization", "Bearer " + vendedor)
                .contentType(MediaType.APPLICATION_JSON).content("{\"razonSocial\":\"" + razon + "\"}")).andReturn();
        assertThat(alta.getResponse().getStatus()).as("alta de cliente").isEqualTo(201);
        long cliente = objectMapper.readTree(cuerpo(alta)).get("id").asLong();

        MvcResult edicion = mvc.perform(MockMvcRequestBuilders.put("/api/clientes/" + cliente)
                .header("Authorization", "Bearer " + vendedor).contentType(MediaType.APPLICATION_JSON)
                .content("{\"razonSocial\":\"" + razon + " (editado)\"}")).andReturn();
        assertThat(edicion.getResponse().getStatus()).as("edición de cliente").isEqualTo(200);
        assertThat(objectMapper.readTree(cuerpo(edicion)).get("razonSocial").asText()).isEqualTo(razon + " (editado)");
    }
}
