package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.support.InvarianteCuenta;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * CC-010 y CC-020, (f) del design, contra MySQL real y con hilos de verdad por HTTP: desactivar a un cliente y venderle a
 * cuenta corriente a la vez. Las dos operaciones toman la misma fila del cliente (FOR UPDATE), así que entra una sola: o se
 * desactiva (saldo 0) y la venta recibe 422 «desactivado», o la venta entra y la desactivación recibe 422 por saldo. Nunca
 * las dos, ni un cliente desactivado con deuda. Se repite en muchas rondas, con la desactivación llegando cada vez un poco más
 * tarde, para pasar por los dos órdenes: una carrera mal cerrada aparece de a ratos.
 */
class CuentaCorrienteDesactivarConcurrenciaIntegrationTest extends VentasTestBase {

    private static final int RONDAS = 20;
    private static final String DESACTIVADO = "El cliente está desactivado.";
    private static final String CON_SALDO = "No se puede desactivar: el cliente tiene saldo en cuenta corriente.";

    private record Respuesta(int status, JsonNode cuerpo) {}

    private long cliente(Tienda t) {
        String razon = "Cliente " + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into cliente (ferreteria_id, razon_social, limite_cuenta_corriente) values (?, ?, 1000.00)",
                t.id(), razon);
        return jdbc.queryForObject("select id from cliente where ferreteria_id = ? and razon_social = ?", Long.class,
                t.id(), razon);
    }

    /** {@code retrasoNs}: la desactivación arranca un poco después de la venta (la venta es más lenta: sin esto siempre gana la desactivación). */
    private Callable<Respuesta> desactivar(String token, long clienteId, long retrasoNs) {
        return () -> {
            java.util.concurrent.locks.LockSupport.parkNanos(retrasoNs);
            var resultado = mvc.perform(MockMvcRequestBuilders.patch("/api/clientes/" + clienteId + "/activo")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"activo\": false}")).andReturn().getResponse();
            return leer(resultado.getStatus(), resultado.getContentAsString(StandardCharsets.UTF_8));
        };
    }

    private Callable<Respuesta> ventaACuenta(Tienda t, long articulo, String token, long clienteId) {
        return () -> {
            Map<String, Object> renglon = new LinkedHashMap<>();
            renglon.put("articuloId", articulo);
            renglon.put("cantidad", new BigDecimal("100"));
            renglon.put("precioUnitario", new BigDecimal("1.00"));
            Map<String, Object> cuerpo = new LinkedHashMap<>();
            cuerpo.put("clave", clave());
            cuerpo.put("renglones", List.of(renglon));
            cuerpo.put("totalMostrado", new BigDecimal("100"));
            cuerpo.put("formaPagoId", formaDe(t, "CUENTA_CORRIENTE"));
            cuerpo.put("clienteId", clienteId);
            var resultado = postJson("/api/ventas", token, objectMapper.writeValueAsString(cuerpo)).andReturn().getResponse();
            return leer(resultado.getStatus(), resultado.getContentAsString(StandardCharsets.UTF_8));
        };
    }

    private Respuesta leer(int status, String texto) throws Exception {
        return new Respuesta(status, texto.isBlank() ? null : objectMapper.readTree(texto));
    }

    private List<Respuesta> enParalelo(List<Callable<Respuesta>> pedidos) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(pedidos.size());
        CountDownLatch listos = new CountDownLatch(pedidos.size());
        CountDownLatch largada = new CountDownLatch(1);
        try {
            List<Future<Respuesta>> futuros = new ArrayList<>();
            for (Callable<Respuesta> pedido : pedidos) {
                futuros.add(pool.submit(() -> {
                    listos.countDown();
                    largada.await();
                    return pedido.call();
                }));
            }
            assertThat(listos.await(30, TimeUnit.SECONDS)).isTrue();
            largada.countDown();
            List<Respuesta> respuestas = new ArrayList<>();
            for (Future<Respuesta> futuro : futuros) {
                respuestas.add(futuro.get(90, TimeUnit.SECONDS));
            }
            return respuestas;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("(f) desactivar y vender a cuenta a la vez, 20 rondas: entra una sola de las dos, nunca un desactivado con deuda")
    void desactivarContraVenta() throws Exception {
        Tienda t = tienda();
        activarFormaCuentaCorriente(t);
        long articulo = articulo(t, "Tornillo", "UNIDAD", "1.00", "10000000");
        String dueno = login(t.emailDueno());
        String vendedor = login(t.emailVendedor());
        int ganoLaVenta = 0;
        int ganoLaDesactivacion = 0;

        for (int ronda = 0; ronda < RONDAS; ronda++) {
            long c = cliente(t);
            // una ronda vende el Vendedor y la otra el Dueño: la autorización no entra en juego (no hay exceso)
            List<Respuesta> r = enParalelo(List.of(desactivar(dueno, c, ronda * 250_000L),
                    ventaACuenta(t, articulo, ronda % 2 == 0 ? vendedor : dueno, c)));
            Respuesta desactivacion = r.get(0);
            Respuesta venta = r.get(1);

            boolean activo = jdbc.queryForObject("select activo from cliente where id = ?", Boolean.class, c);
            String ronda_ = "ronda " + ronda + ": desactivar=" + desactivacion.status() + " venta=" + venta.status();
            if (venta.status() == 201) {
                ganoLaVenta++;
                assertThat(desactivacion.status()).as(ronda_).isEqualTo(422);
                assertThat(desactivacion.cuerpo().get("message").asText()).as(ronda_).isEqualTo(CON_SALDO);
                assertThat(activo).as(ronda_).isTrue();
                assertThat(jdbc.queryForObject("select saldo from cliente where id = ?", BigDecimal.class, c))
                        .as(ronda_).isEqualByComparingTo("100.00");
            } else {
                ganoLaDesactivacion++;
                assertThat(venta.status()).as(ronda_).isEqualTo(422);
                assertThat(venta.cuerpo().get("message").asText()).as(ronda_).isEqualTo(DESACTIVADO);
                assertThat(desactivacion.status()).as(ronda_).isEqualTo(200);
                assertThat(activo).as(ronda_).isFalse();
                assertThat(jdbc.queryForObject("select saldo from cliente where id = ?", BigDecimal.class, c))
                        .as(ronda_).isEqualByComparingTo("0");
                assertThat(jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente where cliente_id = ?",
                        Long.class, c)).as(ronda_).isZero();
            }
            InvarianteCuenta.assertInvariante(jdbc, c);
        }

        System.out.printf("(f) %d rondas: ganó la venta %d veces y la desactivación %d%n", RONDAS, ganoLaVenta,
                ganoLaDesactivacion);
        assertThat(ganoLaVenta + ganoLaDesactivacion).isEqualTo(RONDAS);
    }
}
