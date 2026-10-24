package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Clientes que compraron un articulo (G29, el Ctrl+A del listado de GDS): una fila por cliente con sus compras, la
 * cantidad y la ultima compra, la mas nueva primero. Las ventas sin cliente solo se cuentan. Solo el Dueño.
 */
class ClientesDelArticuloIntegrationTest extends VentasTestBase {

    private Tienda tienda;
    private String dueno;
    private long tornillo;
    private long tuerca;
    private long ana;
    private long beto;
    private long carla;
    private final Map<String, Long> ventas = new java.util.HashMap<>();

    private static String url(long articuloId) {
        return "/api/articulos/" + articuloId + "/clientes";
    }

    /**
     * Ana compra el tornillo dos veces (2 y 4), Beto una vez (5, con una
     * tuerca) y otra solo tuercas, Carla una vez y despues se la da de baja. Dos ventas de tornillos sin cliente y una
     * de tuercas sin cliente.
     */
    @BeforeEach
    void ventas() throws Exception {
        tienda = tienda();
        dueno = login(tienda.emailDueno());
        tornillo = articulo(tienda, "Tornillo 1/4", "UNIDAD", "100.00", "1000");
        tuerca = articulo(tienda, "Tuerca 1/4", "UNIDAD", "50.00", "1000");
        ana = cliente(tienda, "Ana Ferrari");
        beto = cliente(tienda, "Beto Gómez");
        carla = cliente(tienda, "Carla Díaz");

        venta(ana, "2026-01-10 10:00:00", linea(tornillo, "2", "100.00"));
        venta(ana, "2026-03-01 10:00:00", linea(tornillo, "4", "100.00"));
        venta(beto, "2026-02-01 10:00:00", linea(tornillo, "5", "100.00"), linea(tuerca, "1", "50.00"));
        venta(beto, "2026-05-01 10:00:00", linea(tuerca, "4", "50.00"));
        venta(carla, "2026-04-01 10:00:00", linea(tornillo, "2", "100.00"));
        venta(null, "2026-04-02 10:00:00", linea(tornillo, "1", "100.00"));
        venta(null, "2026-04-03 10:00:00", linea(tornillo, "2", "100.00"));
        venta(null, "2026-04-04 10:00:00", linea(tuerca, "1", "50.00"));
        jdbc.update("update cliente set activo = false where id = ?", carla);
    }

    private long cliente(Tienda t, String razonSocial) {
        String razon = razonSocial + " " + UUID.randomUUID().toString().substring(0, 4);
        jdbc.update("insert into cliente (ferreteria_id, razon_social) values (?, ?)", t.id(), razon);
        return jdbc.queryForObject("select id from cliente where ferreteria_id = ? and razon_social = ?", Long.class,
                t.id(), razon);
    }

