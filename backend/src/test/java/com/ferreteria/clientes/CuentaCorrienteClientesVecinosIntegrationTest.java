package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.ResultActions;

/**
 * GDS-20, tramo 3b-2 (S6): clientes VECINOS de la misma ferretería a la vez. Una venta a cuenta corriente de un cliente con
 * adelanto consume su crédito con una lectura actual sobre {@code ix_imputacion_cliente} y un cobro imputado del cliente de al
 * lado inserta en el mismo índice. En REPEATABLE READ esa lectura deja un gap residual que llega al cliente siguiente, así que
 * InnoDB puede elegir una víctima de deadlock entre clientes distintos (lo midió la verificación con {@code lock_deadlocks}).
 * La venta, la cobranza y el ajuste reintentan la transacción entera: ningún pedido falla y la cuenta de cada cliente cierra.
 * CUIT: bloque 30-8412.
 */
class CuentaCorrienteClientesVecinosIntegrationTest extends CuentaCorrienteTestBase {

    private static final int RONDAS = 3;
    private static final int CLIENTES = 8;
    private static final int PEDIDOS = 32;

    @Override
    protected String prefijoDeCuit() {
        return "30-8412";
    }

    private static String clave() {
        return UUID.randomUUID().toString();
    }

    private String venta(long clienteId, long articuloId, long formaCuentaCorriente) {
        return "{\"clave\":\"" + clave() + "\",\"clienteId\":" + clienteId + ",\"renglones\":[{\"articuloId\":" + articuloId
                + ",\"cantidad\":1,\"precioUnitario\":100.00}],\"totalMostrado\":100.00,\"pagos\":[{\"formaPagoId\":"
                + formaCuentaCorriente + ",\"monto\":100.00}]}";
    }

    @Test
    @DisplayName("ventas que consumen crédito y cobros imputados de clientes vecinos, a la vez: ninguno falla y cada cuenta cierra")
    void ventasYCobrosDeClientesVecinos() throws Exception {
        List<String> fallidos = Collections.synchronizedList(new ArrayList<>());
        List<Long> todos = new ArrayList<>();
        for (int ronda = 0; ronda < RONDAS; ronda++) {
            Ferreteria f = ferreteria();
            Logins l = logins(f);
            cajaAbiertaHoy(f.id());
            String dueno = login(l.dueno());
            String vendedor = login(l.vendedor());
            long cuentaCorriente = jdbc.queryForObject(
                    "select id from forma_pago where ferreteria_id = ? and medio = 'CUENTA_CORRIENTE'", Long.class, f.id());
            String codigo = "VEC-" + ronda;
            jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, alicuota_iva, "
                    + "unidad, stock_actual, stock_minimo, activo) values (?, ?, 'Tornillo', 1.00, 100.00, 'IVA_21', 'UNIDAD', "
                    + "100000, 0, true)", f.id(), codigo);
            long articulo = jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?",
                    Long.class, f.id(), codigo);
            // Pares con adelanto (las ventas consumen crédito); impares con 4 ventas a cuenta (los cobros les imputan).
            List<Long> clientes = new ArrayList<>();
            List<List<Long>> deudas = new ArrayList<>();
            for (int i = 0; i < CLIENTES; i++) {
                long cliente = cliente(f, "1000000.00");
                clientes.add(cliente);
                List<Long> ventas = new ArrayList<>();
                if (i % 2 == 0) {
                    postJson("/api/cobranzas", dueno, "{\"clave\":\"" + clave() + "\",\"clienteId\":" + cliente
                            + ",\"medio\":\"EFECTIVO\",\"total\":1000,\"imputaciones\":[]}").andExpect(status().isCreated());
                } else {
                    for (int j = 0; j < 4; j++) {
                        String cuerpo = postJson("/api/ventas", vendedor, venta(cliente, articulo, cuentaCorriente))
                                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
                        ventas.add(objectMapper.readTree(cuerpo).get("ventaId").asLong());
                    }
                }
                deudas.add(ventas);
            }
            todos.addAll(clientes);

            ExecutorService pool = Executors.newFixedThreadPool(16);
            CountDownLatch largada = new CountDownLatch(1);
            List<Future<Integer>> pedidos = new ArrayList<>();
            for (int n = 0; n < PEDIDOS; n++) {
                int indice = n % CLIENTES;
                long cliente = clientes.get(indice);
                long ventaADeber = indice % 2 == 1 ? deudas.get(indice).get((n / CLIENTES) % 4) : 0;
                pedidos.add(pool.submit(() -> {
                    largada.await();
                    ResultActions r = indice % 2 == 0
                            ? postJson("/api/ventas", vendedor, venta(cliente, articulo, cuentaCorriente))
                            : postJson("/api/cobranzas", dueno, "{\"clave\":\"" + clave() + "\",\"clienteId\":" + cliente
                                    + ",\"medio\":\"EFECTIVO\",\"total\":100,\"imputaciones\":[{\"comprobanteId\":" + ventaADeber
                                    + ",\"importe\":100}]}");
                    int estado = r.andReturn().getResponse().getStatus();
                    if (estado >= 300) {
                        fallidos.add((indice % 2 == 0 ? "venta " : "cobro ") + estado + " "
                                + r.andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
                    }
                    return estado;
                }));
            }
            largada.countDown();
            for (Future<Integer> pedido : pedidos) {
                pedido.get(120, TimeUnit.SECONDS);
            }
            pool.shutdown();
        }

        assertThat(fallidos).isEmpty();
        todos.forEach(this::assertInvariante);
    }
}
