package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.common.HoraArgentina;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CU-07 (G55, RM-002 y RM-005): listar, ver y anular los remitos, contra MySQL real. */
class RemitoConsultaIntegrationTest extends VentasTestBase {

    private ResultActions anular(String token, long id) throws Exception {
        return mvc.perform(patch("/api/remitos/" + id + "/anular").header("Authorization", "Bearer " + token));
    }

    private List<Long> numeros(JsonNode pagina) {
        List<Long> numeros = new ArrayList<>();
        pagina.get("items").forEach(i -> numeros.add(i.get("numero").asLong()));
        return numeros;
    }

    private String estado(long id) {
        return jdbc.queryForObject("select estado from remito where id = ?", String.class, id);
    }

    @Test
    @DisplayName("el listado trae los de la ferreteria, los mas nuevos primero, sin renglones, y pagina")
    void listaMasNuevosPrimeroYPagina() throws Exception {
        Tienda t = tiendaSinCaja();
        Tienda otra = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long b = articulo(otra, "Clavo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        for (int i = 0; i < 3; i++) {
            remito(token, a, "1", "100.00", "100.00");
        }
        remito(login(otra.emailVendedor()), b, "1", "100.00", "100.00");

        JsonNode todos = getJson("/api/remitos", token);
        JsonNode primera = getJson("/api/remitos?size=2", token);
        JsonNode segunda = getJson("/api/remitos?size=2&page=1", token);

        assertThat(numeros(todos)).containsExactly(3L, 2L, 1L);
        assertThat(todos.get("totalElements").asLong()).isEqualTo(3);
        assertThat(numeros(primera)).containsExactly(3L, 2L);
        assertThat(primera.get("totalPages").asInt()).isEqualTo(2);
        assertThat(numeros(segunda)).containsExactly(1L);
        JsonNode fila = todos.get("items").get(0);
        assertThat(fila.get("estado").asText()).isEqualTo("EMITIDO");
        assertThat(fila.get("total").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(fila.has("renglones")).isFalse();
    }

    @Test
    @DisplayName("filtra por estado y por numero")
    void filtraPorEstadoYNumero() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailDueno());
        long uno = remito(token, a, "1", "100.00", "100.00").get("remitoId").asLong();
        remito(token, a, "1", "100.00", "100.00");
        remito(token, a, "1", "100.00", "100.00");
        anular(token, uno).andExpect(status().isOk());

        assertThat(numeros(getJson("/api/remitos?estado=ANULADO", token))).containsExactly(1L);
        assertThat(numeros(getJson("/api/remitos?estado=EMITIDO", token))).containsExactly(3L, 2L);
        assertThat(numeros(getJson("/api/remitos?numero=2", token))).containsExactly(2L);
        assertThat(numeros(getJson("/api/remitos?numero=99", token))).isEmpty();
        getResult("/api/remitos?estado=VENCIDO", token).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("filtra por fecha desde y hasta, inclusive")
    void filtraPorFecha() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        long viejo = remito(token, a, "1", "100.00", "100.00").get("remitoId").asLong();
        remito(token, a, "1", "100.00", "100.00");
        LocalDate hoy = HoraArgentina.hoy();
        jdbc.update("update remito set fecha = ? where id = ?", hoy.minusDays(10).atTime(23, 59), viejo);

