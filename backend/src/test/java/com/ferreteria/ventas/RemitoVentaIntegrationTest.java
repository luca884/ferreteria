package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
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

/**
 * CU-07 (G55, RM-006): cobrar un remito con {@code POST /api/ventas} y {@code remitoId}, contra MySQL real. La venta
 * concilia la diferencia de stock contra lo que el remito ya descontó.
 */
class RemitoVentaIntegrationTest extends VentasTestBase {

    /** El cuerpo normal de la venta en efectivo, con el remito del que viene. */
    private ResultActions venderRemito(String token, String clave, Long remitoId, String total, Linea... lineas)
            throws Exception {
        Map<String, Object> cuerpo = mapa(clave, null, total, lineas);
        cuerpo.put("formaPagoId", formaDePago(token, "EFECTIVO"));
        cuerpo.put("remitoId", remitoId);
        return postJson("/api/ventas", token, objectMapper.writeValueAsString(cuerpo));
    }

    private String estado(long remitoId) {
        return jdbc.queryForObject("select estado from remito where id = ?", String.class, remitoId);
    }

    private List<Map<String, Object>> diferencias(long comprobanteId) {
        return jdbc.queryForList("select articulo_id, cantidad, remito_id, motivo, tipo from movimiento_stock "
                + "where comprobante_id = ? order by articulo_id", comprobanteId);
    }

    @Test
    @DisplayName("la venta igual al remito lo deja FACTURADO, guarda de cual vino y no mueve el stock otra vez")
    void ventaIgualAlRemito() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        long r = remito(token, a, "2", "100.00", "200.00").get("remitoId").asLong();
        assertThat(stock(a)).isEqualByComparingTo("8");

        JsonNode venta = json(venderRemito(token, clave(), r, "200.00", linea(a, "2", "100.00"))
                .andExpect(status().isCreated()));

