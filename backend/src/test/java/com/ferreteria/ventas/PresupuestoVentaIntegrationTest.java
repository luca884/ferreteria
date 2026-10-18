package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CU-07 (GDS-13, PP-006): cobrar un presupuesto con {@code POST /api/ventas} y {@code presupuestoId}, contra MySQL real. */
class PresupuestoVentaIntegrationTest extends VentasTestBase {

    /** El cuerpo normal de la venta en efectivo, con el presupuesto del que viene. */
    private ResultActions venderPresupuesto(String token, String clave, Long presupuestoId, String total,
            Linea... lineas) throws Exception {
        Map<String, Object> cuerpo = mapa(clave, null, total, lineas);
        cuerpo.put("formaPagoId", formaDePago(token, "EFECTIVO"));
        cuerpo.put("presupuestoId", presupuestoId);
        return postJson("/api/ventas", token, objectMapper.writeValueAsString(cuerpo));
    }

    private String estado(long presupuestoId) {
        return jdbc.queryForObject("select estado from presupuesto where id = ?", String.class, presupuestoId);
    }

    @Test
    @DisplayName("la venta desde un presupuesto lo deja FACTURADO, guarda de cual vino y descuenta el stock")
    void ventaDesdeUnPresupuesto() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        long p = presupuesto(token, a, "2", "100.00", "200.00").get("presupuestoId").asLong();

        JsonNode venta = json(venderPresupuesto(token, clave(), p, "200.00", linea(a, "2", "100.00"))
                .andExpect(status().isCreated()));

