package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.common.HoraArgentina;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CU-07 (GDS-13, PP-002 y PP-005): listar, ver y cambiar el estado de los presupuestos, contra MySQL real. */
class PresupuestoConsultaIntegrationTest extends VentasTestBase {

    private ResultActions cambiarEstado(String token, long id, String estado) throws Exception {
        String cuerpo = estado == null ? "{}" : "{\"estado\":\"" + estado + "\"}";
        return mvc.perform(patch("/api/presupuestos/" + id + "/estado").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo));
    }

    private List<Long> numeros(JsonNode pagina) {
        List<Long> numeros = new ArrayList<>();
        pagina.get("items").forEach(i -> numeros.add(i.get("numero").asLong()));
        return numeros;
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
            presupuesto(token, a, "1", "100.00", "100.00");
        }
        presupuesto(login(otra.emailVendedor()), b, "1", "100.00", "100.00");

        JsonNode todos = getJson("/api/presupuestos", token);
        JsonNode primera = getJson("/api/presupuestos?size=2", token);
        JsonNode segunda = getJson("/api/presupuestos?size=2&page=1", token);

        assertThat(numeros(todos)).containsExactly(3L, 2L, 1L);
        assertThat(todos.get("totalElements").asLong()).isEqualTo(3);
        assertThat(numeros(primera)).containsExactly(3L, 2L);
        assertThat(primera.get("totalPages").asInt()).isEqualTo(2);
        assertThat(numeros(segunda)).containsExactly(1L);
        JsonNode fila = todos.get("items").get(0);
        assertThat(fila.get("estado").asText()).isEqualTo("PENDIENTE");
        assertThat(fila.get("total").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(fila.has("vence")).isTrue();
        assertThat(fila.has("renglones")).isFalse();
    }

    @Test
    @DisplayName("filtra por estado y por numero")
    void filtraPorEstadoYNumero() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailDueno());
        long uno = presupuesto(token, a, "1", "100.00", "100.00").get("presupuestoId").asLong();
        presupuesto(token, a, "1", "100.00", "100.00");
        presupuesto(token, a, "1", "100.00", "100.00");
        cambiarEstado(token, uno, "ACEPTADO").andExpect(status().isOk());

        assertThat(numeros(getJson("/api/presupuestos?estado=ACEPTADO", token))).containsExactly(1L);
        assertThat(numeros(getJson("/api/presupuestos?estado=PENDIENTE", token))).containsExactly(3L, 2L);
        assertThat(numeros(getJson("/api/presupuestos?numero=2", token))).containsExactly(2L);
        assertThat(numeros(getJson("/api/presupuestos?numero=99", token))).isEmpty();
        getResult("/api/presupuestos?estado=VENCIDO", token).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("filtra por fecha desde y hasta, inclusive")
    void filtraPorFecha() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        long viejo = presupuesto(token, a, "1", "100.00", "100.00").get("presupuestoId").asLong();
        presupuesto(token, a, "1", "100.00", "100.00");
        LocalDate hoy = HoraArgentina.hoy();
        jdbc.update("update presupuesto set fecha = ? where id = ?", hoy.minusDays(10).atTime(23, 59), viejo);

        assertThat(numeros(getJson("/api/presupuestos?desde=" + hoy, token))).containsExactly(2L);
        assertThat(numeros(getJson("/api/presupuestos?hasta=" + hoy.minusDays(10), token))).containsExactly(1L);
        assertThat(numeros(getJson("/api/presupuestos?desde=" + hoy.minusDays(10) + "&hasta=" + hoy, token)))
                .containsExactly(2L, 1L);
        assertThat(numeros(getJson("/api/presupuestos?desde=" + hoy.plusDays(1), token))).isEmpty();
        rangoAlRevesDa400("/api/presupuestos?desde=" + hoy + "&hasta=" + hoy.minusDays(1), token);
    }

    @Test
    @DisplayName("un pendiente o aceptado cuyo vencimiento paso sale vencido; uno rechazado no; el estado guardado no cambia")
    void vencido() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        long pendiente = presupuesto(token, a, "1", "100.00", "100.00").get("presupuestoId").asLong();
        long rechazado = presupuesto(token, a, "1", "100.00", "100.00").get("presupuestoId").asLong();
        long hoyVence = presupuesto(token, a, "1", "100.00", "100.00").get("presupuestoId").asLong();
        cambiarEstado(token, rechazado, "RECHAZADO").andExpect(status().isOk());
        jdbc.update("update presupuesto set vence = ? where id in (?, ?)", HoraArgentina.hoy().minusDays(1), pendiente,
                rechazado);
        jdbc.update("update presupuesto set vence = ? where id = ?", HoraArgentina.hoy(), hoyVence);

        JsonNode lista = getJson("/api/presupuestos", token);

        assertThat(lista.get("items").get(0).get("vencido").asBoolean()).as("vence hoy: todavia vale").isFalse();
        assertThat(lista.get("items").get(1).get("vencido").asBoolean()).as("rechazado").isFalse();
        assertThat(lista.get("items").get(2).get("vencido").asBoolean()).as("pendiente que paso").isTrue();
        assertThat(getJson("/api/presupuestos/" + pendiente, token).get("vencido").asBoolean()).isTrue();
        assertThat(jdbc.queryForObject("select estado from presupuesto where id = ?", String.class, pendiente))
                .isEqualTo("PENDIENTE");
    }

    @Test
    @DisplayName("el detalle trae los renglones; uno de otra ferreteria o que no existe da 404")
    void detalle() throws Exception {
        Tienda t = tiendaSinCaja();
        Tienda otra = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        long id = presupuesto(token, a, "2", "100.00", "200.00").get("presupuestoId").asLong();
        long ajeno = presupuesto(login(otra.emailVendedor()), articulo(otra, "Clavo", "UNIDAD", "100.00", "10"), "1",
                "100.00", "100.00").get("presupuestoId").asLong();

        JsonNode presupuesto = getJson("/api/presupuestos/" + id, token);

        assertThat(presupuesto.get("numero").asLong()).isEqualTo(1);
        assertThat(presupuesto.get("renglones")).hasSize(1);
        assertThat(presupuesto.get("renglones").get(0).get("descripcion").asText()).isEqualTo("Tornillo");
        assertThat(presupuesto.get("renglones").get(0).get("cantidad").decimalValue()).isEqualByComparingTo("2");
        getResult("/api/presupuestos/" + ajeno, token).andExpect(status().isNotFound());
        getResult("/api/presupuestos/999999999", token).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("el estado va y viene entre pendiente, aceptado y rechazado; repetirlo no da error y no toca stock")
    void cambiaDeEstado() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String vendedor = login(t.emailVendedor());
        long id = presupuesto(vendedor, a, "1", "100.00", "100.00").get("presupuestoId").asLong();

        JsonNode aceptado = json(cambiarEstado(vendedor, id, "ACEPTADO").andExpect(status().isOk()));
        JsonNode repetido = json(cambiarEstado(vendedor, id, "ACEPTADO").andExpect(status().isOk()));
        String dueno = login(t.emailDueno());
        JsonNode rechazado = json(cambiarEstado(dueno, id, "RECHAZADO").andExpect(status().isOk()));
        JsonNode pendiente = json(cambiarEstado(dueno, id, "PENDIENTE").andExpect(status().isOk()));

        assertThat(aceptado.get("estado").asText()).isEqualTo("ACEPTADO");
        assertThat(aceptado.get("renglones")).hasSize(1);
        assertThat(repetido.get("estado").asText()).isEqualTo("ACEPTADO");
        assertThat(rechazado.get("estado").asText()).isEqualTo("RECHAZADO");
        assertThat(pendiente.get("estado").asText()).isEqualTo("PENDIENTE");
        assertThat(jdbc.queryForObject("select estado from presupuesto where id = ?", String.class, id))
                .isEqualTo("PENDIENTE");
        assertThat(stock(a)).isEqualByComparingTo("10");
        assertThat(movimientos(t)).isZero();
    }

    @Test
    @DisplayName("un rechazado lo reactiva solo el Dueño: el Vendedor recibe 403 y el presupuesto sigue rechazado")
    void reactivarUnRechazadoEsDelDueno() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String vendedor = login(t.emailVendedor());
        long id = presupuesto(vendedor, a, "1", "100.00", "100.00").get("presupuestoId").asLong();
        cambiarEstado(vendedor, id, "RECHAZADO").andExpect(status().isOk());

        for (String estado : new String[] {"PENDIENTE", "ACEPTADO"}) {
            cambiarEstado(vendedor, id, estado).andExpect(status().isForbidden());
            assertThat(jdbc.queryForObject("select estado from presupuesto where id = ?", String.class, id))
                    .as(estado).isEqualTo("RECHAZADO");
        }
        cambiarEstado(vendedor, id, "RECHAZADO").andExpect(status().isOk());
        JsonNode aceptado = json(cambiarEstado(login(t.emailDueno()), id, "ACEPTADO").andExpect(status().isOk()));
        assertThat(aceptado.get("estado").asText()).isEqualTo("ACEPTADO");
    }

    @Test
    @DisplayName("sobre un rechazado, un estado no válido es 400 y el de otra ferretería es 404, antes que el 403")
    void reactivarUnRechazadoRespetaElOrdenDeLosErrores() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String vendedor = login(t.emailVendedor());
        long id = presupuesto(vendedor, a, "1", "100.00", "100.00").get("presupuestoId").asLong();
        cambiarEstado(vendedor, id, "RECHAZADO").andExpect(status().isOk());

        cambiarEstado(vendedor, id, "FACTURADO").andExpect(status().isBadRequest());
        cambiarEstado(vendedor, id, "pendiente").andExpect(status().isBadRequest());
        Tienda otra = tiendaSinCaja();
        cambiarEstado(login(otra.emailVendedor()), id, "ACEPTADO").andExpect(status().isNotFound());
        cambiarEstado(login(otra.emailDueno()), id, "ACEPTADO").andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("select estado from presupuesto where id = ?", String.class, id))
                .isEqualTo("RECHAZADO");
    }

    @Test
    @DisplayName("pasar a FACTURADO a mano, un estado que no existe o la falta del estado dan 400 y no cambian nada")
    void estadoNoValido() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        long id = presupuesto(token, a, "1", "100.00", "100.00").get("presupuestoId").asLong();

        for (String estado : new String[] {"FACTURADO", "VENCIDO", "aceptado", null}) {
            JsonNode error = json(cambiarEstado(token, id, estado).andExpect(status().isBadRequest()));
            assertThat(error.get("errors").get("estado").asText()).as(String.valueOf(estado))
                    .isEqualTo("Estado no válido.");
        }
        assertThat(jdbc.queryForObject("select estado from presupuesto where id = ?", String.class, id))
                .isEqualTo("PENDIENTE");
    }

    @Test
    @DisplayName("uno ya facturado no se puede cambiar: 409; uno de otra ferreteria, 404")
    void facturadoOAjeno() throws Exception {
        Tienda t = tiendaSinCaja();
        Tienda otra = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        long id = presupuesto(token, a, "1", "100.00", "100.00").get("presupuestoId").asLong();
        jdbc.update("update presupuesto set estado = 'FACTURADO' where id = ?", id);

        JsonNode conflicto = json(cambiarEstado(token, id, "RECHAZADO").andExpect(status().isConflict()));
        cambiarEstado(login(otra.emailVendedor()), id, "RECHAZADO").andExpect(status().isNotFound());

        assertThat(conflicto.get("message").asText()).isEqualTo("Ese presupuesto ya se facturó.");
        assertThat(jdbc.queryForObject("select estado from presupuesto where id = ?", String.class, id))
                .isEqualTo("FACTURADO");
    }

    @Test
    @DisplayName("sin sesion, listar, ver y cambiar el estado dan 401")
    void sinSesion() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/presupuestos"))
                .andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/presupuestos/1"))
                .andExpect(status().isUnauthorized());
        mvc.perform(patch("/api/presupuestos/1/estado").contentType(MediaType.APPLICATION_JSON)
                .content("{\"estado\":\"ACEPTADO\"}")).andExpect(status().isUnauthorized());
    }
}
