package com.ferreteria.catalogo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GET /api/articulos/bajo-minimo (CU-09 punto 1, G72): el «Listado de Artículos bajo Stock Mínimo» de GDS. Solo los
 * activos con stock menor al mínimo (y mínimo cargado), con cuánto reponer, ordenados como pide el Dueño, y nada de otra
 * ferreteria (regla 7). CUIT 30-79091001-1 y 30-79091002-2.
 */
class StockBajoMinimoIntegrationTest extends AjustesTestBase {

    private static final String URL = "/api/articulos/bajo-minimo";

    private Long a;
    private Long b;
    private String duenoA;

    @BeforeEach
    void sembrar() throws Exception {
        a = ferreteria("30-79091001-1", "Minimo A");
        b = ferreteria("30-79091002-2", "Minimo B");
        borrarCompras(a, b);
        borrarDatosDe(a, b);
        duenoA = login(usuario(a, "dueno-minimo-a@test.local", "DUENO"));
        usuario(a, "vendedor-minimo-a@test.local", "VENDEDOR");
        usuario(b, "dueno-minimo-b@test.local", "DUENO");

        Long bulones = rubro(a, "Bulones", "30");
        Long pinturas = rubro(a, "Pinturas", "30");
        Long sur = proveedor(a, "Bulonera Sur");
        Long norte = proveedor(a, "Andina Norte");
        cargar(a, "B-002", "Tuerca 1/4", "UNIDAD", "2", "10", bulones, sur);
        cargar(a, "A-001", "Cable 2,5 mm", "METRO", "12.5", "50", pinturas, norte);
        cargar(a, "C-003", "Arandela", "UNIDAD", "-3", "5", null, null);
        // No salen: en el minimo justo, sin minimo cargado (aunque falte stock), dado de baja, y de otra ferreteria.
        cargar(a, "D-004", "Clavo justo", "UNIDAD", "5", "5", bulones, sur);
        cargar(a, "E-005", "Sin minimo", "UNIDAD", "-1", "0", bulones, sur);
        Long baja = cargar(a, "F-006", "Dado de baja", "UNIDAD", "0", "4", bulones, sur);
        jdbc.update("update articulo set activo = false where id = ?", baja);
        cargar(b, "G-007", "Ajeno", "UNIDAD", "0", "9", null, null);
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

    private Long idDe(Long ferreteriaId, String codigo) {
        return jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?", Long.class,
                ferreteriaId, codigo);
    }

    /** Una compra (factura A) con un renglón por artículo, cargada por SQL con la fecha del comprobante. */
    private void compra(Long ferreteriaId, Long proveedorId, String proveedorNombre, long numero, String fecha,
                        Long... articulos) {
        Long usuario = usuarioDe(ferreteriaId);
        jdbc.update("insert into compra (ferreteria_id, proveedor_id, proveedor_nombre, tipo, punto_venta, numero, fecha,"
                + " moneda, neto, clave_idempotencia, usuario_id) values (?, ?, ?, 'FA', 1, ?, ?, 'ARS', 100, ?, ?)",
                ferreteriaId, proveedorId, proveedorNombre, numero, java.sql.Date.valueOf(fecha),
                java.util.UUID.randomUUID().toString(), usuario);
        Long compraId = jdbc.queryForObject("select id from compra where ferreteria_id = ? and numero = ? and proveedor_id = ?",
                Long.class, ferreteriaId, numero, proveedorId);
        int renglon = 1;
        for (Long articulo : articulos) {
            jdbc.update("insert into item_compra (ferreteria_id, compra_id, renglon, articulo_id, codigo_interno, descripcion,"
                    + " unidad, cantidad, costo_unitario, subtotal, costo_anterior, precio_anterior, precio_nuevo)"
                    + " values (?, ?, ?, ?, 'X', 'X', 'UNIDAD', 1, 100, 100, 100, 160, 160)",
                    ferreteriaId, compraId, renglon++, articulo);
        }
    }

    private Long usuarioDe(Long ferreteriaId) {
        return jdbc.queryForObject("select min(id) from usuario where ferreteria_id = ?", Long.class, ferreteriaId);
    }

    @Test
    @DisplayName("bloque 2, S5: cada fila trae el codigo del proveedor del articulo (null si no tiene)")
    void codigoDelProveedor() throws Exception {
        jdbc.update("update articulo set codigo_proveedor = 'BS-4471' where id = ?", idDe(a, "B-002"));

        JsonNode filas = getJson(URL, duenoA);

        assertThat(filas.get(2).get("codigoInterno").asText()).isEqualTo("B-002");
        assertThat(filas.get(2).get("codigoProveedor").asText()).isEqualTo("BS-4471");
        assertThat(filas.get(0).get("codigoProveedor").isNull()).isTrue();
    }

