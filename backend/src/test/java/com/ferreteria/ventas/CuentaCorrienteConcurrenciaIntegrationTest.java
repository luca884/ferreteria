package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.clientes.service.CuentaCorrienteService;
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
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.util.AopTestUtils;

/**
 * CC-009 y CC-010 contra MySQL real y con hilos de verdad por HTTP: el límite se respeta bajo carga, la autorización del Dueño
 * marca los excesos, el orden canónico de locks (caja → presupuesto/remito → cliente → numerador → ...) no deja ciclos entre
 * ventas con y sin cuenta del mismo cliente ni con la emisión de remitos y presupuestos, el doble envío con límite justo
 * devuelve la venta original y una falla del asiento no deja nada a medias.
 */
class CuentaCorrienteConcurrenciaIntegrationTest extends VentasTestBase {

    private record Respuesta(int status, JsonNode cuerpo) {}

    /** Para la prueba de atomicidad (h): hace fallar el asiento a pedido; en el resto de los tests llama al servicio real. */
    @SpyBean
    CuentaCorrienteService cuentaCorriente;

    private record Escenario(Tienda tienda, long articulo, String dueno, String vendedor) {}

    private Escenario escenario() throws Exception {
        Tienda t = tienda();
        activarFormaCuentaCorriente(t);
        long articulo = articulo(t, "Tornillo", "UNIDAD", "1.00", "10000000");
        return new Escenario(t, articulo, login(t.emailDueno()), login(t.emailVendedor()));
    }

    private long cliente(Tienda t, String limite) {
        String razon = "Cliente " + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into cliente (ferreteria_id, razon_social, limite_cuenta_corriente) values (?, ?, ?)",
                t.id(), razon, limite == null ? null : new BigDecimal(limite));
        return jdbc.queryForObject("select id from cliente where ferreteria_id = ? and razon_social = ?", Long.class,
                t.id(), razon);
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

    private Callable<Respuesta> post(String url, String token, Map<String, Object> cuerpo) {
        return () -> {
            var resultado = postJson(url, token, objectMapper.writeValueAsString(cuerpo)).andReturn().getResponse();
            String texto = resultado.getContentAsString(StandardCharsets.UTF_8);
            return new Respuesta(resultado.getStatus(), texto.isBlank() ? null : objectMapper.readTree(texto));
        };
    }

    /** Venta de {@code unidades} del artículo a $ 1,00 pagada con la forma dada, a nombre del cliente. */
    private Map<String, Object> venta(Escenario e, int unidades, long clienteId, long formaPagoId, boolean autoriza) {
        Map<String, Object> renglon = new LinkedHashMap<>();
        renglon.put("articuloId", e.articulo());
        renglon.put("cantidad", new BigDecimal(unidades));
        renglon.put("precioUnitario", new BigDecimal("1.00"));
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("clave", clave());
        cuerpo.put("renglones", List.of(renglon));
        cuerpo.put("totalMostrado", new BigDecimal(unidades));
        cuerpo.put("formaPagoId", formaPagoId);
        cuerpo.put("clienteId", clienteId);
        if (autoriza) {
            cuerpo.put("autorizaExceso", true);
        }
        return cuerpo;
    }

    private Callable<Respuesta> ventaACuenta(Escenario e, String token, int unidades, long clienteId, boolean autoriza) {
        return post("/api/ventas", token, venta(e, unidades, clienteId, formaDe(e.tienda(), "CUENTA_CORRIENTE"), autoriza));
    }

    private BigDecimal saldo(long clienteId) {
        return jdbc.queryForObject("select saldo from cliente where id = ?", BigDecimal.class, clienteId);
    }

