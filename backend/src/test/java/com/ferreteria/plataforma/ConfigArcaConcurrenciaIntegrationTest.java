package com.ferreteria.plataforma;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.support.BarreraDeLocks;
import com.ferreteria.support.BarreraDeLocks.HolderJdbc;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * El panel del super admin contra MySQL real y con hilos de verdad (molde de {@code VentaConcurrenciaIntegrationTest}): las
 * tres acciones toman el lock de la fila de {@code config_arca} antes de decidir, así que dos a la vez se serializan y la
 * segunda decide sobre lo que dejó la primera. Sin tiempos: un holder JDBC sostiene el lock, el test espera a que las dos
 * estén en LOCK WAIT ({@link BarreraDeLocks}) y recién ahí lo suelta. Cada test usa sus ferreterías; delta de deadlocks 0.
 */
class ConfigArcaConcurrenciaIntegrationTest extends PlataformaTestBase {

    private static final String SIN_MARCA = "El simulador solo se asigna a una ferretería marcada «de prueba».";
    private static final String MARCA_CON_SIMULADOR =
            "No se puede sacar la marca «de prueba» mientras la ferretería use el simulador: quitale el simulador primero.";

    private String admin;
    private BarreraDeLocks barrera;

    @BeforeEach
    void entrar() throws Exception {
        admin = tokenSuperAdmin();
        barrera = new BarreraDeLocks(jdbcRoot());
    }

    private record Respuesta(int status, String mensaje) {}

    private Callable<Respuesta> patch(long ferreteria, String ruta, String cuerpo) {
        return () -> {
            MockHttpServletResponse r = patchJson(BASE + "/" + ferreteria + "/" + ruta, admin, cuerpo).andReturn()
                    .getResponse();
            return new Respuesta(r.getStatus(), objectMapper.readTree(r.getContentAsString(StandardCharsets.UTF_8))
                    .path("message").asText(null));
        };
    }

