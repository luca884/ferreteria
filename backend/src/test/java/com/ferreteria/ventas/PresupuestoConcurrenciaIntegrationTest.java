package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
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

/** CU-07 (GDS-13, PP-003) contra MySQL real y con hilos de verdad: la misma clave a la vez y la numeracion sin huecos. */
class PresupuestoConcurrenciaIntegrationTest extends VentasTestBase {

    private record Respuesta(int status, JsonNode cuerpo) {}

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
                respuestas.add(futuro.get(60, TimeUnit.SECONDS));
            }
            return respuestas;
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<Respuesta> emitir(String token, String clave, long articuloId, String cantidad, String total) {
        return () -> {
            Map<String, Object> renglon = Map.of("articuloId", articuloId, "cantidad", new BigDecimal(cantidad),
                    "precioUnitario", new BigDecimal("100.00"));
            Map<String, Object> cuerpo = Map.of("clave", clave, "renglones", List.of(renglon), "totalMostrado",
                    new BigDecimal(total));
            var resultado = postJson("/api/presupuestos", token, objectMapper.writeValueAsString(cuerpo))
                    .andReturn().getResponse();
            return new Respuesta(resultado.getStatus(),
                    objectMapper.readTree(resultado.getContentAsString(StandardCharsets.UTF_8)));
        };
    }

    private long presupuestos(Tienda tienda) {
        return jdbc.queryForObject("select count(*) from presupuesto where ferreteria_id = ?", Long.class, tienda.id());
    }

    @Test
    @DisplayName("dos hilos con la misma clave dan un solo presupuesto, con el mismo numero")
    void dosHilosConLaMismaClave() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        String clave = clave();

        List<Respuesta> respuestas = enParalelo(List.of(
                emitir(token, clave, a, "3", "300.00"), emitir(token, clave, a, "3", "300.00")));

        assertThat(respuestas).extracting(Respuesta::status).containsExactlyInAnyOrder(201, 200);
        assertThat(respuestas.get(0).cuerpo().get("presupuestoId")).isEqualTo(respuestas.get(1).cuerpo().get("presupuestoId"));
        assertThat(respuestas.get(0).cuerpo().get("numero")).isEqualTo(respuestas.get(1).cuerpo().get("numero"));
        assertThat(presupuestos(t)).isEqualTo(1);
    }

    @Test
    @DisplayName("diez presupuestos a la vez dan los numeros del uno al diez sin huecos")
    void numeracionSinHuecos() throws Exception {
        Tienda t = tiendaSinCaja();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "100");
        String token = login(t.emailVendedor());
        List<Callable<Respuesta>> pedidos = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            pedidos.add(emitir(token, clave(), a, "1", "100.00"));
        }

        List<Respuesta> respuestas = enParalelo(pedidos);

        assertThat(respuestas).extracting(Respuesta::status).containsOnly(201);
        assertThat(respuestas.stream().map(r -> r.cuerpo().get("numero").asLong()).sorted().toList())
                .containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        assertThat(presupuestos(t)).isEqualTo(10);
    }
}
