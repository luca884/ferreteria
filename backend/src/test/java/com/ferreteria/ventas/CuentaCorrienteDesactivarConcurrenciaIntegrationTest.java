package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.clientes.service.CuentaCorrienteService;
import com.ferreteria.support.BarreraDeLocks;
import com.ferreteria.support.BarreraDeLocks.Hito;
import com.ferreteria.support.BarreraDeLocks.HolderJdbc;
import com.ferreteria.support.InvarianteCuenta;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * CC-010 y CC-020 (f) del design, contra MySQL real y con hilos de verdad por HTTP: desactivar a un cliente y venderle a cuenta
 * corriente a la vez. Las dos operaciones toman la misma fila del cliente (FOR UPDATE), así que entra una sola: o la venta
 * entra y la desactivación recibe 422 por saldo, o se desactiva (saldo 0) y la venta recibe 422 «desactivado». Nunca las dos,
 * ni un cliente desactivado con deuda.
 *
 * <p>CCT3-312 (paga la deuda W2 de tramo 2): el test viejo repetía 20 rondas con un jitter de {@code parkNanos} y no garantizaba
 * pasar por los dos órdenes. Ahora hay UN test por orden, sin tiempos: se sincronizan con {@link BarreraDeLocks} (el hilo B
 * está en LOCK WAIT, observable en MySQL) y con hitos.
 * <ul>
 *   <li>(A) la venta queda PAUSADA dentro de {@code asentarVenta} (ya tiene al cliente en X, sin confirmar, vía un
 *       {@code @SpyBean}); se lanza la desactivación y se espera a que esté bloqueada; se libera la venta.
 *   <li>(B) una conexión cruda («holder») tiene al cliente en X con {@code activo = 0} sin confirmar; se lanza la venta y se
 *       espera a que esté bloqueada; se confirma el holder.
 * </ul>
 */
class CuentaCorrienteDesactivarConcurrenciaIntegrationTest extends VentasTestBase {

    private static final String DESACTIVADO = "El cliente está desactivado.";
    private static final String CON_SALDO = "No se puede desactivar: el cliente tiene saldo en cuenta corriente.";

    /** Pausa la venta del hilo registrado dentro de {@code asentarVenta}: ya tomó caja y cliente, y no confirmó. */
    @SpyBean
    CuentaCorrienteService servicioEspiado;

    private record Respuesta(int status, JsonNode cuerpo) {}

    private final AtomicReference<Thread> hiloAPausar = new AtomicReference<>();

    private long cliente(Tienda t) {
        String razon = "Cliente " + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into cliente (ferreteria_id, razon_social, limite_cuenta_corriente) values (?, ?, 1000.00)",
                t.id(), razon);
        return jdbc.queryForObject("select id from cliente where ferreteria_id = ? and razon_social = ?", Long.class,
                t.id(), razon);
    }

    private Callable<Respuesta> desactivar(String token, long clienteId) {
        return () -> {
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

    private void pausarLaVenta(Hito llego, Hito suelta) {
        CuentaCorrienteService espia = AopTestUtils.getUltimateTargetObject(servicioEspiado);
        doAnswer(invocacion -> {
            if (Thread.currentThread() == hiloAPausar.get()) {
                llego.avisar();
                suelta.esperar();
            }
            return invocacion.callRealMethod();
        }).when(espia).asentarVenta(any());
    }

    private static Respuesta resultado(Future<Respuesta> futuro) throws Exception {
        return futuro.get(60, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("(A) la venta tiene al cliente y no confirmó; desactivar queda bloqueada → al soltar, la venta entra (201) y desactivar es 422 por saldo ≠ 0")
    void ventaPrimeroDesactivarDespues() throws Exception {
        Tienda t = tienda();
        activarFormaCuentaCorriente(t);
        long articulo = articulo(t, "Tornillo", "UNIDAD", "1.00", "10000000");
        String dueno = login(t.emailDueno());
        long c = cliente(t);
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        Hito llego = new Hito();
        Hito suelta = new Hito();
        pausarLaVenta(llego, suelta);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Respuesta> venta = pool.submit(() -> {
                hiloAPausar.set(Thread.currentThread());
                return ventaACuenta(t, articulo, dueno, c).call();
            });
            llego.esperar(); // la venta tiene caja (S) y cliente (X), está en asentarVenta y no confirmó
            Future<Respuesta> desactivacion = pool.submit(desactivar(dueno, c));
            barrera.esperarBloqueados(1); // la desactivación está en LOCK WAIT sobre la fila del cliente
            suelta.avisar();

            assertThat(resultado(venta).status()).isEqualTo(201);
            Respuesta d = resultado(desactivacion);
            assertThat(d.status()).isEqualTo(422);
            assertThat(d.cuerpo().get("message").asText()).isEqualTo(CON_SALDO);
        } finally {
            suelta.avisar();
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("select activo from cliente where id = ?", Boolean.class, c)).isTrue();
        assertThat(jdbc.queryForObject("select saldo from cliente where id = ?", BigDecimal.class, c)).isEqualByComparingTo("100.00");
        barrera.assertSinDeadlocks(deadlocksAntes);
        InvarianteCuenta.assertInvariante(jdbc, c);
    }

    @Test
    @DisplayName("(B) alguien desactivó al cliente y no confirmó; la venta queda bloqueada → al confirmar, la venta es 422 «desactivado» y el cliente no tiene saldo")
    void desactivarPrimeroVentaDespues() throws Exception {
        Tienda t = tienda();
        activarFormaCuentaCorriente(t);
        long articulo = articulo(t, "Tornillo", "UNIDAD", "1.00", "10000000");
        String dueno = login(t.emailDueno());
        long c = cliente(t);
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        // el mismo SQL que cambiarActivo: toma al cliente (FOR UPDATE) y lo desactiva, sin confirmar
        HolderJdbc holder = BarreraDeLocks.holderJdbc("select id from cliente where id = " + c + " for update",
                "update cliente set activo = 0 where id = " + c);
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<Respuesta> venta = pool.submit(ventaACuenta(t, articulo, dueno, c));
            barrera.esperarBloqueados(1); // la venta está en LOCK WAIT sobre la fila del cliente
            holder.commit();

            Respuesta v = resultado(venta);
            assertThat(v.status()).isEqualTo(422);
            assertThat(v.cuerpo().get("message").asText()).isEqualTo(DESACTIVADO);
        } finally {
            holder.rollback();
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("select activo from cliente where id = ?", Boolean.class, c)).isFalse();
        assertThat(jdbc.queryForObject("select saldo from cliente where id = ?", BigDecimal.class, c)).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente where cliente_id = ?", Long.class, c)).isZero();
        barrera.assertSinDeadlocks(deadlocksAntes);
        InvarianteCuenta.assertInvariante(jdbc, c);
    }
}
