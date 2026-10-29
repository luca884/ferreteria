package com.ferreteria.facturacion.credencial;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.facturacion.api.FacturacionParaVenta;
import com.ferreteria.facturacion.api.FacturacionParaVenta.ReceptorVenta;
import com.ferreteria.support.BarreraDeLocks;
import com.ferreteria.support.BarreraDeLocks.HolderJdbc;
import com.ferreteria.support.BarreraDeLocks.Hito;
import com.ferreteria.support.BarreraDeLocks.Largada;
import com.ferreteria.tenant.TenantContext;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * La credencial de ARCA del Dueño con hilos de verdad contra MySQL real (rama 5, S14, S15 y S16): todo cambio empieza
 * tomando el lock exclusivo de la fila de {@code config_arca} de la ferretería ({@code CredencialArcaService.bloquear}), así
 * que dos a la vez se serializan, queda a lo sumo una credencial activa y un pedido pendiente, y una venta que sostiene el
 * {@code FOR SHARE} de esa fila hace esperar al Dueño sin deadlock. Sin esperas por tiempo: los hilos largan juntos con un
 * latch ({@link Largada}) y la venta se suelta cuando los dos cambios del Dueño están en LOCK WAIT ({@link BarreraDeLocks}).
 * Cada test usa sus ferreterías; delta de deadlocks 0.
 */
class CredencialArcaConcurrenciaIntegrationTest extends CredencialArcaTestBase {

    /** Lo más que se espera una respuesta o un hilo: una espera que vence es un fallo, no un cuelgue. */
    private static final Duration TOPE = Duration.ofSeconds(60);

    @Autowired
    FacturacionParaVenta facturacion;
    @Autowired
    PlatformTransactionManager transacciones;

    private BarreraDeLocks barrera;

    @BeforeEach
    void barrera() {
        barrera = new BarreraDeLocks(jdbcRoot());
    }

    /** El status de la respuesta. */
    private static int estado(ResultActions resultado) throws Exception {
        return resultado.andReturn().getResponse().getStatus();
    }

    /** Cuántas credenciales de la ferretería hay en ese estado. */
    private int cuantas(Ferreteria f, String estado) {
        return jdbc.queryForObject("select count(*) from credencial_arca where ferreteria_id = ? and estado = ?",
                Integer.class, f.id(), estado);
    }