    @Test
    @DisplayName("bloque 2, S6: cada fila trae la fecha y el proveedor de la ultima compra del articulo; sin compras, null")
    void ultimaCompra() throws Exception {
        Long sur = jdbc.queryForObject("select id from proveedor where ferreteria_id = ? and nombre = 'Bulonera Sur'",
                Long.class, a);
        Long norte = jdbc.queryForObject("select id from proveedor where ferreteria_id = ? and nombre = 'Andina Norte'",
                Long.class, a);
        Long tuerca = idDe(a, "B-002");
        Long cable = idDe(a, "A-001");
        compra(a, sur, "Bulonera Sur", 10, "2026-09-01", tuerca, cable);
        compra(a, norte, "Andina Norte", 11, "2026-10-12", tuerca);
        // Cargada despues pero con un comprobante mas viejo: la ultima es la de fecha mas nueva.
        compra(a, sur, "Bulonera Sur", 12, "2026-08-20", tuerca);

        JsonNode filas = getJson(URL, duenoA);

        JsonNode filaTuerca = filas.get(2);
        assertThat(filaTuerca.get("codigoInterno").asText()).isEqualTo("B-002");
        assertThat(filaTuerca.get("ultimaCompraFecha").asText()).isEqualTo("2026-10-12");
        assertThat(filaTuerca.get("ultimaCompraProveedor").asText()).isEqualTo("Andina Norte");
        JsonNode filaCable = filas.get(1);
        assertThat(filaCable.get("ultimaCompraFecha").asText()).isEqualTo("2026-09-01");
        assertThat(filaCable.get("ultimaCompraProveedor").asText()).isEqualTo("Bulonera Sur");
        JsonNode arandela = filas.get(0);
        assertThat(arandela.get("ultimaCompraFecha").isNull()).isTrue();
        assertThat(arandela.get("ultimaCompraProveedor").isNull()).isTrue();
    }

    @Test
    @DisplayName("bloque 2, S6: la ultima compra sale solo de las compras de la ferreteria que pide")
    void ultimaCompraAislada() throws Exception {
        Long ajeno = idDe(b, "G-007");
        Long proveedorB = proveedor(b, "Proveedor de B");
        compra(b, proveedorB, "Proveedor de B", 20, "2026-10-30", ajeno);

        JsonNode deA = getJson(URL, duenoA);
        JsonNode deB = getJson(URL, login("dueno-minimo-b@test.local"));

        assertThat(deA.findValues("ultimaCompraProveedor")).isNotEmpty().allMatch(JsonNode::isNull);
        assertThat(deB.get(0).get("ultimaCompraProveedor").asText()).isEqualTo("Proveedor de B");
    }

    private Long cargar(Long ferreteriaId, String codigo, String descripcion, String unidad, String stock, String minimo,
                        Long rubroId, Long proveedorId) {
        Long id = articulo(ferreteriaId, codigo, "ARS", "100.00", "160.00", true);
        jdbc.update("update articulo set descripcion = ?, unidad = ?, stock_actual = ?, stock_minimo = ?, rubro_id = ?,"
                + " proveedor_id = ? where id = ?", descripcion, unidad, bd(stock), bd(minimo), rubroId, proveedorId, id);
        return id;
    }

    @Test
    @DisplayName("lista los activos propios bajo el minimo, por descripcion, con cuanto reponer")
    void listaBajoMinimo() throws Exception {
        JsonNode filas = getJson(URL, duenoA);

        assertThat(filas.findValuesAsText("codigoInterno")).containsExactly("C-003", "A-001", "B-002");
        JsonNode cable = filas.get(1);
        assertThat(cable.get("descripcion").asText()).isEqualTo("Cable 2,5 mm");
        assertThat(cable.get("rubro").asText()).isEqualTo("Pinturas");
        assertThat(cable.get("proveedor").asText()).isEqualTo("Andina Norte");
        assertThat(cable.get("costo").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(cable.get("moneda").asText()).isEqualTo("ARS");
        assertThat(cable.get("unidad").asText()).isEqualTo("METRO");
        assertThat(cable.get("stockActual").decimalValue()).isEqualByComparingTo("12.5");
        assertThat(cable.get("stockMinimo").decimalValue()).isEqualByComparingTo("50");
        assertThat(cable.get("aReponer").decimalValue()).isEqualByComparingTo("37.5");
        JsonNode arandela = filas.get(0);
        assertThat(arandela.get("rubro").isNull()).isTrue();
        assertThat(arandela.get("proveedor").isNull()).isTrue();
        assertThat(arandela.get("aReponer").decimalValue()).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("ordena por codigo, rubro o proveedor como el dialogo de GDS; sin rubro o proveedor va al final")
    void ordena() throws Exception {
        assertThat(getJson(URL + "?orden=DESCRIPCION", duenoA).findValuesAsText("codigoInterno"))
                .containsExactly("C-003", "A-001", "B-002");
        assertThat(getJson(URL + "?orden=CODIGO", duenoA).findValuesAsText("codigoInterno"))
                .containsExactly("A-001", "B-002", "C-003");
        assertThat(getJson(URL + "?orden=RUBRO", duenoA).findValuesAsText("codigoInterno"))
                .containsExactly("B-002", "A-001", "C-003");
        assertThat(getJson(URL + "?orden=PROVEEDOR", duenoA).findValuesAsText("codigoInterno"))
                .containsExactly("A-001", "B-002", "C-003");
    }

    @Test
    @DisplayName("otra ferreteria solo ve lo suyo; el Vendedor y el admin reciben 403; un orden que no existe da 400")
    void aislamientoYPermisos() throws Exception {
        assertThat(getJson(URL, login("dueno-minimo-b@test.local")).findValuesAsText("codigoInterno"))
                .containsExactly("G-007");
        getResult(URL, login("vendedor-minimo-a@test.local")).andExpect(status().isForbidden());
        getResult(URL, login("admin@demo.local")).andExpect(status().isForbidden());
        getResult(URL + "?orden=MARCA", duenoA).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("sin articulos bajo el minimo devuelve la lista vacia")
    void vacio() throws Exception {
        jdbc.update("update articulo set stock_minimo = 0 where ferreteria_id = ?", a);

        assertThat(getJson(URL, duenoA)).isEmpty();
    }
}
