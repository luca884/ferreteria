package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GET /api/articulos/{id} (cu-13-factura-pdf, S5): la ficha del Dueño trae la ultima compra del articulo (id, texto del
 * comprobante y fecha) para llevar al detalle; el Vendedor no ve nada de compras y nada se mezcla entre ferreterias.
 * CUIT 30-79091011-0 y 30-79091012-1.
 */
class UltimaCompraArticuloIntegrationTest extends AjustesTestBase {

    private Long a;
    private Long b;
    private String duenoA;
    private String vendedorA;

    @BeforeEach
    void sembrar() throws Exception {
        a = ferreteria("30-79091011-0", "Ultima compra A");
        b = ferreteria("30-79091012-1", "Ultima compra B");
        borrarCompras(a, b);
        borrarDatosDe(a, b);
        duenoA = login(usuario(a, "dueno-ultima-a@test.local", "DUENO"));
        vendedorA = login(usuario(a, "vendedor-ultima-a@test.local", "VENDEDOR"));
        usuario(b, "dueno-ultima-b@test.local", "DUENO");
    }

    @AfterEach
    void limpiar() {
        borrarCompras(a, b);
        borrarDatosDe(a, b);
    }

    private void borrarCompras(Long... ferreterias) {
        for (Long id : ferreterias) {
            jdbc.update("delete from item_compra where ferreteria_id = ?", id);
            jdbc.update("delete from compra where ferreteria_id = ?", id);
        }
    }

    /** Una factura A con un renglon del articulo, cargada por SQL; devuelve el id de la compra. */
    private Long compra(Long ferreteriaId, long numero, String fecha, Long articuloId) {
        Long proveedor = proveedor(ferreteriaId, "Proveedor " + numero);
        Long usuario = jdbc.queryForObject("select min(id) from usuario where ferreteria_id = ?", Long.class, ferreteriaId);
        jdbc.update("insert into compra (ferreteria_id, proveedor_id, proveedor_nombre, tipo, punto_venta, numero, fecha,"
                + " moneda, neto, clave_idempotencia, usuario_id) values (?, ?, ?, 'FA', 13, ?, ?, 'ARS', 100, ?, ?)",
                ferreteriaId, proveedor, "Proveedor " + numero, numero, java.sql.Date.valueOf(fecha),
                java.util.UUID.randomUUID().toString(), usuario);
        Long compraId = jdbc.queryForObject("select id from compra where ferreteria_id = ? and numero = ?",
                Long.class, ferreteriaId, numero);
        jdbc.update("insert into item_compra (ferreteria_id, compra_id, renglon, articulo_id, codigo_interno, descripcion,"
                + " unidad, cantidad, costo_unitario, subtotal, costo_anterior, precio_anterior, precio_nuevo)"
                + " values (?, ?, 1, ?, 'X', 'X', 'UNIDAD', 1, 100, 100, 100, 160, 160)", ferreteriaId, compraId, articuloId);
        return compraId;
    }

    @Test
    @DisplayName("S5: el Dueño ve la ultima compra con su id, el comprobante y la fecha; sin compras, null")
    void duenoVeLaUltimaCompra() throws Exception {
        Long comprado = articulo(a, "UC-001", "ARS", "100.00", "160.00", true);
        Long sinCompras = articulo(a, "UC-002", "ARS", "100.00", "160.00", true);
        compra(a, 100, "2026-08-01", comprado);
        Long ultima = compra(a, 605684, "2026-09-24", comprado);

        JsonNode ficha = getJson("/api/articulos/" + comprado, duenoA);

        JsonNode compra = ficha.get("ultimaCompra");
        assertThat(compra.get("compraId").asLong()).isEqualTo(ultima);
        assertThat(compra.get("comprobante").asText()).isEqualTo("Factura A 00013-00605684");
        assertThat(compra.get("fecha").asText()).isEqualTo("2026-09-24");
        assertThat(getJson("/api/articulos/" + sinCompras, duenoA).get("ultimaCompra").isNull()).isTrue();
    }

    @Test
    @DisplayName("S5: el Vendedor no recibe nada de compras")
    void vendedorNoVeCompras() throws Exception {
        Long comprado = articulo(a, "UC-003", "ARS", "100.00", "160.00", true);
        compra(a, 101, "2026-09-24", comprado);

        JsonNode ficha = getJson("/api/articulos/" + comprado, vendedorA);

        assertThat(ficha.has("ultimaCompra")).isFalse();
    }

    @Test
    @DisplayName("S5: las compras de otra ferreteria no aparecen en la ficha")
    void aislada() throws Exception {
        Long propio = articulo(a, "UC-004", "ARS", "100.00", "160.00", true);
        Long ajeno = articulo(b, "UC-005", "ARS", "100.00", "160.00", true);
        compra(b, 102, "2026-10-30", ajeno);

        assertThat(getJson("/api/articulos/" + propio, duenoA).get("ultimaCompra").isNull()).isTrue();
        assertThat(getJson("/api/articulos/" + ajeno, login("dueno-ultima-b@test.local")).get("ultimaCompra")
                .get("comprobante").asText()).isEqualTo("Factura A 00013-00000102");
    }
}