    /**
     * Corre las tareas a la vez: cada hilo avisa que está listo y espera la largada común, y el test la da cuando están todos.
     * Devuelve los status en el orden de las tareas.
     */
    private List<Integer> paralelo(List<Callable<Integer>> tareas) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tareas.size());
        Largada largada = new Largada(tareas.size());
        try {
            List<Future<Integer>> futuros = new ArrayList<>();
            for (Callable<Integer> tarea : tareas) {
                futuros.add(pool.submit(() -> {
                    largada.listoYEsperar();
                    return tarea.call();
                }));
            }
            largada.esperarListos(TOPE);
            largada.largar();
            List<Integer> estados = new ArrayList<>();
            for (Future<Integer> futuro : futuros) {
                estados.add(futuro.get(TOPE.toSeconds(), TimeUnit.SECONDS));
            }
            return estados;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("ocho pedidos a la vez en una ferretería sin fila de config_arca: los ocho 201, un solo pendiente y una sola fila")
    void ochoPedidosALaVezSinFilaDeConfiguracion() throws Exception {
        Ferreteria f = ferreteria();
        long deadlocks = barrera.deadlocksAntes();

        List<Integer> estados = paralelo(Collections.nCopies(8,
                () -> estado(postJson(URL + "/pedido-certificado", f.dueno(), "{\"cuit\": null}"))));

        assertThat(estados).containsExactly(201, 201, 201, 201, 201, 201, 201, 201);
        assertThat(credenciales(f)).extracting(fila -> fila.get("estado")).containsExactly("PENDIENTE");
        assertThat(jdbc.queryForObject("select count(*) from config_arca where ferreteria_id = ?", Integer.class, f.id()))
                .isOne();
        barrera.assertSinDeadlocks(deadlocks);
    }

    @Test
    @DisplayName("el mismo .crt subido dos veces a la vez: un 200 y un 409, una sola activa y ningún pendiente")
    void elMismoCrtDosVecesALaVez() throws Exception {
        Ferreteria f = ferreteria();
        String crt = pem(emitido(CasDeArcaDePrueba.HOMOLOGACION, pedido(f), f));
        long deadlocks = barrera.deadlocksAntes();

        List<Integer> estados = paralelo(Collections.nCopies(2, () -> estado(subir(f, crt))));

        assertThat(estados).containsExactlyInAnyOrder(200, 409);
        assertThat(cuantas(f, "ACTIVA")).isOne();
        assertThat(cuantas(f, "PENDIENTE")).isZero();
        barrera.assertSinDeadlocks(deadlocks);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"la primera en la cola es una subida", "el primero en la cola es un pedido"})
    @DisplayName("tres subidas del mismo .crt entrelazadas con tres pedidos nuevos: solo 200, 201, 400 o 409, el .crt activa "
            + "solo si su subida es la primera de la cola, y queda una activa y un pendiente")
    void subidasYPedidosEntrelazados(String quienEmpieza) throws Exception {
        Ferreteria f = ferreteria();
        X509Certificate anterior = activa(f, CasDeArcaDePrueba.HOMOLOGACION);
        X509Certificate renovado = emitido(CasDeArcaDePrueba.HOMOLOGACION, pedido(f), f);
        String crt = pem(renovado);
        Callable<Integer> subida = () -> estado(subir(f, crt));
        Callable<Integer> pedido = () -> estado(postJson(URL + "/pedido-certificado", f.dueno(), "{\"cuit\": null}"));
        boolean subidaPrimero = quienEmpieza.contains("subida");
        List<Callable<Integer>> cola = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            cola.add(subidaPrimero ? subida : pedido);
            cola.add(subidaPrimero ? pedido : subida);
        }
        long deadlocks = barrera.deadlocksAntes();

        // Un holder sostiene el lock de la fila y las seis entran a la cola de a una, en ese orden: el orden en que ganan es
        // el de la cola y no el de quién arrancó antes, así que la subida gana en un caso y pierde en el otro.
        List<Integer> estados = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(cola.size());
        HolderJdbc holder = BarreraDeLocks.holderJdbc(
                "select id from config_arca where ferreteria_id = " + f.id() + " for update");
        try {
            List<Future<Integer>> futuros = new ArrayList<>();
            for (Callable<Integer> tarea : cola) {
                futuros.add(pool.submit(tarea));
                barrera.esperarBloqueados(futuros.size());
            }
            holder.rollback();
            for (Future<Integer> futuro : futuros) {
                estados.add(futuro.get(TOPE.toSeconds(), TimeUnit.SECONDS));
            }
        } finally {
            holder.rollback();
            pool.shutdownNow();
        }

        // El .crt es el del pedido de antes: sirve una sola vez, y solo si ningún pedido nuevo lo reemplazó. Con la cola fija,
        // gana la primera subida si es la primera de la cola y ninguna si un pedido llegó antes.
        assertThat(estados).isSubsetOf(200, 201, 400, 409);
        long activadas = estados.stream().filter(estado -> estado == 200).count();
        assertThat(activadas).isEqualTo(subidaPrimero ? 1 : 0);
        assertThat(estados.stream().filter(estado -> estado == 201).count()).isEqualTo(3);
        // La activa anterior solo se reemplaza, y cada pedido deja uno pendiente: nunca dos de cada una, ni ninguna.
        assertThat(cuantas(f, "ACTIVA")).isOne();
        assertThat(cuantas(f, "PENDIENTE")).isOne();
        assertThat(credenciales(f).get(0)).containsEntry("huella",
                huella((activadas == 1 ? renovado : anterior).getPublicKey()));
        barrera.assertSinDeadlocks(deadlocks);
    }

    @Test
    @DisplayName("una venta sostiene el FOR SHARE de la configuración mientras el Dueño renueva el .crt y cambia el punto de "
            + "venta: los dos esperan, dan 200 cuando la venta termina, sin deadlock, y queda la renovada con el punto 7")
    void ventaQueSostieneElLockMientrasElDuenoRenueva() throws Exception {
        Ferreteria f = ferreteria();
        activa(f, CasDeArcaDePrueba.HOMOLOGACION);
        facturacionPrendida(f);
        X509Certificate renovado = emitido(CasDeArcaDePrueba.HOMOLOGACION, pedido(f), f);
        Hito ventaDecidio = new Hito();
        Hito ventaSigue = new Hito();
        long deadlocks = barrera.deadlocksAntes();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            Future<Boolean> venta = pool.submit(() -> {
                TenantContext.setCurrentFerreteriaId(f.id());
                try {
                    return new TransactionTemplate(transacciones).execute(t -> {
                        boolean factura = facturacion.decidir(ReceptorVenta.sinCliente(), reloj.instant()).factura();
                        ventaDecidio.avisar();
                        ventaSigue.esperar();
                        return factura;
                    });
                } finally {
                    TenantContext.clear();
                }
            });
            ventaDecidio.esperar();
            Future<Integer> renovar = pool.submit(() -> estado(subir(f, pem(renovado))));
            Future<Integer> puntoVenta = pool.submit(
                    () -> estado(putJson(URL + "/punto-venta", f.dueno(), "{\"puntoVenta\": 7}")));

            barrera.esperarBloqueados(2);
            assertThat(barrera.objetosEnEspera()).as("lo que esperan el .crt y el punto de venta").containsExactly("config_arca");
            assertThat(renovar).isNotDone();
            assertThat(puntoVenta).isNotDone();
            ventaSigue.avisar();

            assertThat(venta.get(TOPE.toSeconds(), TimeUnit.SECONDS)).isTrue();
            assertThat(renovar.get(TOPE.toSeconds(), TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(puntoVenta.get(TOPE.toSeconds(), TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            ventaSigue.avisar();
            pool.shutdownNow();
        }

        assertThat(credenciales(f)).singleElement().satisfies(fila -> assertThat(fila).containsEntry("estado", "ACTIVA")
                .containsEntry("huella", huella(renovado.getPublicKey())));
        assertThat(config(f)).containsEntry("punto_venta", 7);
        barrera.assertSinDeadlocks(deadlocks);
    }
}
