package com.ferreteria.facturacion.arca;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.support.BarreraDeLocks;
import com.ferreteria.support.BarreraDeLocks.Largada;
import com.ferreteria.tenant.TenantContext;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S2, con hilos de verdad contra MySQL real: el TA se pide de a uno por (ferretería, servicio, ambiente), también entre las
 * dos JVM de un deploy, porque el lease está en la base. Dos instancias de {@link TicketsDeAcceso} (las dos «JVM», sin nada
 * compartido en memoria) reciben 20 pedidos a la vez, que largan juntos con un latch ({@link Largada}), y el WSAA falso
 * tarda para que se crucen. El resultado no depende de cuánto tarde: el que toma el lease relee la fila con el lock antes de
 * llamar, así que un pedido que llega tarde ve el TA guardado. Delta de deadlocks 0.
 */
class TicketsDeAccesoConcurrenciaIntegrationTest extends TicketsDeAccesoTestBase {

    private static final int PEDIDOS = 20;
    /** Lo más que se espera a un hilo: una espera que vence es un fallo, no un cuelgue. */
    private static final Duration TOPE = Duration.ofSeconds(60);

    @Test
    @DisplayName("S2: 20 pedidos simultáneos desde dos instancias dan un solo loginCms y el mismo token para los 20")
    void veintePedidosDosInstanciasUnSoloLoginCms() throws Exception {
        wsaa.demora = Duration.ofMillis(300);
        List<TicketsDeAcceso> jvms = List.of(instancia(), instancia());
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocks = barrera.deadlocksAntes();
        long ferreteria = ferreteriaId;
        ExecutorService pool = Executors.newFixedThreadPool(PEDIDOS);
        Largada largada = new Largada(PEDIDOS);
        try {
            List<Future<TicketDeAcceso>> futuros = new ArrayList<>();
            for (int i = 0; i < PEDIDOS; i++) {
                TicketsDeAcceso jvm = jvms.get(i % 2);
                futuros.add(pool.submit(() -> {
                    TenantContext.setCurrentFerreteriaId(ferreteria);
                    try {
                        largada.listoYEsperar();
                        return jvm.ticket(Ambiente.HOMOLOGACION, WSFE);
                    } finally {
                        TenantContext.clear();
                    }
                }));
            }
            largada.esperarListos(TOPE);
            largada.largar();
            List<TicketDeAcceso> recibidos = new ArrayList<>();
            for (Future<TicketDeAcceso> futuro : futuros) {
                recibidos.add(futuro.get(TOPE.toSeconds(), TimeUnit.SECONDS));
            }

            assertThat(wsaa.llamadas).hasValue(1);
            assertThat(recibidos).hasSize(PEDIDOS).extracting(TicketDeAcceso::token).containsOnly(wsaa.tokens.get(0));
            assertThat(fila(ferreteriaId)).containsEntry("lease_dueno", null);
            barrera.assertSinDeadlocks(deadlocks);
        } finally {
            pool.shutdownNow();
        }
    }
}
