package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * cu-12-numeracion-ajustes S1, bajo carga: Recargos y Descuentos en paralelo, en dos ferreterías, nunca repiten ni saltean un
 * número dentro de su tipo, ningún pedido falla y la cuenta de cada cliente cierra. Nace de la prueba del verificador de la V40.
 */
class AjusteConcurrenciaIntegrationTest extends CuentaCorrienteTestBase {

    private static final int CLIENTES_POR_FERRETERIA = 6;
    private static final int PEDIDOS_POR_FERRETERIA = 40;

    @Override
    protected String prefijoDeCuit() {
        return "30-8977";
    }

    private record Escenario(Ferreteria f, String dueno, List<Long> clientes) {}

    @Test
    @DisplayName("S1: en paralelo, cada (ferretería, tipo) numera 1..N sin repetidos ni huecos y sin pedidos fallidos")
    void ajustesMezcladosEnParalelo() throws Exception {
        List<Escenario> escenarios = List.of(escenario(), escenario());
        List<String> fallidos = Collections.synchronizedList(new ArrayList<>());
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch largada = new CountDownLatch(1);
        List<Future<?>> pedidos = new ArrayList<>();
        try {
            for (int n = 0; n < PEDIDOS_POR_FERRETERIA * escenarios.size(); n++) {
                Escenario e = escenarios.get(n % escenarios.size());
                int k = n / escenarios.size();
                String tipo = k % 2 == 0 ? "RECARGO" : "DESCUENTO";
                long cliente = e.clientes().get(k % CLIENTES_POR_FERRETERIA);
                String cuerpo = "{\"clave\":\"" + UUID.randomUUID() + "\",\"tipo\":\"" + tipo
                        + "\",\"importe\":1.00,\"motivo\":\"Prueba concurrente\",\"imputaciones\":[]}";
                pedidos.add(pool.submit(() -> {
                    largada.await();
                    MockHttpServletResponse r = postJson("/api/clientes/" + cliente + "/cuenta-corriente/ajustes", e.dueno(),
                            cuerpo).andReturn().getResponse();
                    if (r.getStatus() != 201) {
                        fallidos.add(r.getStatus() + " " + r.getContentAsString(StandardCharsets.UTF_8));
                    }
                    return null;
                }));
            }
            largada.countDown();
            for (Future<?> pedido : pedidos) {
                pedido.get(180, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(fallidos).isEmpty();
        List<Long> deUnoAN = LongStream.rangeClosed(1, PEDIDOS_POR_FERRETERIA / 2).boxed().toList();
        for (Escenario e : escenarios) {
            for (String tipo : List.of("RECARGO", "DESCUENTO")) {
                assertThat(jdbc.queryForList("select numero from ajuste_cuenta where ferreteria_id = ? and tipo = ? "
                        + "order by numero", Long.class, e.f().id(), tipo)).as(tipo).isEqualTo(deUnoAN);
            }
            e.clientes().forEach(this::assertInvariante);
        }
    }

    private Escenario escenario() throws Exception {
        Ferreteria f = ferreteria();
        String dueno = login(logins(f).dueno());
        List<Long> clientes = new ArrayList<>();
        for (int i = 0; i < CLIENTES_POR_FERRETERIA; i++) {
            clientes.add(cliente(f, "1000000.00"));
        }
        return new Escenario(f, dueno, clientes);
    }
}