    /** Una venta en efectivo (con cliente o sin el) que despues se lleva a esa fecha. */
    private void venta(Long clienteId, String fecha, Linea... lineas) throws Exception {
        String total = Arrays.stream(lineas)
                .map(l -> new BigDecimal(l.cantidad()).multiply(new BigDecimal(l.precio())))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2).toPlainString();
        Map<String, Object> cuerpo = mapa(clave(), "EFECTIVO", total, lineas);
        if (clienteId != null) {
            cuerpo.put("clienteId", clienteId);
        }
        long id = json(postJson("/api/ventas", dueno, objectMapper.writeValueAsString(cuerpo))
                .andExpect(status().isCreated())).get("ventaId").asLong();
        jdbc.update("update comprobante set fecha = ? where id = ?", comoLaGuardaHibernate(fecha), id);
        ventas.put(fecha, id);
    }

    /**
     * Una hora de Buenos Aires guardada como la guarda Hibernate ({@code hibernate.jdbc.time_zone: UTC}): pasada a UTC con
     * la zona de la JVM (docs/spec.md, «Despliegue: zona horaria»). Un texto crudo en el SQL no pasa por esa conversion.
     */
    private static String comoLaGuardaHibernate(String fechaHora) {
        return LocalDateTime.parse(fechaHora.replace(' ', 'T')).atZone(ZoneId.systemDefault())
                .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime().toString().replace('T', ' ');
    }

    private static BigDecimal dec(String valor) {
        return new BigDecimal(valor);
    }

    @Test
    @DisplayName("una fila por cliente con compras, cantidad y ultima compra, la mas nueva primero; las ventas sin cliente solo se cuentan")
    void agrupaPorCliente() throws Exception {
        JsonNode pagina = getJson(url(tornillo), dueno);

        assertThat(pagina.get("totalElements").asLong()).isEqualTo(3);
        assertThat(pagina.get("ventasSinCliente").asLong()).isEqualTo(2);
        JsonNode items = pagina.get("items");
        assertThat(items.findValues("clienteId")).extracting(JsonNode::asLong).containsExactly(carla, ana, beto);
        assertThat(items.findValuesAsText("ultimaCompra")).containsExactly("2026-04-01", "2026-03-01", "2026-02-01");
        assertThat(items.findValues("compras")).extracting(JsonNode::asLong).containsExactly(1L, 2L, 1L);
        assertThat(items.findValues("cantidad")).extracting(JsonNode::decimalValue)
                .usingElementComparator(BigDecimal::compareTo)
                .containsExactly(dec("2"), dec("6"), dec("5"));
        assertThat(items.get(1).get("razonSocial").asText()).startsWith("Ana Ferrari");
        assertThat(items.findValues("activo")).extracting(JsonNode::asBoolean).containsExactly(false, true, true);
    }

    @Test
    @DisplayName("la ultima compra es el dia de Buenos Aires de la venta, tambien a la noche: el mismo que muestra la venta")
    void ventaDeNoche() throws Exception {
        venta(beto, "2026-06-01 22:30:00", linea(tornillo, "1", "100.00"));

        JsonNode primero = getJson(url(tornillo), dueno).get("items").get(0);

        assertThat(primero.get("clienteId").asLong()).isEqualTo(beto);
        assertThat(primero.get("ultimaCompra").asText()).isEqualTo("2026-06-01");
        assertThat(getJson("/api/ventas/" + ventas.get("2026-06-01 22:30:00"), dueno).get("fecha").asText())
                .startsWith("2026-06-01T22:30");
    }

    @Test
    @DisplayName("la razon social es la actual del cliente, no la copiada en la venta")
    void razonSocialActual() throws Exception {
        jdbc.update("update cliente set razon_social = 'Ana Ferrari SRL' where id = ?", ana);

        JsonNode items = getJson(url(tornillo), dueno).get("items");

        assertThat(items.get(1).get("razonSocial").asText()).isEqualTo("Ana Ferrari SRL");
    }

    @Test
    @DisplayName("pagina de a 20 por defecto; cada pagina trae el total de ventas sin cliente")
    void pagina() throws Exception {
        JsonNode pagina = getJson(url(tornillo) + "?page=1&size=2", dueno);

        assertThat(pagina.get("page").asInt()).isEqualTo(1);
        assertThat(pagina.get("size").asInt()).isEqualTo(2);
        assertThat(pagina.get("totalPages").asInt()).isEqualTo(2);
        assertThat(pagina.get("items").findValues("clienteId")).extracting(JsonNode::asLong).containsExactly(beto);
        assertThat(pagina.get("ventasSinCliente").asLong()).isEqualTo(2);
        assertThat(getJson(url(tornillo), dueno).get("size").asInt()).isEqualTo(20);
    }

    @Test
    @DisplayName("el tamaño de pagina queda entre 1 y 100 y una pagina negativa es la primera")
    void tamanoAcotado() throws Exception {
        assertThat(getJson(url(tornillo) + "?size=0", dueno).get("size").asInt()).isEqualTo(1);
        assertThat(getJson(url(tornillo) + "?size=5000", dueno).get("size").asInt()).isEqualTo(100);
        JsonNode negativa = getJson(url(tornillo) + "?size=-3&page=-2", dueno);
        assertThat(negativa.get("page").asInt()).isZero();
        assertThat(negativa.get("size").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("un articulo dado de baja sigue mostrando quien lo compro")
    void articuloDadoDeBaja() throws Exception {
        jdbc.update("update articulo set activo = false where id = ?", tornillo);

        assertThat(getJson(url(tornillo), dueno).get("totalElements").asLong()).isEqualTo(3);
    }

    @Test
    @DisplayName("un articulo que nadie compro da la lista vacia y cero ventas sin cliente")
    void sinVentas() throws Exception {
        long nuevo = articulo(tienda, "Arandela", "UNIDAD", "10.00", "10");

        JsonNode pagina = getJson(url(nuevo), dueno);

        assertThat(pagina.get("items")).isEmpty();
        assertThat(pagina.get("totalElements").asLong()).isZero();
        assertThat(pagina.get("ventasSinCliente").asLong()).isZero();
    }

    @Test
    @DisplayName("un articulo de otra ferreteria o inexistente da 404; el Vendedor y el admin reciben 403")
    void aislamientoYPermisos() throws Exception {
        Tienda otra = tiendaSinCaja();
        getResult(url(tornillo), login(otra.emailDueno())).andExpect(status().isNotFound());
        // La ferreteria sale solo del JWT: un parametro o un header del cliente no la cambian.
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url(tornillo))
                        .param("ferreteriaId", String.valueOf(tienda.id()))
                        .header("X-Ferreteria-Id", String.valueOf(tienda.id()))
                        .header("Authorization", "Bearer " + login(otra.emailDueno())))
                .andExpect(status().isNotFound());
        getResult(url(999_999_999L), dueno).andExpect(status().isNotFound());
        getResult(url(tornillo), login(tienda.emailVendedor())).andExpect(status().isForbidden());
        getResult(url(tornillo), login("admin@demo.local")).andExpect(status().isForbidden());
    }
}
