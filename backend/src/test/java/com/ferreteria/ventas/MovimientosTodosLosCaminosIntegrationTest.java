package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.common.HoraArgentina;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * «Movimientos de stock» (CU-09 punto 3) ordena por id, el orden en que cada movimiento se aplico al stock. Todos los
 * caminos que mueven stock insertan el movimiento en la misma transaccion que actualiza el articulo, asi que con datos
 * normales el orden por id coincide con el de la fecha y el saldo hacia atras cuadra en cada camino.
 */
class MovimientosTodosLosCaminosIntegrationTest extends VentasTestBase {

    private String dueno;
    private long articulo;

    private static String url(long id) {
        return "/api/articulos/" + id + "/movimientos-stock";
    }

    /** Stock 10 y un movimiento de cada camino: venta, remito, anulacion de remito, factura de remito, NC interna, compra y ajuste. */
    @BeforeEach
    void todosLosCaminos() throws Exception {
        Tienda tienda = tienda();
        dueno = login(tienda.emailDueno());
        articulo = articulo(tienda, "Caminos", "UNIDAD", "100.00", "10");
        vender(dueno, clave(), "200.00", linea(articulo, "2", "100.00")).andExpect(status().isCreated());
        long remitoA = remito(dueno, articulo, "1", "100.00", "100.00").get("remitoId").asLong();
        mvc.perform(patch("/api/remitos/" + remitoA + "/anular").header("Authorization", "Bearer " + dueno))
                .andExpect(status().isOk());
        long remitoB = remito(dueno, articulo, "3", "100.00", "300.00").get("remitoId").asLong();
        Map<String, Object> deRemito = mapa(clave(), null, "400.00", linea(articulo, "4", "100.00"));
        deRemito.put("formaPagoId", formaDePago(dueno, "EFECTIVO"));
        deRemito.put("remitoId", remitoB);
        postJson("/api/ventas", dueno, objectMapper.writeValueAsString(deRemito)).andExpect(status().isCreated());
        long ventaC = json(vender(dueno, clave(), "100.00", linea(articulo, "1", "100.00"))
                .andExpect(status().isCreated())).get("ventaId").asLong();
        postJson("/api/ventas/" + ventaC + "/anular", dueno, "{\"motivo\":\"devolvio\"}")
                .andExpect(status().is2xxSuccessful());
        jdbc.update("insert into proveedor (ferreteria_id, nombre, activo) values (?, 'Prov Caminos', true)", tienda.id());
        long proveedor = jdbc.queryForObject("select id from proveedor where ferreteria_id = ?", Long.class, tienda.id());
        postJson("/api/compras", dueno, """
                {"clave": "%s", "proveedorId": %d, "tipo": "FA", "puntoVenta": 3, "numero": 77, "fecha": "%s",
                 "renglones": [{"articuloId": %d, "cantidad": 5, "costoUnitario": 1.00}]}
                """.formatted(clave(), proveedor, HoraArgentina.hoy().minusDays(1), articulo))
                .andExpect(status().isCreated());
        postJson("/api/articulos/" + articulo + "/ajustes-stock", dueno, "{\"stockReal\": 7, \"motivo\": \"ROTURA\"}")
                .andExpect(status().isCreated());
    }

    private static List<String> resumen(Iterable<JsonNode> items) {
        List<String> filas = new ArrayList<>();
        for (JsonNode i : items) {
            filas.add(i.get("tipo").asText() + " " + i.get("cantidad").decimalValue().stripTrailingZeros().toPlainString()
                    + " -> " + i.get("saldo").decimalValue().stripTrailingZeros().toPlainString());
        }
        return filas;
    }

    private List<JsonNode> paginas(String query, int size) throws Exception {
        List<JsonNode> todo = new ArrayList<>();
        for (int p = 0; ; p++) {
            JsonNode pagina = getJson(url(articulo) + "?size=" + size + "&page=" + p + query, dueno);
            pagina.get("items").forEach(todo::add);
            if (p + 1 >= pagina.get("totalPages").asInt()) {
                return todo;
            }
        }
    }

    @Test
    @DisplayName("todos los caminos: orden de alta, saldos encadenados, fechas no crecientes, igual con paginas y filtro")
    void todosLosCaminosCuadran() throws Exception {
        JsonNode items = getJson(url(articulo), dueno).get("items");

        assertThat(resumen(items)).containsExactly(
                "AJUSTE -2 -> 7", "COMPRA 5 -> 9", "NOTA_CREDITO 1 -> 4", "VENTA -1 -> 3", "VENTA -1 -> 4",
                "REMITO -3 -> 5", "ANULACION_REMITO 1 -> 8", "REMITO -1 -> 7", "VENTA -2 -> 8");
        BigDecimal saldo = stock(articulo);
        long idAnterior = Long.MAX_VALUE;
        LocalDateTime fechaAnterior = LocalDateTime.MAX;
        for (JsonNode i : items) {
            assertThat(i.get("saldo").decimalValue()).isEqualByComparingTo(saldo);
            saldo = saldo.subtract(i.get("cantidad").decimalValue());
            assertThat(i.get("id").asLong()).isLessThan(idAnterior);
            LocalDateTime fecha = LocalDateTime.parse(i.get("fecha").asText());
            assertThat(fecha).isBeforeOrEqualTo(fechaAnterior);
            idAnterior = i.get("id").asLong();
            fechaAnterior = fecha;
        }
        assertThat(saldo).isEqualByComparingTo("10");
        String hoy = HoraArgentina.hoy().toString();
        for (int size : new int[] {1, 2, 4}) {
            assertThat(resumen(paginas("", size))).as("size=" + size).isEqualTo(resumen(items));
            assertThat(resumen(paginas("&desde=" + hoy + "&hasta=" + hoy, size))).as("filtro hoy, size=" + size)
                    .isEqualTo(resumen(items));
        }
    }
}