        assertThat(numeros(getJson("/api/remitos?desde=" + hoy, token))).containsExactly(2L);
        assertThat(numeros(getJson("/api/remitos?hasta=" + hoy.minusDays(10), token))).containsExactly(1L);
        assertThat(numeros(getJson("/api/remitos?desde=" + hoy.minusDays(10) + "&hasta=" + hoy, token)))
                .containsExactly(2L, 1L);
        assertThat(numeros(getJson("/api/remitos?desde=" + hoy.plusDays(1), token))).isEmpty();
        rangoAlRevesDa400("/api/remitos?desde=" + hoy + "&hasta=" + hoy.minusDays(1), token);
    }

    @Test
    @DisplayName("el detalle trae los renglones; uno de otra ferreteria o que no existe da 404")
    void detalle() throws Exception {
        Tienda t = tiendaSinCaja();
        Tienda otra = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        long id = remito(token, a, "2", "100.00", "200.00").get("remitoId").asLong();
        long ajeno = remito(login(otra.emailVendedor()), articulo(otra, "Clavo", "UNIDAD", "100.00", "10"), "1",
                "100.00", "100.00").get("remitoId").asLong();

        JsonNode remito = getJson("/api/remitos/" + id, token);

        assertThat(remito.get("numero").asLong()).isEqualTo(1);
        assertThat(remito.get("estado").asText()).isEqualTo("EMITIDO");
        assertThat(remito.get("renglones")).hasSize(1);
        assertThat(remito.get("renglones").get(0).get("descripcion").asText()).isEqualTo("Tornillo");
        assertThat(remito.get("renglones").get(0).get("cantidad").decimalValue()).isEqualByComparingTo("2");
        assertThat(remito.has("sinStock")).isFalse();
        getResult("/api/remitos/" + ajeno, token).andExpect(status().isNotFound());
        getResult("/api/remitos/999999999", token).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("el Dueno anula un EMITIDO: queda ANULADO, devuelve el stock y deja un movimiento ANULACION_REMITO por articulo")
    void anulaYDevuelveElStock() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long b = articulo(t, "Cano", "METRO", "50.00", "20");
        String duenio = login(t.emailDueno());
        Map<String, Object> pedido = new java.util.LinkedHashMap<>();
        pedido.put("clave", clave());
        pedido.put("renglones", List.of(
                Map.of("articuloId", a, "cantidad", new BigDecimal("3"), "precioUnitario", new BigDecimal("100.00")),
                Map.of("articuloId", b, "cantidad", new BigDecimal("2.5"), "precioUnitario", new BigDecimal("50.00"))));
        pedido.put("totalMostrado", new BigDecimal("425.00"));
        long id = json(postJson("/api/remitos", duenio, objectMapper.writeValueAsString(pedido))
                .andExpect(status().isCreated())).get("remitoId").asLong();
        assertThat(stock(a)).isEqualByComparingTo("7");
        assertThat(stock(b)).isEqualByComparingTo("17.5");

        JsonNode anulado = json(anular(duenio, id).andExpect(status().isOk()));

        assertThat(anulado.get("estado").asText()).isEqualTo("ANULADO");
        assertThat(anulado.get("renglones")).hasSize(2);
        assertThat(estado(id)).isEqualTo("ANULADO");
        assertThat(stock(a)).isEqualByComparingTo("10");
        assertThat(stock(b)).isEqualByComparingTo("20");
        List<Map<String, Object>> devueltos = jdbc.queryForList("select articulo_id, cantidad, remito_id, comprobante_id "
                + "from movimiento_stock where tipo = 'ANULACION_REMITO' and ferreteria_id = ? order by articulo_id",
                t.id());
        assertThat(devueltos).hasSize(2);
        assertThat((BigDecimal) devueltos.get(0).get("cantidad")).isEqualByComparingTo("3");
        assertThat((BigDecimal) devueltos.get(1).get("cantidad")).isEqualByComparingTo("2.5");
        assertThat(devueltos).allSatisfy(m -> {
            assertThat(((Number) m.get("remito_id")).longValue()).isEqualTo(id);
            assertThat(m.get("comprobante_id")).isNull();
        });
        assertThat(movimientos(t)).isEqualTo(4);
    }

    @Test
    @DisplayName("el remito anulado dice quien lo anulo y cuando, en la anulacion y en el detalle; repetirla no lo cambia (#81)")
    void anuladoDiceQuienYCuando() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String vendedor = login(t.emailVendedor());
        String duenio = login(t.emailDueno());
        long id = remito(vendedor, a, "2", "100.00", "200.00").get("remitoId").asLong();
        assertThat(getJson("/api/remitos/" + id, duenio).has("anuladoPor")).isFalse();
        assertThat(getJson("/api/remitos/" + id, duenio).has("fechaAnulacion")).isFalse();

        LocalDateTime antes = HoraArgentina.ahora().minusSeconds(1);
        JsonNode anulado = json(anular(duenio, id).andExpect(status().isOk()));
        JsonNode detalle = getJson("/api/remitos/" + id, vendedor);
        JsonNode repetida = json(anular(duenio, id).andExpect(status().isOk()));

        for (JsonNode r : List.of(anulado, detalle, repetida)) {
            assertThat(r.get("anuladoPor").asText()).isEqualTo("Dueno Ventas");
            assertThat(r.get("usuario").asText()).isEqualTo("Vendedor Ventas");
            assertThat(LocalDateTime.parse(r.get("fechaAnulacion").asText())).isAfter(antes);
        }
        assertThat(repetida.get("fechaAnulacion").asText()).isEqualTo(anulado.get("fechaAnulacion").asText());
        assertThat(jdbc.queryForObject("select usuario_anulacion_id from remito where id = ?", Long.class, id))
                .isNotNull();
    }

    @Test
    @DisplayName("otro Dueno que repite la anulacion no pisa quien ni cuando la hizo (#81)")
    void otroDuenoNoPisaQuienAnulo() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String duenio = login(t.emailDueno());
        long id = remito(duenio, a, "2", "100.00", "200.00").get("remitoId").asLong();
        String hash = jdbc.queryForObject("select password_hash from usuario where email = ?", String.class,
                t.emailDueno());
        String otro = "dueno2-" + t.id() + "@ventas.demo";
        jdbc.update("insert into usuario (ferreteria_id, email, password_hash, nombre, rol) "
                + "values (?, ?, ?, 'Otra Duena', 'DUENO')", t.id(), otro, hash);
        long primerDueno = jdbc.queryForObject("select id from usuario where email = ?", Long.class, t.emailDueno());

        JsonNode primera = json(anular(duenio, id).andExpect(status().isOk()));
        JsonNode segunda = json(anular(login(otro), id).andExpect(status().isOk()));

        assertThat(segunda.get("anuladoPor").asText()).isEqualTo("Dueno Ventas");
        assertThat(segunda.get("fechaAnulacion").asText()).isEqualTo(primera.get("fechaAnulacion").asText());
        assertThat(anulacion(id)).containsEntry("usuario_anulacion_id", primerDueno);
        assertThat(stock(a)).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("un facturado (409) o un remito de otra ferreteria (404) no guardan quien ni cuando (#81)")
    void sinAnulacionNoGuardaQuienNiCuando() throws Exception {
        Tienda t = tiendaSinCaja();
        Tienda otra = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String duenio = login(t.emailDueno());
        long ajeno = remito(duenio, a, "2", "100.00", "200.00").get("remitoId").asLong();
        long facturado = remito(duenio, a, "2", "100.00", "200.00").get("remitoId").asLong();
        jdbc.update("update remito set estado = 'FACTURADO' where id = ?", facturado);

        anular(login(otra.emailDueno()), ajeno).andExpect(status().isNotFound());
        anular(duenio, facturado).andExpect(status().isConflict());

        for (long id : new long[] {ajeno, facturado}) {
            assertThat(anulacion(id)).containsEntry("fecha_anulacion", null).containsEntry("usuario_anulacion_id", null);
        }
        assertThat(estado(ajeno)).isEqualTo("EMITIDO");
        anular(duenio, ajeno).andExpect(status().isOk());
        getResult("/api/remitos/" + ajeno, login(otra.emailDueno())).andExpect(status().isNotFound());
    }

    /** Quien y cuando quedaron guardados en la fila del remito (V47). */
    private Map<String, Object> anulacion(long id) {
        Map<String, Object> fila = jdbc.queryForMap("select fecha_anulacion, usuario_anulacion_id from remito where id = ?", id);
        Map<String, Object> normalizada = new java.util.HashMap<>(fila);
        if (fila.get("usuario_anulacion_id") instanceof Number n) {
            normalizada.put("usuario_anulacion_id", n.longValue());
        }
        return normalizada;
    }

    @Test
    @DisplayName("anular un remito de un articulo sin stock lo deja como estaba antes de emitirlo")
    void anularCompensaElStockNegativo() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "1");
        String token = login(t.emailDueno());
        long id = remito(token, a, "5", "100.00", "500.00").get("remitoId").asLong();
        assertThat(stock(a)).isEqualByComparingTo("-4");

        anular(token, id).andExpect(status().isOk());

        assertThat(stock(a)).isEqualByComparingTo("1");
    }

    @Test
    @DisplayName("anular un remito cuyo articulo se dio de baja devuelve el stock igual")
    void anularConArticuloDadoDeBaja() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailDueno());
        long id = remito(token, a, "2", "100.00", "200.00").get("remitoId").asLong();
        jdbc.update("update articulo set activo = false where id = ?", a);

        anular(token, id).andExpect(status().isOk());

        assertThat(stock(a)).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("repetir la anulacion devuelve el remito ANULADO (200) sin devolver el stock otra vez")
    void anularDosVeces() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailDueno());
        long id = remito(token, a, "2", "100.00", "200.00").get("remitoId").asLong();

        anular(token, id).andExpect(status().isOk());
        JsonNode repetida = json(anular(token, id).andExpect(status().isOk()));

        assertThat(repetida.get("estado").asText()).isEqualTo("ANULADO");
        assertThat(stock(a)).isEqualByComparingTo("10");
        assertThat(movimientos(t)).isEqualTo(2);
    }

    @Test
    @DisplayName("el vendedor no anula: 403 y no cambia nada")
    void vendedorNoAnula() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String vendedor = login(t.emailVendedor());
        long id = remito(vendedor, a, "2", "100.00", "200.00").get("remitoId").asLong();

        anular(vendedor, id).andExpect(status().isForbidden());

        assertThat(estado(id)).isEqualTo("EMITIDO");
        assertThat(stock(a)).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("uno ya facturado no se anula: 409; uno de otra ferreteria o que no existe, 404")
    void facturadoOAjeno() throws Exception {
        Tienda t = tiendaSinCaja();
        Tienda otra = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailDueno());
        long id = remito(token, a, "2", "100.00", "200.00").get("remitoId").asLong();
        jdbc.update("update remito set estado = 'FACTURADO' where id = ?", id);

        JsonNode conflicto = json(anular(token, id).andExpect(status().isConflict()));
        anular(login(otra.emailDueno()), id).andExpect(status().isNotFound());
        anular(token, 999999999L).andExpect(status().isNotFound());

        assertThat(conflicto.get("message").asText()).isEqualTo("Ese remito ya se facturó.");
        assertThat(estado(id)).isEqualTo("FACTURADO");
        assertThat(stock(a)).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("sin sesion, listar, ver y anular dan 401")
    void sinSesion() throws Exception {
        mvc.perform(get("/api/remitos")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/remitos/1")).andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/remitos/1/anular")).andExpect(status().isUnauthorized());
    }
}