        assertThat(venta.get("remitoId").asLong()).isEqualTo(r);
        assertThat(venta.get("sinStock")).isEmpty();
        assertThat(estado(r)).isEqualTo("FACTURADO");
        assertThat(jdbc.queryForObject("select remito_id from comprobante where id = ?", Long.class,
                venta.get("ventaId").asLong())).isEqualTo(r);
        assertThat(stock(a)).isEqualByComparingTo("8");
        assertThat(movimientos(t)).isEqualTo(1);
    }

    @Test
    @DisplayName("una venta sin remito no trae remitoId y guarda nulo")
    void ventaSinRemito() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");

        JsonNode venta = json(vender(login(t.emailVendedor()), clave(), "100.00", linea(a, "1", "100.00"))
                .andExpect(status().isCreated()));

        assertThat(venta.has("remitoId")).isFalse();
        assertThat(jdbc.queryForObject("select remito_id from comprobante where id = ?", Long.class,
                venta.get("ventaId").asLong())).isNull();
        assertThat(stock(a)).isEqualByComparingTo("9");
    }

    @Test
    @DisplayName("si se vende de mas, sale solo la diferencia: remito de 2 y venta de 3 mueve 1")
    void ventaDeMas() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        long r = remito(token, a, "2", "100.00", "200.00").get("remitoId").asLong();

        JsonNode venta = json(venderRemito(token, clave(), r, "300.00", linea(a, "3", "100.00"))
                .andExpect(status().isCreated()));

        assertThat(stock(a)).isEqualByComparingTo("7");
        List<Map<String, Object>> movimientos = diferencias(venta.get("ventaId").asLong());
        assertThat(movimientos).hasSize(1);
        assertThat(movimientos.get(0).get("tipo")).isEqualTo("VENTA");
        assertThat((BigDecimal) movimientos.get(0).get("cantidad")).isEqualByComparingTo("-1");
        assertThat(((Number) movimientos.get(0).get("remito_id")).longValue()).isEqualTo(r);
        assertThat(movimientos.get(0).get("motivo")).isEqualTo("Diferencia con el remito N.º 1");
    }

    @Test
    @DisplayName("si el Dueño vende de menos, vuelve la diferencia: remito de 3 y venta de 1 devuelve 2")
    void ventaDeMenos() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long r = remito(login(t.emailVendedor()), a, "3", "100.00", "300.00").get("remitoId").asLong();

        JsonNode venta = json(venderRemito(login(t.emailDueno()), clave(), r, "100.00", linea(a, "1", "100.00"))
                .andExpect(status().isCreated()));

        assertThat(stock(a)).isEqualByComparingTo("9");
        List<Map<String, Object>> movimientos = diferencias(venta.get("ventaId").asLong());
        assertThat(movimientos).hasSize(1);
        assertThat((BigDecimal) movimientos.get(0).get("cantidad")).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("con el Dueño, un articulo que el remito no tenia sale entero y uno del remito que no se vendio vuelve entero")
    void articulosQueCambian() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long b = articulo(t, "Clavo", "UNIDAD", "50.00", "10");
        long r = remito(login(t.emailVendedor()), a, "2", "100.00", "200.00").get("remitoId").asLong();

        JsonNode venta = json(venderRemito(login(t.emailDueno()), clave(), r, "150.00", linea(b, "3", "50.00"))
                .andExpect(status().isCreated()));

        assertThat(stock(a)).isEqualByComparingTo("10");
        assertThat(stock(b)).isEqualByComparingTo("7");
        assertThat(diferencias(venta.get("ventaId").asLong())).hasSize(2);
    }

    @Test
    @DisplayName("hallazgo 3: el Vendedor no factura un remito por menos de lo que entrego: 403 y no cambia nada")
    void vendedorNoDevuelveStock() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Bolsa de cemento", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        long r = remito(token, a, "3", "100.00", "300.00").get("remitoId").asLong();

        JsonNode rechazo = json(venderRemito(token, clave(), r, "100.00", linea(a, "1", "100.00"))
                .andExpect(status().isForbidden()));

        assertThat(rechazo.get("message").asText())
                .isEqualTo("Facturar menos de lo que entregó el remito devuelve stock: lo tiene que hacer el Dueño.");
        assertThat(estado(r)).isEqualTo("EMITIDO");
        assertThat(stock(a)).isEqualByComparingTo("7");
        assertThat(comprobantes(t)).isZero();
    }

    @Test
    @DisplayName("hallazgo 3: el Vendedor no saca del ticket un articulo del remito: 403 y no cambia nada")
    void vendedorNoSacaUnArticuloDelRemito() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Bolsa de cemento", "UNIDAD", "100.00", "10");
        long b = articulo(t, "Caño", "UNIDAD", "50.00", "10");
        String token = login(t.emailVendedor());
        Map<String, Object> pedido = mapa(clave(), null, "300.00", linea(a, "2", "100.00"), linea(b, "2", "50.00"));
        pedido.remove("formaPagoId");
        long r = json(postJson("/api/remitos", token, objectMapper.writeValueAsString(pedido))
                .andExpect(status().isCreated())).get("remitoId").asLong();

        venderRemito(token, clave(), r, "200.00", linea(a, "2", "100.00")).andExpect(status().isForbidden());

        assertThat(estado(r)).isEqualTo("EMITIDO");
        assertThat(stock(a)).isEqualByComparingTo("8");
        assertThat(stock(b)).isEqualByComparingTo("8");
        assertThat(comprobantes(t)).isZero();
    }

    @Test
    @DisplayName("hallazgo 3: el Vendedor agrega un articulo sin sacar los del remito: se vende")
    void vendedorAgregaSinSacar() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Bolsa de cemento", "UNIDAD", "100.00", "10");
        long b = articulo(t, "Caño", "UNIDAD", "50.00", "10");
        String token = login(t.emailVendedor());
        long r = remito(token, a, "2", "100.00", "200.00").get("remitoId").asLong();

        venderRemito(token, clave(), r, "250.00", linea(a, "2", "100.00"), linea(b, "1", "50.00"))
                .andExpect(status().isCreated());

        assertThat(estado(r)).isEqualTo("FACTURADO");
        assertThat(stock(a)).isEqualByComparingTo("8");
        assertThat(stock(b)).isEqualByComparingTo("9");
    }

    @Test
    @DisplayName("el aviso de sin stock mira solo lo que sale en la venta: el negativo que dejo el remito no se repite")
    void sinStockSoloDeLoQueSale() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "1");
        long b = articulo(t, "Clavo", "UNIDAD", "50.00", "1");
        String token = login(t.emailVendedor());
        long r = remito(token, a, "3", "100.00", "300.00").get("remitoId").asLong();
        assertThat(stock(a)).isEqualByComparingTo("-2");

        JsonNode igual = json(venderRemito(token, clave(), r, "300.00", linea(a, "3", "100.00"))
                .andExpect(status().isCreated()));
        long r2 = remito(token, b, "1", "50.00", "50.00").get("remitoId").asLong();
        JsonNode deMas = json(venderRemito(token, clave(), r2, "150.00", linea(b, "3", "50.00"))
                .andExpect(status().isCreated()));

        assertThat(igual.get("sinStock")).isEmpty();
        assertThat(deMas.get("sinStock")).hasSize(1);
        assertThat(deMas.get("sinStock").get(0).get("articuloId").asLong()).isEqualTo(b);
        assertThat(deMas.get("sinStock").get(0).get("stockActual").decimalValue()).isEqualByComparingTo("-2");
    }

    @Test
    @DisplayName("se cobra con los precios de hoy: con el precio viejo da 409 y el remito y el stock siguen igual")
    void preciosDeHoy() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        long r = remito(token, a, "2", "100.00", "200.00").get("remitoId").asLong();
        jdbc.update("update articulo set precio_final = 120.00 where id = ?", a);

        JsonNode rechazo = json(venderRemito(token, clave(), r, "200.00", linea(a, "2", "100.00"))
                .andExpect(status().isConflict()));
        assertThat(rechazo.get("motivo").asText()).isEqualTo("PRECIOS_CAMBIADOS");
        assertThat(estado(r)).isEqualTo("EMITIDO");
        assertThat(comprobantes(t)).isZero();
        assertThat(stock(a)).isEqualByComparingTo("8");

        JsonNode venta = json(venderRemito(token, clave(), r, "240.00", linea(a, "2", "120.00"))
                .andExpect(status().isCreated()));

        assertThat(venta.get("total").decimalValue()).isEqualByComparingTo("240.00");
        assertThat(estado(r)).isEqualTo("FACTURADO");
    }

    @Test
    @DisplayName("uno anulado o ya facturado da 409 con su mensaje y no vende nada")
    void anuladoOFacturado() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        long anulado = remito(token, a, "1", "100.00", "100.00").get("remitoId").asLong();
        long facturado = remito(token, a, "1", "100.00", "100.00").get("remitoId").asLong();
        jdbc.update("update remito set estado = 'ANULADO' where id = ?", anulado);
        venderRemito(token, clave(), facturado, "100.00", linea(a, "1", "100.00")).andExpect(status().isCreated());

        JsonNode alAnulado = json(venderRemito(token, clave(), anulado, "100.00", linea(a, "1", "100.00"))
                .andExpect(status().isConflict()));
        JsonNode alFacturado = json(venderRemito(token, clave(), facturado, "100.00", linea(a, "1", "100.00"))
                .andExpect(status().isConflict()));

        assertThat(alAnulado.get("message").asText()).isEqualTo("Ese remito fue anulado.");
        assertThat(alFacturado.get("message").asText()).isEqualTo("Ese remito ya se facturó.");
        assertThat(comprobantes(t)).isEqualTo(1);
        assertThat(stock(a)).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("un remito de otra ferreteria o que no existe da 404")
    void ajenoOInexistente() throws Exception {
        Tienda t = tienda();
        Tienda otra = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long b = articulo(otra, "Clavo", "UNIDAD", "100.00", "10");
        long ajeno = remito(login(otra.emailVendedor()), b, "1", "100.00", "100.00").get("remitoId").asLong();
        ultimaTienda = t;
        String token = login(t.emailVendedor());

        venderRemito(token, clave(), ajeno, "100.00", linea(a, "1", "100.00")).andExpect(status().isNotFound());
        venderRemito(token, clave(), 999_999_999L, "100.00", linea(a, "1", "100.00")).andExpect(status().isNotFound());

        assertThat(estado(ajeno)).isEqualTo("EMITIDO");
        assertThat(comprobantes(t)).isZero();
        assertThat(stock(b)).isEqualByComparingTo("9");
    }

    @Test
    @DisplayName("si la venta no se hace (sin caja abierta) el remito y el stock no cambian")
    void sinCajaNoCambiaElRemito() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        long r = remito(token, a, "2", "100.00", "200.00").get("remitoId").asLong();

        JsonNode rechazo = json(venderRemito(token, clave(), r, "300.00", linea(a, "3", "100.00"))
                .andExpect(status().isUnprocessableEntity()));

        assertThat(rechazo.get("motivo").asText()).isEqualTo("CAJA_CERRADA");
        assertThat(estado(r)).isEqualTo("EMITIDO");
        assertThat(stock(a)).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("no se puede pasar un presupuesto y un remito en la misma venta: 422 y no cambia nada")
    void presupuestoYRemitoJuntos() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        long r = remito(token, a, "1", "100.00", "100.00").get("remitoId").asLong();
        long p = presupuesto(token, a, "1", "100.00", "100.00").get("presupuestoId").asLong();
        Map<String, Object> cuerpo = mapa(clave(), null, "100.00", linea(a, "1", "100.00"));
        cuerpo.put("formaPagoId", formaDePago(token, "EFECTIVO"));
        cuerpo.put("remitoId", r);
        cuerpo.put("presupuestoId", p);

        JsonNode error = json(postJson("/api/ventas", token, objectMapper.writeValueAsString(cuerpo))
                .andExpect(status().isUnprocessableEntity()));

        assertThat(error.get("message").asText()).isEqualTo("Una venta sale de un presupuesto o de un remito, no de los dos.");
        assertThat(estado(r)).isEqualTo("EMITIDO");
        assertThat(jdbc.queryForObject("select estado from presupuesto where id = ?", String.class, p))
                .isEqualTo("PENDIENTE");
        assertThat(comprobantes(t)).isZero();
    }

    @Test
    @DisplayName("repetir la venta devuelve la original (200) sin conciliar de nuevo; la misma clave con otro remito, 422")
    void idempotencia() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        long r = remito(token, a, "1", "100.00", "100.00").get("remitoId").asLong();
        long otro = remito(token, a, "1", "100.00", "100.00").get("remitoId").asLong();
        String clave = clave();

        JsonNode primera = json(venderRemito(token, clave, r, "300.00", linea(a, "3", "100.00"))
                .andExpect(status().isCreated()));
        JsonNode repetida = json(venderRemito(token, clave, r, "300.00", linea(a, "3", "100.00"))
                .andExpect(status().isOk()));
        JsonNode otroRemito = json(venderRemito(token, clave, otro, "300.00", linea(a, "3", "100.00"))
                .andExpect(status().isUnprocessableEntity()));

        assertThat(repetida.get("ventaId").asLong()).isEqualTo(primera.get("ventaId").asLong());
        assertThat(repetida.get("remitoId").asLong()).isEqualTo(r);
        assertThat(otroRemito.get("motivo").asText()).isEqualTo("CLAVE_CON_OTRO_CONTENIDO");
        assertThat(estado(r)).isEqualTo("FACTURADO");
        assertThat(estado(otro)).isEqualTo("EMITIDO");
        assertThat(comprobantes(t)).isEqualTo(1);
        assertThat(stock(a)).isEqualByComparingTo("6");
    }

    @Test
    @DisplayName("dos ventas a la vez del mismo remito: una se hace y la otra da 409")
    void dosVentasDelMismoRemito() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        long r = remito(token, a, "1", "100.00", "100.00").get("remitoId").asLong();
        long forma = formaDePago(token, "EFECTIVO");
        List<Callable<Integer>> pedidos = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Map<String, Object> cuerpo = mapa(clave(), null, "100.00", linea(a, "1", "100.00"));
            cuerpo.put("formaPagoId", forma);
            cuerpo.put("remitoId", r);
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
        assertThat(estado(r)).isEqualTo("FACTURADO");
    }

    @Test
    @DisplayName("anular el remito y facturarlo a la vez: una gana y el stock no se devuelve dos veces")
    void anularYFacturarALaVez() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String vendedor = login(t.emailVendedor());
        String duenio = login(t.emailDueno());
        long r = remito(vendedor, a, "1", "100.00", "100.00").get("remitoId").asLong();
        Map<String, Object> cuerpo = mapa(clave(), null, "100.00", linea(a, "1", "100.00"));
        cuerpo.put("formaPagoId", formaDePago(vendedor, "EFECTIVO"));
        cuerpo.put("remitoId", r);
        String venta = objectMapper.writeValueAsString(cuerpo);
        List<Callable<Integer>> pedidos = List.of(
                () -> postJson("/api/ventas", vendedor, venta).andReturn().getResponse().getStatus(),
                () -> mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .patch("/api/remitos/" + r + "/anular").header("Authorization", "Bearer " + duenio))
                        .andReturn().getResponse().getStatus());

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

        String final_ = estado(r);
        if (final_.equals("FACTURADO")) {
            assertThat(estados).containsExactly(201, 409);
            assertThat(stock(a)).isEqualByComparingTo("9");
        } else {
            assertThat(final_).isEqualTo("ANULADO");
            assertThat(estados.get(1)).isEqualTo(200);
            assertThat(estados.get(0)).isEqualTo(409);
            assertThat(stock(a)).isEqualByComparingTo("10");
        }
    }
}