    /**
     * Abre el holder, lanza los pedidos, espera a que los dos estén en LOCK WAIT sobre {@code tabla}, suelta el holder y
     * devuelve las respuestas en el orden de los pedidos.
     */
    private List<Respuesta> trasElHolder(String sqlDelHolder, String tabla, List<Callable<Respuesta>> pedidos)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(pedidos.size());
        HolderJdbc holder = BarreraDeLocks.holderJdbc(sqlDelHolder);
        try {
            List<Future<Respuesta>> futuros = new ArrayList<>();
            for (Callable<Respuesta> pedido : pedidos) {
                futuros.add(pool.submit(pedido));
            }
            barrera.esperarBloqueados(pedidos.size());
            assertThat(barrera.objetosEnEspera()).as("lo que esperan los pedidos").containsExactly(tabla);
            holder.rollback();
            List<Respuesta> respuestas = new ArrayList<>();
            for (Future<Respuesta> futuro : futuros) {
                respuestas.add(futuro.get(60, TimeUnit.SECONDS));
            }
            return respuestas;
        } finally {
            holder.rollback();
            pool.shutdownNow();
        }
    }

    private Map<String, Object> fila(long ferreteria) {
        return jdbc.queryForMap("select de_prueba, ambiente, punto_venta from config_arca where ferreteria_id = ?",
                ferreteria);
    }

    @Test
    @DisplayName("sacar la marca y asignar el simulador a la vez: una gana, la otra da 422 y la fila queda como dice el 200")
    void sacarLaMarcaYAsignarElSimuladorALaVez() throws Exception {
        long f = crearFerreteria("Carrera");
        patchJson(BASE + "/" + f + "/de-prueba", admin, json("dePrueba", true)).andReturn();
        long deadlocks = barrera.deadlocksAntes();

        List<Respuesta> respuestas = trasElHolder(
                "select id from config_arca where ferreteria_id = " + f + " for update", "config_arca",
                List.of(patch(f, "de-prueba", json("dePrueba", false)), patch(f, "simulador", json("simulador", true))));

        Respuesta sacar = respuestas.get(0);
        Respuesta asignar = respuestas.get(1);
        assertThat(List.of(sacar.status(), asignar.status())).containsExactlyInAnyOrder(200, 422);
        if (sacar.status() == 200) {
            assertThat(asignar.mensaje()).isEqualTo(SIN_MARCA);
            assertThat(fila(f)).containsEntry("de_prueba", false).containsEntry("ambiente", null)
                    .containsEntry("punto_venta", null);
        } else {
            assertThat(sacar.mensaje()).isEqualTo(MARCA_CON_SIMULADOR);
            assertThat(fila(f)).containsEntry("de_prueba", true).containsEntry("ambiente", "SIMULADOR")
                    .containsEntry("punto_venta", 1);
        }
        barrera.assertSinDeadlocks(deadlocks);
    }

    @Test
    @DisplayName("habilitar la facturación y quitar el simulador a la vez: una gana, la otra da 422 y la fila queda como dice el 200")
    void habilitarYQuitarElSimuladorALaVez() throws Exception {
        long f = crearFerreteria("Carrera facturación");
        jdbc.update("update ferreteria set domicilio_comercial = 'Av. Luro 1', inicio_actividades = '2015-03-01', "
                + "ingresos_brutos = '901-123456-7' where id = ?", f);
        patchJson(BASE + "/" + f + "/de-prueba", admin, json("dePrueba", true)).andReturn();
        patchJson(BASE + "/" + f + "/simulador", admin, json("simulador", true)).andReturn();
        long deadlocks = barrera.deadlocksAntes();

        List<Respuesta> respuestas = trasElHolder(
                "select id from config_arca where ferreteria_id = " + f + " for update", "config_arca",
                List.of(patch(f, "facturacion", json("habilitada", true)), patch(f, "simulador", json("simulador", false))));

        Respuesta habilitar = respuestas.get(0);
        Respuesta quitar = respuestas.get(1);
        assertThat(List.of(habilitar.status(), quitar.status())).containsExactlyInAnyOrder(200, 422);
        Map<String, Object> fila = jdbc.queryForMap(
                "select ambiente, punto_venta, facturacion_habilitada from config_arca where ferreteria_id = ?", f);
        if (habilitar.status() == 200) {
            assertThat(quitar.mensaje()).isEqualTo(
                    "Primero deshabilitá la facturación: no se puede quitar el ambiente mientras esté habilitada.");
            assertThat(fila).containsEntry("ambiente", "SIMULADOR").containsEntry("facturacion_habilitada", true);
        } else {
            assertThat(habilitar.mensaje()).isEqualTo(
                    "Para habilitar la facturación, la ferretería necesita un ambiente de ARCA y un punto de venta.");
            assertThat(fila).containsEntry("ambiente", null).containsEntry("facturacion_habilitada", false);
        }
        barrera.assertSinDeadlocks(deadlocks);
    }

    @Test
    @DisplayName("dos «marcar de prueba» a la vez sobre una ferretería sin fila: los dos 200 y una sola fila, sin 409")
    void dosPrimerosToquesALaVez() throws Exception {
        long f = crearFerreteria("Primer toque");
        long deadlocks = barrera.deadlocksAntes();

        List<Respuesta> respuestas = trasElHolder("select id from ferreteria where id = " + f + " for update",
                "ferreteria", List.of(patch(f, "de-prueba", json("dePrueba", true)),
                        patch(f, "de-prueba", json("dePrueba", true))));

        assertThat(respuestas).extracting(Respuesta::status).containsExactly(200, 200);
        assertThat(jdbc.queryForObject("select count(*) from config_arca where ferreteria_id = ?", Long.class, f))
                .isEqualTo(1L);
        assertThat(fila(f)).containsEntry("de_prueba", true);
        barrera.assertSinDeadlocks(deadlocks);
    }
}
