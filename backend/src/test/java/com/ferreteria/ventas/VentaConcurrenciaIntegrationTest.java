package com.ferreteria.ventas;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.stock.service.StockService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * CU-07 R-011, R-012, R-018 contra MySQL real y con hilos de verdad: la misma clave a la vez, numeracion sin
 * huecos, ventas que tocan los mismos articulos en orden opuesto y una falla a mitad de la escritura.
 * Cada test usa su propia ferreteria; ninguno toca los datos demo.
 */
class VentaConcurrenciaIntegrationTest extends VentasTestBase {

    /** Spy sobre el servicio real: solo se le cambia el comportamiento en el test de la falla inyectada. */
    @SpyBean
    private StockService stockService;

    /** Resultado de un POST: estado HTTP y cuerpo. */
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

    private Callable<Respuesta> post(String token, String clave, String total, Linea... lineas) {
        return () -> {
            var resultado = vender(token, clave, total, lineas).andReturn().getResponse();
            return new Respuesta(resultado.getStatus(),
                    objectMapper.readTree(resultado.getContentAsString(StandardCharsets.UTF_8)));
        };
    }

    @Test
    void dosHilosConLaMismaClaveDanUnComprobanteUnDescuentoYElMismoNumero() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        String token = login(t.emailVendedor());
        String clave = clave();

        List<Respuesta> respuestas = enParalelo(List.of(
                post(token, clave, "300.00", linea(a, "3", "100.00")),
                post(token, clave, "300.00", linea(a, "3", "100.00"))));

        assertThat(respuestas).extracting(Respuesta::status).containsExactlyInAnyOrder(201, 200);
        assertThat(respuestas.get(0).cuerpo().get("numero")).isEqualTo(respuestas.get(1).cuerpo().get("numero"));
        assertThat(respuestas.get(0).cuerpo().get("ventaId")).isEqualTo(respuestas.get(1).cuerpo().get("ventaId"));
        assertThat(comprobantes(t)).isEqualTo(1);
        assertThat(movimientos(t)).isEqualTo(1);
        assertThat(stock(a)).isEqualByComparingTo("7");
    }

    @Test
    void diezVentasConcurrentesDanLosNumerosUnoADiezSinHuecos() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "100");
        String token = login(t.emailVendedor());
        List<Callable<Respuesta>> pedidos = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            pedidos.add(post(token, clave(), "100.00", linea(a, "1", "100.00")));
        }

        List<Respuesta> respuestas = enParalelo(pedidos);

        assertThat(respuestas).extracting(Respuesta::status).containsOnly(201);
        List<Long> numeros = respuestas.stream().map(r -> r.cuerpo().get("numero").asLong()).sorted().toList();
        assertThat(numeros).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
        assertThat(comprobantes(t)).isEqualTo(10);
        assertThat(stock(a)).isEqualByComparingTo("90");
        assertThat(movimientos(t)).isEqualTo(10);
    }

    @Test
    void ventasConLosMismosArticulosEnOrdenOpuestoTerminanTodasEn201SinDeadlock() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "1000");
        long b = articulo(t, "Tuerca", "UNIDAD", "50.00", "1000");
        String token = login(t.emailVendedor());
        List<Callable<Respuesta>> pedidos = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            pedidos.add(post(token, clave(), "150.00", linea(a, "1", "100.00"), linea(b, "1", "50.00")));
            pedidos.add(post(token, clave(), "150.00", linea(b, "1", "50.00"), linea(a, "1", "100.00")));
        }

        List<Respuesta> respuestas = enParalelo(pedidos);

        assertThat(respuestas).extracting(Respuesta::status).containsOnly(201);
        assertThat(comprobantes(t)).isEqualTo(10);
        assertThat(stock(a)).isEqualByComparingTo("990");
        assertThat(stock(b)).isEqualByComparingTo("990");
    }

    @Test
    void unaFallaDespuesDeEscribirElStockNoDejaNadaYElNumeroNoSeConsume() throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long b = articulo(t, "Tuerca", "UNIDAD", "50.00", "10");
        String token = login(t.emailVendedor());
        // Falla DESPUES de que el servicio real descuento el stock y guardo los movimientos.
        StockService objetivo = AopTestUtils.getUltimateTargetObject(stockService);
        doAnswer(invocacion -> {
            invocacion.callRealMethod();
            throw new IllegalStateException("falla inyectada al escribir el segundo movimiento");
        }).when(objetivo).registrarVenta(any(), any(), any());

        vender(token, clave(), "150.00", linea(a, "1", "100.00"), linea(b, "1", "50.00"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isInternalServerError());

        assertThat(comprobantes(t)).isZero();
        assertThat(movimientos(t)).isZero();
        assertThat(stock(a)).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(stock(b)).isEqualByComparingTo(new BigDecimal("10"));

        org.mockito.Mockito.reset(objetivo);
        JsonNode siguiente = json(vender(token, clave(), "100.00", linea(a, "1", "100.00"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated()));
        assertThat(siguiente.get("numero").asLong()).isEqualTo(1);
    }

    /**
     * La fachada reintenta ante PessimisticLockingFailureException (VentaServiceTest lo prueba con Mockito).
     * Aca se confirma contra MySQL real que un deadlock verdadero llega como esa excepcion: dos transacciones
     * se cruzan sobre dos articulos y la que pierde recibe la excepcion que la fachada sabe reintentar.
     */
    @Test
    void unDeadlockRealDeMysqlLlegaComoUnaExcepcionDeLockQueLaFachadaReintenta(
            @org.springframework.beans.factory.annotation.Autowired PlatformTransactionManager tm) throws Exception {
        Tienda t = tienda();
        long a = articulo(t, "Tornillo", "UNIDAD", "100.00", "10");
        long b = articulo(t, "Tuerca", "UNIDAD", "50.00", "10");
        TransactionTemplate tx = new TransactionTemplate(tm);
        CountDownLatch primerLock = new CountDownLatch(2);
        AtomicReference<Throwable> falla = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> uno = pool.submit(() -> cruzar(tx, jdbc, a, b, primerLock, falla));
            Future<?> otro = pool.submit(() -> cruzar(tx, jdbc, b, a, primerLock, falla));
            uno.get(60, TimeUnit.SECONDS);
            otro.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        assertThat(falla.get()).isInstanceOf(PessimisticLockingFailureException.class);
    }

    private static void cruzar(TransactionTemplate tx, JdbcTemplate jdbc, long primero, long segundo,
            CountDownLatch primerLock, AtomicReference<Throwable> falla) {
        try {
            tx.executeWithoutResult(estado -> {
                jdbc.update("update articulo set stock_actual = stock_actual + 1 where id = ?", primero);
                primerLock.countDown();
                try {
                    primerLock.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                jdbc.update("update articulo set stock_actual = stock_actual + 1 where id = ?", segundo);
            });
        } catch (Throwable e) {
            falla.compareAndSet(null, e);
        }
    }
}