        assertThat(venta.get("presupuestoId").asLong()).isEqualTo(p);
        assertThat(estado(p)).isEqualTo("FACTURADO");
        assertThat(jdbc.queryForObject("select presupuesto_id from comprobante where id = ?", Long.class,
                venta.get("ventaId").asLong())).isEqualTo(p);
        assertThat(stock(a)).isEqualByComparingTo("8");
        assertThat(movimientos(t)).isEqualTo(1);
    }

    @Test
    @DisplayName("una venta sin presupuesto no trae presupuestoId y guarda nulo")
    void ventaSinPresupuesto() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        JsonNode venta = json(vender(login(t.emailVendedor()), clave(), "100.00", linea(a, "1", "100.00"))
                .andExpect(status().isCreated()));

        assertThat(venta.has("presupuestoId")).isFalse();
        assertThat(jdbc.queryForObject("select presupuesto_id from comprobante where id = ?", Long.class,
                venta.get("ventaId").asLong())).isNull();
    }

    @Test
    @DisplayName("un presupuesto aceptado o vencido tambien se cobra")
    void aceptadoYVencido() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        long aceptado = presupuesto(token, a, "1", "100.00", "100.00").get("presupuestoId").asLong();
        long vencido = presupuesto(token, a, "1", "100.00", "100.00").get("presupuestoId").asLong();
        jdbc.update("update presupuesto set estado = 'ACEPTADO' where id = ?", aceptado);
        jdbc.update("update presupuesto set vence = date_sub(curdate(), interval 30 day) where id = ?", vencido);

        venderPresupuesto(token, clave(), aceptado, "100.00", linea(a, "1", "100.00")).andExpect(status().isCreated());
        venderPresupuesto(token, clave(), vencido, "100.00", linea(a, "1", "100.00")).andExpect(status().isCreated());

        assertThat(estado(aceptado)).isEqualTo("FACTURADO");
        assertThat(estado(vencido)).isEqualTo("FACTURADO");
    }

    @Test
    @DisplayName("se cobra con los precios de hoy y lo que el cajero cargue: con el precio viejo da 409 y el presupuesto sigue igual")
    void preciosDeHoy() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        long p = presupuesto(token, a, "2", "100.00", "200.00").get("presupuestoId").asLong();
        jdbc.update("update articulo set precio_final = 120.00 where id = ?", a);

        JsonNode rechazo = json(venderPresupuesto(token, clave(), p, "200.00", linea(a, "2", "100.00"))
                .andExpect(status().isConflict()));
        assertThat(rechazo.get("motivo").asText()).isEqualTo("PRECIOS_CAMBIADOS");
        assertThat(estado(p)).isEqualTo("PENDIENTE");
        assertThat(comprobantes(t)).isZero();

        JsonNode venta = json(venderPresupuesto(token, clave(), p, "360.00", linea(a, "3", "120.00"))
                .andExpect(status().isCreated()));

        assertThat(venta.get("total").decimalValue()).isEqualByComparingTo("360.00");
        assertThat(estado(p)).isEqualTo("FACTURADO");
    }

    @Test
    @DisplayName("uno rechazado o ya facturado da 409 con su mensaje y no vende nada")
    void rechazadoOFacturado() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        long rechazado = presupuesto(token, a, "1", "100.00", "100.00").get("presupuestoId").asLong();
        long facturado = presupuesto(token, a, "1", "100.00", "100.00").get("presupuestoId").asLong();
        jdbc.update("update presupuesto set estado = 'RECHAZADO' where id = ?", rechazado);
        venderPresupuesto(token, clave(), facturado, "100.00", linea(a, "1", "100.00")).andExpect(status().isCreated());

        JsonNode alRechazado = json(venderPresupuesto(token, clave(), rechazado, "100.00", linea(a, "1", "100.00"))
                .andExpect(status().isConflict()));
        JsonNode alFacturado = json(venderPresupuesto(token, clave(), facturado, "100.00", linea(a, "1", "100.00"))
                .andExpect(status().isConflict()));

        assertThat(alRechazado.get("message").asText()).isEqualTo("Ese presupuesto fue rechazado.");
        assertThat(alFacturado.get("message").asText()).isEqualTo("Ese presupuesto ya se facturó.");
        assertThat(comprobantes(t)).isEqualTo(1);
        assertThat(stock(a)).isEqualByComparingTo("9");
    }

    @Test
    @DisplayName("un presupuesto de otra ferreteria o que no existe da 404")
    void ajenoOInexistente() throws Exception {
        Tienda t = tienda();
        Tienda otra = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long b = articulo(otra, "Clavo", "UNIDAD", "100.00", "10");
        long ajeno = presupuesto(login(otra.emailVendedor()), b, "1", "100.00", "100.00").get("presupuestoId").asLong();
        ultimaTienda = t;
        String token = login(t.emailVendedor());

        venderPresupuesto(token, clave(), ajeno, "100.00", linea(a, "1", "100.00")).andExpect(status().isNotFound());
        venderPresupuesto(token, clave(), 999_999_999L, "100.00", linea(a, "1", "100.00"))
                .andExpect(status().isNotFound());

        assertThat(estado(ajeno)).isEqualTo("PENDIENTE");
        assertThat(comprobantes(t)).isZero();
    }

    @Test
    @DisplayName("si la venta no se hace (sin caja abierta) el presupuesto no cambia")
    void sinCajaNoCambiaElPresupuesto() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        long p = presupuesto(token, a, "1", "100.00", "100.00").get("presupuestoId").asLong();

        JsonNode rechazo = json(venderPresupuesto(token, clave(), p, "100.00", linea(a, "1", "100.00"))
                .andExpect(status().isUnprocessableEntity()));

        assertThat(rechazo.get("motivo").asText()).isEqualTo("CAJA_CERRADA");
        assertThat(estado(p)).isEqualTo("PENDIENTE");
    }

    @Test
    @DisplayName("repetir la venta devuelve la original (200) sin tocar nada; la misma clave con otro presupuesto, 422")
    void idempotencia() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        long p = presupuesto(token, a, "1", "100.00", "100.00").get("presupuestoId").asLong();
        long otro = presupuesto(token, a, "1", "100.00", "100.00").get("presupuestoId").asLong();
        String clave = clave();

        JsonNode primera = json(venderPresupuesto(token, clave, p, "100.00", linea(a, "1", "100.00"))
                .andExpect(status().isCreated()));
        JsonNode repetida = json(venderPresupuesto(token, clave, p, "100.00", linea(a, "1", "100.00"))
                .andExpect(status().isOk()));
        JsonNode otroPresupuesto = json(venderPresupuesto(token, clave, otro, "100.00", linea(a, "1", "100.00"))
                .andExpect(status().isUnprocessableEntity()));

        assertThat(repetida.get("ventaId").asLong()).isEqualTo(primera.get("ventaId").asLong());
        assertThat(repetida.get("presupuestoId").asLong()).isEqualTo(p);
        assertThat(otroPresupuesto.get("motivo").asText()).isEqualTo("CLAVE_CON_OTRO_CONTENIDO");
        assertThat(estado(p)).isEqualTo("FACTURADO");
        assertThat(estado(otro)).isEqualTo("PENDIENTE");
        assertThat(comprobantes(t)).isEqualTo(1);
        assertThat(stock(a)).isEqualByComparingTo("9");
    }

    @Test
    @DisplayName("dos ventas a la vez del mismo presupuesto: una se hace y la otra da 409")
    void dosVentasDelMismoPresupuesto() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        long p = presupuesto(token, a, "1", "100.00", "100.00").get("presupuestoId").asLong();
        long forma = formaDePago(token, "EFECTIVO");
        List<Callable<Integer>> pedidos = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Map<String, Object> cuerpo = mapa(clave(), null, "100.00", linea(a, "1", "100.00"));
            cuerpo.put("formaPagoId", forma);
            cuerpo.put("presupuestoId", p);
            String json = objectMapper.writeValueAsString(cuerpo);
            pedidos.add(() -> postJson("/api/ventas", token, json).andReturn().getResponse().getStatus());
        }

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<Integer>> futuros = new ArrayList<>();
        for (Callable<Integer> pedido : pedidos) {
            futuros.add(pool.submit(() -> {
                largada.await();
                return pedido.call();
            }));
        }
        largada.countDown();
        List<Integer> estados = new ArrayList<>();
        for (Future<Integer> futuro : futuros) {
            estados.add(futuro.get(60, TimeUnit.SECONDS));
        }
        pool.shutdownNow();

        assertThat(estados).containsExactlyInAnyOrder(201, 409);
        assertThat(comprobantes(t)).isEqualTo(1);
        assertThat(stock(a)).isEqualByComparingTo("9");
        assertThat(estado(p)).isEqualTo("FACTURADO");
    }
}
