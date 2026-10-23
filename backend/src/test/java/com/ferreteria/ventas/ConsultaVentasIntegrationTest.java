package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.common.HoraArgentina;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Consulta y reimpresion de ventas (G60, GDS-15): listar y ver las ventas hechas, contra MySQL real. */
class ConsultaVentasIntegrationTest extends VentasTestBase {

    private long venta(String token, long articulo, String cantidad, String precio, String total) throws Exception {
        return json(vender(token, clave(), total, linea(articulo, cantidad, precio)).andExpect(status().isCreated()))
                .get("ventaId").asLong();
    }

    private List<Long> numeros(JsonNode pagina) {
        List<Long> numeros = new ArrayList<>();
        pagina.get("items").forEach(i -> numeros.add(i.get("numero").asLong()));
        return numeros;
    }

    /** La fecha como la guarda Hibernate ({@code jdbc.time_zone: UTC}): la hora de Buenos Aires pasada a UTC. */
    private void fechar(long venta, LocalDateTime hora) {
        jdbc.update("update comprobante set fecha = ? where id = ?",
                hora.atZone(ZoneId.systemDefault()).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime(), venta);
    }

    @Test
    @DisplayName("el listado trae las ventas de la ferreteria, las mas nuevas primero, con usuario y sin renglones, y pagina")
    void listaMasNuevasPrimeroYPagina() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String dueno = login(t.emailDueno());
        String vendedor = login(t.emailVendedor());
        venta(vendedor, a, "1", "100.00", "100.00");
        venta(dueno, a, "2", "100.00", "200.00");
        venta(vendedor, a, "3", "100.00", "300.00");
        Tienda otra = tienda();
        venta(login(otra.emailVendedor()), articulo(otra, "Clavo", "UNIDAD", "50.00", "10"), "1", "50.00", "50.00");

        JsonNode todas = getJson("/api/ventas", dueno);
        JsonNode primera = getJson("/api/ventas?size=2", dueno);
        JsonNode segunda = getJson("/api/ventas?size=2&page=1", dueno);

        assertThat(numeros(todas)).containsExactly(3L, 2L, 1L);
        assertThat(todas.get("totalElements").asLong()).isEqualTo(3);
        assertThat(numeros(primera)).containsExactly(3L, 2L);
        assertThat(numeros(segunda)).containsExactly(1L);
        JsonNode fila = todas.get("items").get(1);
        assertThat(fila.get("ventaId").asLong()).isPositive();
        assertThat(fila.get("total").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(fila.get("usuario").asText()).isEqualTo("Dueno Ventas");
        assertThat(fila.get("facturable").asBoolean()).isTrue();
        assertThat(fila.get("fecha").asText()).isNotBlank();
        assertThat(fila.has("clienteNombre")).isFalse();
        assertThat(fila.has("renglones")).isFalse();
        assertThat(todas.get("items").get(0).get("usuario").asText()).isEqualTo("Vendedor Ventas");
    }

    @Test
    @DisplayName("filtra por numero, por cliente (contiene, sin mayusculas ni tildes) y por fecha, inclusive")
    void filtra() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String dueno = login(t.emailDueno());
        long vieja = venta(dueno, a, "1", "100.00", "100.00");
        long conCliente = venta(dueno, a, "1", "100.00", "100.00");
        venta(dueno, a, "1", "100.00", "100.00");
        jdbc.update("update comprobante set cliente_nombre = 'Construcciones Pérez SRL' where id = ?", conCliente);
        jdbc.update("update comprobante set cliente_nombre = 'Otro 100% _raro' where id = ?", vieja);
        LocalDate hoy = HoraArgentina.hoy();
        fechar(vieja, hoy.minusDays(10).atTime(23, 59, 59));

        assertThat(numeros(getJson("/api/ventas?numero=2", dueno))).containsExactly(2L);
        assertThat(numeros(getJson("/api/ventas?numero=99", dueno))).isEmpty();
        assertThat(numeros(getJson("/api/ventas?cliente=perez", dueno))).containsExactly(2L);
        assertThat(numeros(getJson("/api/ventas?cliente=PÉREZ SRL", dueno))).containsExactly(2L);
        // MockMvc codifica la URL: el % llega literal al parametro.
        assertThat(numeros(getJson("/api/ventas?cliente=%", dueno))).containsExactly(1L);
        assertThat(numeros(getJson("/api/ventas?cliente=_", dueno))).containsExactly(1L);
        assertThat(numeros(getJson("/api/ventas?cliente=  ", dueno))).containsExactly(3L, 2L, 1L);
        assertThat(numeros(getJson("/api/ventas?desde=" + hoy, dueno))).containsExactly(3L, 2L);
        assertThat(numeros(getJson("/api/ventas?hasta=" + hoy.minusDays(10), dueno))).containsExactly(1L);
        assertThat(numeros(getJson("/api/ventas?desde=" + hoy.minusDays(9) + "&hasta=" + hoy, dueno)))
                .containsExactly(3L, 2L);
        assertThat(numeros(getJson("/api/ventas?desde=" + hoy.plusDays(1), dueno))).isEmpty();
        getResult("/api/ventas?desde=ayer", dueno).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("la venta No facturable se marca en el listado")
    void marcaLaNoFacturable() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String dueno = login(t.emailDueno());
        long id = venta(dueno, a, "1", "100.00", "100.00");
        jdbc.update("update comprobante set estado = 'NO_FACTURABLE' where id = ?", id);

        assertThat(getJson("/api/ventas", dueno).get("items").get(0).get("facturable").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("el detalle trae renglones, pagos y usuario; una de otra ferreteria o que no existe da 404")
    void detalle() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String dueno = login(t.emailDueno());
        long id = venta(login(t.emailVendedor()), a, "2", "100.00", "200.00");
        Tienda otra = tienda();
        long ajena = venta(login(otra.emailVendedor()), articulo(otra, "Clavo", "UNIDAD", "50.00", "10"), "1", "50.00",
                "50.00");

        JsonNode venta = getJson("/api/ventas/" + id, dueno);

        assertThat(venta.get("ventaId").asLong()).isEqualTo(id);
        assertThat(venta.get("numero").asLong()).isEqualTo(1);
        assertThat(venta.get("total").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(venta.get("usuario").asText()).isEqualTo("Vendedor Ventas");
        assertThat(venta.get("renglones")).hasSize(1);
        assertThat(venta.get("renglones").get(0).get("descripcion").asText()).isEqualTo("Tornillo");
        assertThat(venta.get("renglones").get(0).get("cantidad").decimalValue()).isEqualByComparingTo("2");
        assertThat(venta.get("pagos")).hasSize(1);
        assertThat(venta.get("pagos").get(0).get("medio").asText()).isEqualTo("EFECTIVO");
        assertThat(venta.get("pagos").get(0).get("monto").decimalValue()).isEqualByComparingTo("200.00");
        assertThat(venta.get("sinStock")).isEmpty();
        assertThat(venta.get("renglones").get(0).has("costo")).isFalse();
        getResult("/api/ventas/" + ajena, dueno).andExpect(status().isNotFound());
        getResult("/api/ventas/999999999", dueno).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("solo el Dueno: el Vendedor recibe 403 en el listado y en el detalle")
    void soloElDueno() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String vendedor = login(t.emailVendedor());
        long id = venta(vendedor, a, "1", "100.00", "100.00");

        getResult("/api/ventas", vendedor).andExpect(status().isForbidden());
        getResult("/api/ventas/" + id, vendedor).andExpect(status().isForbidden());
    }
}
