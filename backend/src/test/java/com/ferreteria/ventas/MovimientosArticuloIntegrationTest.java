package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Movimientos de stock de un articulo (G29, GDS-17): el mas nuevo primero, con el saldo despues de cada uno y el
 * comprobante que lo origino. Vive con los tests de ventas para reusar su tienda con caja; el endpoint es del modulo stock.
 */
class MovimientosArticuloIntegrationTest extends VentasTestBase {

    private Tienda tienda;
    private String dueno;
    private long articulo;
    private long numeroVenta;
    private long numeroRemito;

    private static String url(long articuloId) {
        return "/api/articulos/" + articuloId + "/movimientos-stock";
    }

    /**
     * Stock 10; venta de 2 (8), remito de 1 (7), compra de 5 (12) y ajuste por rotura a 11. La compra va despues de
     * vender porque recalcula el precio.
     */
    @BeforeEach
    void movimientos() throws Exception {
        tienda = tienda();
        dueno = login(tienda.emailDueno());
        articulo = articulo(tienda, "Tornillo 1/4", "UNIDAD", "100.00", "10");
        numeroVenta = json(vender(dueno, clave(), "200.00", linea(articulo, "2", "100.00"))
                .andExpect(status().isCreated())).get("numero").asLong();
        numeroRemito = remito(dueno, articulo, "1", "100.00", "100.00").get("numero").asLong();
        jdbc.update("insert into proveedor (ferreteria_id, nombre, activo) values (?, 'Bulonera Sur', true)", tienda.id());
        long proveedor = jdbc.queryForObject("select id from proveedor where ferreteria_id = ?", Long.class, tienda.id());
        postJson("/api/compras", dueno, """
                {"clave": "%s", "proveedorId": %d, "tipo": "FA", "puntoVenta": 3, "numero": 1234, "fecha": "%s",
                 "renglones": [{"articuloId": %d, "cantidad": 5, "costoUnitario": 1.00}]}
                """.formatted(clave(), proveedor, LocalDate.now().minusDays(1), articulo))
                .andExpect(status().isCreated());
        postJson("/api/articulos/" + articulo + "/ajustes-stock", dueno,
                "{\"stockReal\": 11, \"motivo\": \"ROTURA\"}")
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("lista los movimientos del mas nuevo al mas viejo con saldo, comprobante, usuario y motivo")
    void listaConSaldoYComprobante() throws Exception {
        JsonNode pagina = getJson(url(articulo), dueno);

        assertThat(pagina.get("totalElements").asLong()).isEqualTo(4);
        JsonNode items = pagina.get("items");
        assertThat(items).hasSize(4);
        assertThat(items.findValuesAsText("tipo")).containsExactly("AJUSTE", "COMPRA", "REMITO", "VENTA");
        assertThat(items.findValues("cantidad")).extracting(JsonNode::decimalValue)
                .usingElementComparator(java.math.BigDecimal::compareTo)
                .containsExactly(dec("-1"), dec("5"), dec("-1"), dec("-2"));
        assertThat(items.findValues("saldo")).extracting(JsonNode::decimalValue)
                .usingElementComparator(java.math.BigDecimal::compareTo)
                .containsExactly(dec("11"), dec("12"), dec("7"), dec("8"));
        assertThat(items.get(0).get("comprobante").isNull()).isTrue();
        assertThat(items.get(0).get("motivo").asText()).isEqualTo("Rotura");
        assertThat(items.get(1).get("comprobante").asText()).isEqualTo("FA 00003-00001234 · Bulonera Sur");
        assertThat(items.get(2).get("comprobante").asText()).isEqualTo("Remito N.º " + numeroRemito);
        assertThat(items.get(3).get("comprobante").asText()).isEqualTo("Venta N.º " + numeroVenta);
        assertThat(items.findValuesAsText("usuario")).containsOnly("Dueno Ventas");
        assertThat(items.get(0).get("fecha").asText()).startsWith(LocalDate.now().toString());
    }

    @Test
    @DisplayName("pagina como la consulta de ventas y el saldo de cada pagina sigue siendo el de ese momento")
    void paginaConSaldo() throws Exception {
        JsonNode pagina = getJson(url(articulo) + "?page=1&size=2", dueno);

        assertThat(pagina.get("page").asInt()).isEqualTo(1);
        assertThat(pagina.get("totalPages").asInt()).isEqualTo(2);
        assertThat(pagina.get("items").findValuesAsText("tipo")).containsExactly("REMITO", "VENTA");
        assertThat(pagina.get("items").findValues("saldo")).extracting(JsonNode::decimalValue)
                .usingElementComparator(java.math.BigDecimal::compareTo)
                .containsExactly(dec("7"), dec("8"));
    }

    @Test
    @DisplayName("filtra por Desde y Hasta (dias completos); el saldo sigue contando lo posterior a Hasta")
    void filtraPorFechas() throws Exception {
        String hoy = LocalDate.now().toString();
        assertThat(getJson(url(articulo) + "?desde=" + LocalDate.now().plusDays(1), dueno).get("totalElements").asLong())
                .isZero();
        assertThat(getJson(url(articulo) + "?desde=" + hoy + "&hasta=" + hoy, dueno).get("totalElements").asLong())
                .isEqualTo(4);
        jdbc.update("update movimiento_stock set fecha = fecha - interval 3 day where articulo_id = ? and tipo = 'VENTA'",
                articulo);
        JsonNode viejos = getJson(url(articulo) + "?hasta=" + LocalDate.now().minusDays(1), dueno);
        assertThat(viejos.get("items").findValuesAsText("tipo")).containsExactly("VENTA");
        assertThat(viejos.get("items").get(0).get("saldo").decimalValue()).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("un articulo de otra ferreteria o inexistente da 404, y el Vendedor recibe 403")
    void aislamientoYPermisos() throws Exception {
        Tienda otra = tiendaSinCaja();
        getResult(url(articulo), login(otra.emailDueno())).andExpect(status().isNotFound());
        getResult(url(999_999_999L), dueno).andExpect(status().isNotFound());
        getResult(url(articulo), login(tienda.emailVendedor())).andExpect(status().isForbidden());
        getResult(url(articulo), login("admin@demo.local")).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("un movimiento que apunta a comprobante, remito, compra o usuario de otra ferreteria no muestra nada ajeno")
    void referenciasAjenasNoSeFiltran() throws Exception {
        Tienda otra = tienda();
        String duenoOtra = login(otra.emailDueno());
        long ajeno = articulo(otra, "Ajeno", "UNIDAD", "100.00", "50");
        long ventaAjena = json(vender(duenoOtra, clave(), "100.00", linea(ajeno, "1", "100.00"))
                .andExpect(status().isCreated())).get("ventaId").asLong();
        long remitoAjeno = remito(duenoOtra, ajeno, "1", "100.00", "100.00").get("remitoId").asLong();
        jdbc.update("insert into proveedor (ferreteria_id, nombre, activo) values (?, 'Proveedor Ajeno', true)", otra.id());
        long proveedorAjeno = jdbc.queryForObject("select id from proveedor where ferreteria_id = ?", Long.class, otra.id());
        postJson("/api/compras", duenoOtra, """
                {"clave": "%s", "proveedorId": %d, "tipo": "FB", "puntoVenta": 7, "numero": 777, "fecha": "%s",
                 "renglones": [{"articuloId": %d, "cantidad": 5, "costoUnitario": 1.00}]}
                """.formatted(clave(), proveedorAjeno, LocalDate.now(), ajeno))
                .andExpect(status().isCreated());
        long compraAjena = jdbc.queryForObject("select id from compra where ferreteria_id = ?", Long.class, otra.id());
        long usuarioAjeno = jdbc.queryForObject("select id from usuario where email = ?", Long.class, otra.emailDueno());
        long propio = articulo(tienda, "Arandela", "UNIDAD", "10.00", "10");
        String insertar = "insert into movimiento_stock (ferreteria_id, articulo_id, tipo, cantidad, motivo, %s, usuario_id)"
                + " values (?, ?, 'AJUSTE', 1, 'x', ?, ?)";
        jdbc.update(insertar.formatted("comprobante_id"), tienda.id(), propio, ventaAjena, usuarioAjeno);
        jdbc.update(insertar.formatted("remito_id"), tienda.id(), propio, remitoAjeno, usuarioAjeno);
        jdbc.update(insertar.formatted("compra_id"), tienda.id(), propio, compraAjena, usuarioAjeno);

        JsonNode items = getJson(url(propio), dueno).get("items");

        assertThat(items).hasSize(3);
        assertThat(items.findValues("comprobante")).allMatch(JsonNode::isNull);
        assertThat(items.findValues("usuario")).allMatch(JsonNode::isNull);
    }

    @Test
    @DisplayName("un articulo sin movimientos devuelve la pagina vacia")
    void sinMovimientos() throws Exception {
        long nuevo = articulo(tienda, "Arandela", "UNIDAD", "10.00", "0");

        JsonNode pagina = getJson(url(nuevo), dueno);

        assertThat(pagina.get("totalElements").asLong()).isZero();
        assertThat(pagina.get("items")).isEmpty();
    }

    private static java.math.BigDecimal dec(String valor) {
        return new java.math.BigDecimal(valor);
    }
}