    private long movimientos(long clienteId) {
        return jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente where cliente_id = ?", Long.class,
                clienteId);
    }

    private long ultimoNumero(Tienda t) {
        List<Long> numeros = jdbc.queryForList("select ultimo_numero from numerador_comprobante where ferreteria_id = ?",
                Long.class, t.id());
        return numeros.isEmpty() ? 0 : numeros.get(0);
    }

    /**
     * Los deadlocks que InnoDB detectó desde que arrancó (métrica {@code lock_deadlocks}, activa por defecto). Los reintentos de
     * VentaService tapan un deadlock con un 201, así que mirar solo los estados HTTP no alcanza para probar el orden de locks:
     * acá se exige que ni siquiera hubo uno.
     */
    private long deadlocksDeInnoDb() {
        return jdbcRoot().queryForObject("select `count` from information_schema.innodb_metrics where name = 'lock_deadlocks'",
                Long.class);
    }

    private static List<Integer> estados(List<Respuesta> respuestas) {
        return respuestas.stream().map(Respuesta::status).toList();
    }

    private static long cuantos(List<Respuesta> respuestas, int estado) {
        return respuestas.stream().filter(r -> r.status() == estado).count();
    }

    // ------------------------------------------------------------------ (a) y (b): el límite bajo carga

    @Test
    @DisplayName("(a) ocho ventas de $ 300 a la vez con límite $ 1.000 y un Vendedor: exactamente 3 entran y 5 son 422 LIMITE; saldo 900")
    void ochoVentasConLimite() throws Exception {
        Escenario e = escenario();
        long c = cliente(e.tienda(), "1000.00");
        List<Callable<Respuesta>> pedidos = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            pedidos.add(ventaACuenta(e, e.vendedor(), 300, c, false));
        }

        List<Respuesta> respuestas = enParalelo(pedidos);

        assertThat(cuantos(respuestas, 201)).isEqualTo(3);
        assertThat(cuantos(respuestas, 422)).isEqualTo(5);
        assertThat(respuestas.stream().filter(r -> r.status() == 422).map(r -> r.cuerpo().get("motivo").asText()))
                .containsOnly("LIMITE_CUENTA_CORRIENTE");
        assertThat(saldo(c)).isEqualByComparingTo("900.00");
        assertThat(movimientos(c)).isEqualTo(3);
        assertThat(comprobantes(e.tienda())).isEqualTo(3);
        InvarianteCuenta.assertInvariante(jdbc, c);
    }

    @Test
    @DisplayName("(b) lo mismo con el Dueño autorizando: las 8 entran, 5 quedan marcadas con excedio_limite y autorizado_por, saldo 2.400")
    void ochoVentasAutorizadasPorElDueno() throws Exception {
        Escenario e = escenario();
        long c = cliente(e.tienda(), "1000.00");
        long idDueno = jdbc.queryForObject("select id from usuario where email = ?", Long.class, e.tienda().emailDueno());
        List<Callable<Respuesta>> pedidos = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            pedidos.add(ventaACuenta(e, e.dueno(), 300, c, true));
        }

        List<Respuesta> respuestas = enParalelo(pedidos);

        assertThat(estados(respuestas)).containsOnly(201);
        assertThat(saldo(c)).isEqualByComparingTo("2400.00");
        assertThat(movimientos(c)).isEqualTo(8);
        assertThat(jdbc.queryForObject(
                "select count(*) from movimiento_cuenta_cliente where cliente_id = ? and excedio_limite = true and autorizado_por = ?",
                Long.class, c, idDueno)).isEqualTo(5);
        assertThat(jdbc.queryForObject(
                "select count(*) from movimiento_cuenta_cliente where cliente_id = ? and excedio_limite = false and autorizado_por is null",
                Long.class, c)).isEqualTo(3);
        InvarianteCuenta.assertInvariante(jdbc, c);
    }

    // ------------------------------------------------------------------ (c) el orden canónico de locks

    @Test
    @DisplayName("(c) 12 hilos mezclan ventas a cuenta y en efectivo (con el mismo cliente): ningún 500 ni deadlock filtrado, todo 201 o 422")
    void mezclaConYSinCuenta() throws Exception {
        Escenario e = escenario();
        long c = cliente(e.tienda(), "1000.00");
        long efectivo = formaDe(e.tienda(), "EFECTIVO");
        long deadlocksAntes = deadlocksDeInnoDb();
        List<Callable<Respuesta>> pedidos = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            pedidos.add(i % 2 == 0
                    ? ventaACuenta(e, e.vendedor(), 300, c, false)
                    : post("/api/ventas", e.vendedor(), venta(e, 50, c, efectivo, false)));
        }

        List<Respuesta> respuestas = enParalelo(pedidos);

        assertThat(estados(respuestas)).containsOnly(201, 422);
        assertThat(deadlocksDeInnoDb()).as("deadlocks que detectó InnoDB durante la corrida").isEqualTo(deadlocksAntes);
        assertThat(cuantos(respuestas, 422)).isEqualTo(3); // de las 6 a cuenta solo entran 3 (900 de 1.000)
        assertThat(saldo(c)).isEqualByComparingTo("900.00");
        assertThat(comprobantes(e.tienda())).isEqualTo(cuantos(respuestas, 201)).isEqualTo(9);
        InvarianteCuenta.assertInvariante(jdbc, c);
    }

    @Test
    @DisplayName("(c') humo de deadlock: remitos y presupuestos emitidos a la vez para el mismo cliente mientras corren ventas a cuenta: ningún 500")
    void emisionesYVentasDelMismoCliente() throws Exception {
        Escenario e = escenario();
        long c = cliente(e.tienda(), "1000.00");
        long deadlocksAntes = deadlocksDeInnoDb();
        List<Callable<Respuesta>> ventas = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ventas.add(ventaACuenta(e, e.vendedor(), 300, c, false));
        }
        List<Callable<Respuesta>> emisiones = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            emisiones.add(post("/api/remitos", e.vendedor(), documento(e, c)));
            emisiones.add(post("/api/presupuestos", e.vendedor(), documento(e, c)));
        }
        List<Callable<Respuesta>> todos = new ArrayList<>(ventas);
        todos.addAll(emisiones);

        List<Respuesta> respuestas = enParalelo(todos);

        List<Respuesta> deVentas = respuestas.subList(0, ventas.size());
        List<Respuesta> deEmisiones = respuestas.subList(ventas.size(), respuestas.size());
        assertThat(estados(deVentas)).containsOnly(201, 422);
        assertThat(estados(deEmisiones)).containsOnly(201);
        assertThat(deadlocksDeInnoDb()).as("deadlocks que detectó InnoDB durante la corrida").isEqualTo(deadlocksAntes);
        assertThat(cuantos(deVentas, 201)).isEqualTo(3);
        assertThat(saldo(c)).isEqualByComparingTo("900.00");
        InvarianteCuenta.assertInvariante(jdbc, c);
    }

    /** El cuerpo de un remito o presupuesto de un renglón a nombre del cliente. */
    private Map<String, Object> documento(Escenario e, long clienteId) {
        Map<String, Object> renglon = new LinkedHashMap<>();
        renglon.put("articuloId", e.articulo());
        renglon.put("cantidad", new BigDecimal("10"));
        renglon.put("precioUnitario", new BigDecimal("1.00"));
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("clave", clave());
        cuerpo.put("renglones", List.of(renglon));
        cuerpo.put("totalMostrado", new BigDecimal("10.00"));
        cuerpo.put("clienteId", clienteId);
        return cuerpo;
    }

    // ------------------------------------------------------------------ (d) doble envío con límite justo

    @Test
    @DisplayName("(d) la misma venta enviada dos veces a la vez con límite justo ($ 600 sobre $ 1.000): un 201 y un 200, nunca un 422; un solo movimiento")
    void dobleEnvioConLimiteJusto() throws Exception {
        Escenario e = escenario();
        long c = cliente(e.tienda(), "1000.00");
        Map<String, Object> venta = venta(e, 600, c, formaDe(e.tienda(), "CUENTA_CORRIENTE"), false);

        List<Respuesta> respuestas = enParalelo(List.of(
                post("/api/ventas", e.vendedor(), venta), post("/api/ventas", e.vendedor(), venta)));

        assertThat(estados(respuestas)).containsExactlyInAnyOrder(201, 200);
        assertThat(respuestas.get(0).cuerpo().get("ventaId")).isEqualTo(respuestas.get(1).cuerpo().get("ventaId"));
        assertThat(movimientos(c)).isEqualTo(1);
        assertThat(saldo(c)).isEqualByComparingTo("600.00");
        assertThat(comprobantes(e.tienda())).isEqualTo(1);
        InvarianteCuenta.assertInvariante(jdbc, c);
    }

    // ------------------------------------------------------------------ (h) atomicidad

    @Test
    @DisplayName("(h) si el asiento falla no queda nada: ni comprobante, ni descuento de stock, ni saldo, ni número consumido")
    void atomicidadSiFallaElAsiento() throws Exception {
        Escenario e = escenario();
        long c = cliente(e.tienda(), "5000.00");
        BigDecimal stockAntes = stock(e.articulo());
        long numeroAntes = ultimoNumero(e.tienda());
        // El bean viene envuelto en el proxy transaccional (MANDATORY): se stubbea sobre el espía de adentro, no sobre el proxy.
        CuentaCorrienteService espia = AopTestUtils.getUltimateTargetObject(cuentaCorriente);
        doThrow(new IllegalStateException("falla simulada del asiento")).when(espia).asentarVenta(any());

        List<Respuesta> respuestas = enParalelo(List.of(ventaACuenta(e, e.vendedor(), 700, c, false)));

        assertThat(respuestas.get(0).status()).isEqualTo(500);
        assertThat(comprobantes(e.tienda())).isZero();
        assertThat(stock(e.articulo())).isEqualByComparingTo(stockAntes);
        assertThat(movimientos(e.tienda())).isZero(); // movimientos de stock
        assertThat(saldo(c)).isEqualByComparingTo("0");
        assertThat(movimientos(c)).isZero();
        assertThat(ultimoNumero(e.tienda())).isEqualTo(numeroAntes);
        InvarianteCuenta.assertInvariante(jdbc, c);

        // sin la falla la misma venta entra y toma el número 1: el que se consumió y se revirtió no dejó un hueco
        org.mockito.Mockito.reset(espia);
        List<Respuesta> despues = enParalelo(List.of(ventaACuenta(e, e.vendedor(), 700, c, false)));
        assertThat(despues.get(0).status()).isEqualTo(201);
        assertThat(despues.get(0).cuerpo().get("numero").asLong()).isEqualTo(numeroAntes + 1);
        assertThat(saldo(c)).isEqualByComparingTo("700.00");
        InvarianteCuenta.assertInvariante(jdbc, c);
    }
}
