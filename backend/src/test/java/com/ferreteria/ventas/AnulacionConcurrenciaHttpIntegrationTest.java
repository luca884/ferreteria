package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.clientes.CuentaCorrienteTestBase;
import com.ferreteria.clientes.service.CuentaCorrienteService;
import com.ferreteria.support.BarreraDeLocks;
import com.ferreteria.support.BarreraDeLocks.Hito;
import com.ferreteria.support.BarreraDeLocks.Largada;
import com.ferreteria.ventas.service.RegistroAnulacion;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.MediaType;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * CCT3B1-607 (CC-092, CC-086, CC-060, CC-057): la concurrencia por HTTP real. Con anulaciones y cobranzas disparadas a la vez
 * ninguna respuesta es un 5xx: el segundo en anular el mismo recibo recibe 409 RECIBO_YA_ANULADO (no 500), las anulaciones de
 * recibos distintos del mismo cliente conviven con cobranzas sin deadlock ({@code lock_deadlocks} = 0), el extracto no se bloquea
 * contra una anulación retenida y la contención agotada es un 503 «reintentá» con {@code Retry-After} (nada guardado), no un 500.
 * Sin {@code Thread.sleep}: largada común y {@link BarreraDeLocks}. CUIT 30-8506NNNN-1.
 */
class AnulacionConcurrenciaHttpIntegrationTest extends CuentaCorrienteTestBase {

    private static final String MOTIVO = "Cobro mal cargado";

    /** Pausa al hilo registrado dentro de asentarAnulacion: ya tiene la caja y el cliente tomados y no confirmó. */
    @SpyBean
    CuentaCorrienteService servicioEspiado;

    /** Para simular un deadlock/timeout de lock agotado: la fachada reintenta sobre esta clase. */
    @SpyBean
    RegistroAnulacion registroEspiado;

    private final AtomicReference<Thread> hiloAPausar = new AtomicReference<>();

    @Override
    protected String prefijoDeCuit() {
        return "30-8506";
    }

    @AfterEach
    void soltarLosEspias() {
        Mockito.reset((CuentaCorrienteService) AopTestUtils.getUltimateTargetObject(servicioEspiado));
        Mockito.reset((RegistroAnulacion) AopTestUtils.getUltimateTargetObject(registroEspiado));
        hiloAPausar.set(null);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Armado
    // ---------------------------------------------------------------------------------------------------------------

    private record Tienda(Ferreteria f, String dueno, long caja) {}

    private Tienda tienda() throws Exception {
        Ferreteria f = ferreteria();
        Logins login = logins(f);
        return new Tienda(f, login(login.dueno()), cajaAbiertaHoy(f.id()));
    }

    /** Lo que respondió el servidor: estado, cuerpo y la cabecera Retry-After. */
    private record Respuesta(int estado, String cuerpo, String reintentarEn) {

        JsonNode json() throws Exception {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(cuerpo);
        }
    }

    private Respuesta responder(MvcResult r) {
        return new Respuesta(r.getResponse().getStatus(), new String(r.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8),
                r.getResponse().getHeader("Retry-After"));
    }

    private Respuesta post(Tienda t, String url, String cuerpo) throws Exception {
        return responder(mvc.perform(MockMvcRequestBuilders.post(url).header("Authorization", "Bearer " + t.dueno())
                .contentType(MediaType.APPLICATION_JSON).content(cuerpo)).andReturn());
    }

    private Respuesta get(Tienda t, String url) throws Exception {
        return responder(mvc.perform(MockMvcRequestBuilders.get(url).header("Authorization", "Bearer " + t.dueno())).andReturn());
    }

    private Respuesta anular(Tienda t, long recibo) throws Exception {
        return post(t, "/api/recibos/" + recibo + "/anulacion", "{\"motivo\":\"" + MOTIVO + "\"}");
    }

    private Respuesta cobrar(Tienda t, long cliente, String total, Long comprobante) throws Exception {
        String imputaciones = comprobante == null ? "[]"
                : "[{\"comprobanteId\":" + comprobante + ",\"importe\":" + total + "}]";
        return post(t, "/api/cobranzas", "{\"clave\":\"" + UUID.randomUUID() + "\",\"clienteId\":" + cliente
                + ",\"medio\":\"EFECTIVO\",\"total\":" + total + ",\"imputaciones\":" + imputaciones + "}");
    }

    /** Un cobro ya hecho (201); devuelve el id del recibo. */
    private long cobroHecho(Tienda t, long cliente, String total, Long comprobante) throws Exception {
        Respuesta r = cobrar(t, cliente, total, comprobante);
        assertThat(r.estado()).as(r.cuerpo()).isEqualTo(201);
        return r.json().get("id").asLong();
    }

    private long anulaciones(long cliente) {
        return jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente where cliente_id = ? and tipo = 'ANULACION'",
                Long.class, cliente);
    }

    private String estado(long recibo) {
        return jdbc.queryForObject("select estado from recibo where id = ?", String.class, recibo);
    }

    private Long ultimoNumero(long ferreteriaId) {
        List<Long> filas = jdbc.queryForList("select ultimo_numero from numerador_recibo where ferreteria_id = ?", Long.class,
                ferreteriaId);
        return filas.isEmpty() ? null : filas.get(0);
    }

    /** N pedidos HTTP con largada común (todos listos antes de que arranque el primero), en el orden en que se pasaron. */
    private List<Respuesta> enParalelo(List<Callable<Respuesta>> trabajos) throws Exception {
        Largada largada = new Largada(trabajos.size());
        ExecutorService pool = Executors.newFixedThreadPool(trabajos.size());
        try {
            List<Future<Respuesta>> futuros = new ArrayList<>();
            for (Callable<Respuesta> trabajo : trabajos) {
                futuros.add(pool.submit(() -> {
                    largada.listoYEsperar();
                    return trabajo.call();
                }));
            }
            largada.esperarListos(Duration.ofSeconds(30));
            largada.largar();
            List<Respuesta> respuestas = new ArrayList<>();
            for (Future<Respuesta> futuro : futuros) {
                respuestas.add(futuro.get(60, TimeUnit.SECONDS));
            }
            return respuestas;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 8 anulaciones del mismo recibo: 1 × 200 y 7 × 409
    // ---------------------------------------------------------------------------------------------------------------

    private void ochoAnulacionesDelMismoRecibo(boolean conImputaciones) throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), null);
        Long comprobante = conImputaciones ? ventaEnLibro(t.f(), cliente, "1000").comprobanteId() : null;
        long recibo = cobroHecho(t, cliente, "600", comprobante);
        java.math.BigDecimal saldoAntes = saldo(cliente);
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        List<Callable<Respuesta>> trabajos = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            trabajos.add(() -> anular(t, recibo));
        }

        List<Respuesta> respuestas = enParalelo(trabajos);

        assertThat(respuestas).extracting(Respuesta::estado).as("ningún 5xx: %s", respuestas)
                .containsExactlyInAnyOrder(200, 409, 409, 409, 409, 409, 409, 409);
        for (Respuesta r : respuestas) {
            if (r.estado() == 409) {
                JsonNode cuerpo = r.json();
                assertThat(cuerpo.get("motivo").asText()).isEqualTo("RECIBO_YA_ANULADO");
                assertThat(cuerpo.get("anuladoPor").asText()).isEqualTo("Dueno CC");
                assertThat(cuerpo.get("message").asText())
                        .matches("Este recibo ya fue anulado por Dueno CC el \\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}\\.");
            } else {
                assertThat(r.json().get("estado").asText()).isEqualTo("ANULADO");
            }
        }
        assertThat(estado(recibo)).isEqualTo("ANULADO");
        assertThat(anulaciones(cliente)).as("una sola ANULACION").isEqualTo(1);
        assertThat(saldo(cliente)).as("el cobro vuelve a la deuda una sola vez").isEqualByComparingTo(saldoAntes.add(new java.math.BigDecimal("600")));
        assertThat(ultimoNumero(t.f().id())).as("anular no consume número").isEqualTo(1L);
        barrera.assertSinDeadlocks(deadlocksAntes);
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("CC-086: 8 POST …/anulacion del mismo recibo CON imputaciones a la vez → exactamente 1 × 200 y 7 × 409 RECIBO_YA_ANULADO (ningún 500), una ANULACION, sin deadlock")
    void ochoAnulacionesConImputaciones() throws Exception {
        ochoAnulacionesDelMismoRecibo(true);
    }

    @Test
    @DisplayName("CC-086: 8 POST …/anulacion del mismo recibo de un ADELANTO a la vez → 1 × 200 y 7 × 409 (sin imputaciones que detecten la carrera: la ataja el UNIQUE y el lock del cliente), sin deadlock")
    void ochoAnulacionesDeUnAdelanto() throws Exception {
        ochoAnulacionesDelMismoRecibo(false);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 6 anulaciones de recibos distintos del mismo cliente + 4 cobranzas
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("CC-092: 6 anulaciones de recibos distintos del mismo cliente + 4 POST /api/cobranzas a la vez → ninguna 5xx, 0 deadlocks, saldo final = modelo y las invariantes")
    void seisAnulacionesYCuatroCobranzas() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), null);
        long comprobante = ventaEnLibro(t.f(), cliente, "1000").comprobanteId();
        List<Long> recibos = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            recibos.add(cobroHecho(t, cliente, "100", comprobante)); // imputados
        }
        for (int i = 0; i < 3; i++) {
            recibos.add(cobroHecho(t, cliente, "100", null)); // adelantos
        }
        assertThat(saldo(cliente)).isEqualByComparingTo("400");
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        List<Callable<Respuesta>> trabajos = new ArrayList<>();
        for (long recibo : recibos) {
            trabajos.add(() -> anular(t, recibo));
        }
        for (int i = 0; i < 4; i++) {
            trabajos.add(() -> cobrar(t, cliente, "50", null));
        }

        List<Respuesta> respuestas = enParalelo(trabajos);

        assertThat(respuestas).as("ninguna respuesta 5xx: %s", respuestas).allSatisfy(r -> assertThat(r.estado()).isLessThan(500));
        assertThat(respuestas.subList(0, 6)).extracting(Respuesta::estado).as("cada recibo se anula una vez").containsOnly(200);
        assertThat(respuestas.subList(6, 10)).extracting(Respuesta::estado).as("las cobranzas entran").containsOnly(201);
        assertThat(recibos).allSatisfy(r -> assertThat(estado(r)).isEqualTo("ANULADO"));
        assertThat(anulaciones(cliente)).isEqualTo(6);
        assertThat(saldo(cliente)).as("400 + 6 × 100 de las anulaciones − 4 × 50 de las cobranzas").isEqualByComparingTo("800");
        barrera.assertSinDeadlocks(deadlocksAntes);
        assertInvariante(cliente);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Un GET extracto en medio no se bloquea
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("CC-079 (HTTP): con una anulación retenida (caja y cliente tomados, sin confirmar) el GET extracto termina SOLO (0 transacciones en LOCK WAIT) y devuelve el estado de ANTES, coherente; al soltar ya muestra la ANULACION")
    void extractoNoSeBloqueaConUnaAnulacionRetenida() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), null);
        VentaSuelta venta = ventaEnLibro(t.f(), cliente, "1000");
        long recibo = cobroHecho(t, cliente, "400", venta.comprobanteId());
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        Hito llego = new Hito();
        Hito suelta = new Hito();
        CuentaCorrienteService espia = AopTestUtils.getUltimateTargetObject(servicioEspiado);
        doAnswer(invocacion -> {
            if (Thread.currentThread() == hiloAPausar.get()) {
                llego.avisar();
                suelta.esperar();
            }
            return invocacion.callRealMethod();
        }).when(espia).asentarAnulacion(any());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Respuesta> anulacion = pool.submit(() -> {
                hiloAPausar.set(Thread.currentThread());
                return anular(t, recibo);
            });
            llego.esperar();

            Future<Respuesta> lectura = pool.submit(() -> get(t, "/api/clientes/" + cliente + "/cuenta-corriente/extracto"));
            Respuesta antes;
            try {
                antes = lectura.get(20, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                throw new AssertionError("El extracto quedó esperando más de 20 s: tomó algún lock de la anulación", e);
            }

            assertThat(barrera.bloqueadosAhora()).as("nadie en LOCK WAIT").isZero();
            assertThat(antes.estado()).isEqualTo(200);
            JsonNode extractoAntes = antes.json();
            assertThat(extractoAntes.get("movimientos").get("items").get(0).get("tipo").asText()).isEqualTo("COBRO");
            assertThat(extractoAntes.get("movimientos").get("items").get(0).get("anulado").asBoolean()).isFalse();
            assertThat(extractoAntes.get("saldoActual").decimalValue()).isEqualByComparingTo("600");
            assertThat(extractoAntes.get("movimientos").get("items").get(0).get("saldo").decimalValue())
                    .as("saldo de la línea más nueva = saldo actual (foto coherente)").isEqualByComparingTo("600");

            suelta.avisar();
            Respuesta anulada = anulacion.get(60, TimeUnit.SECONDS);
            assertThat(anulada.estado()).isEqualTo(200);
            JsonNode despues = get(t, "/api/clientes/" + cliente + "/cuenta-corriente/extracto").json();
            assertThat(despues.get("movimientos").get("items").get(0).get("tipo").asText()).isEqualTo("ANULACION");
            assertThat(despues.get("movimientos").get("items").get(1).get("anulado").asBoolean()).isTrue();
            assertThat(despues.get("saldoActual").decimalValue()).isEqualByComparingTo("1000");
        } finally {
            suelta.avisar();
            pool.shutdownNow();
        }
        barrera.assertSinDeadlocks(deadlocksAntes);
        assertInvariante(cliente);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Una respuesta «perdida»: el reintento del cliente da 409
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("CC-060: la anulación se hizo pero la respuesta se «perdió»: repetir el mismo POST da 409 RECIBO_YA_ANULADO con quién y cuándo (no una segunda anulación ni un 500)")
    void respuestaPerdida() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), null);
        long recibo = cobroHecho(t, cliente, "300", null);
        Respuesta primera = anular(t, recibo);
        assertThat(primera.estado()).isEqualTo(200);

        Respuesta reintento = anular(t, recibo);

        assertThat(reintento.estado()).isEqualTo(409);
        assertThat(reintento.json().get("motivo").asText()).isEqualTo("RECIBO_YA_ANULADO");
        assertThat(reintento.json().get("fechaAnulacion").asText()).isEqualTo(primera.json().get("fechaAnulacion").asText());
        assertThat(anulaciones(cliente)).isEqualTo(1);
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertInvariante(cliente);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // 503 con Retry-After: contención agotada
    // ---------------------------------------------------------------------------------------------------------------

    /** Hace que los primeros {@code fallos} intentos de registrar ANULEN lo que escribieron (rollback) y fallen como un deadlock. */
    private AtomicInteger fallarComoDeadlock(int fallos) {
        AtomicInteger intentos = new AtomicInteger();
        RegistroAnulacion espia = AopTestUtils.getUltimateTargetObject(registroEspiado);
        doAnswer(invocacion -> {
            Object resultado = invocacion.callRealMethod();
            if (intentos.incrementAndGet() <= fallos) {
                // La excepción sale dentro de la @Transactional de registrar: se revierte todo lo que el intento escribió.
                throw new CannotAcquireLockException("Deadlock found when trying to get lock; try restarting transaction (simulado)");
            }
            return resultado;
        }).when(espia).registrar(any(), any(), any(), any());
        return intentos;
    }

    @Test
    @DisplayName("CC-057/CC-086: la contención agotada tras 3 intentos (deadlock en cada uno) es 503 «El sistema está ocupado, reintentá.» con Retry-After: 2, NO un 500, y no quedó nada guardado (los intentos revirtieron)")
    void contencionAgotadaEs503() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), null);
        long recibo = cobroHecho(t, cliente, "300", null);
        AtomicInteger intentos = fallarComoDeadlock(Integer.MAX_VALUE);

        Respuesta r = anular(t, recibo);

        assertThat(r.estado()).as(r.cuerpo()).isEqualTo(503);
        assertThat(r.reintentarEn()).isEqualTo("2");
        assertThat(r.json().get("message").asText()).isEqualTo("El sistema está ocupado, reintentá.");
        assertThat(intentos.get()).as("3 intentos y recién ahí 503").isEqualTo(3);
        assertThat(estado(recibo)).isEqualTo("EMITIDO");
        assertThat(anulaciones(cliente)).isZero();
        assertThat(saldo(cliente)).isEqualByComparingTo("-300");
        assertThat(jdbc.queryForObject("select count(*) from imputacion where recibo_id = ? and vigente = 0", Long.class, recibo)).isZero();
        assertThat(ultimoNumero(t.f().id())).isEqualTo(1L);
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("CC-086: dos deadlocks seguidos NO llegan al cliente: el tercer intento anula (200) y deja UNA sola ANULACION; el reintento protege (con MAX_INTENTOS = 1 esto es un 5xx)")
    void elReintentoProtege() throws Exception {
        Tienda t = tienda();
        long cliente = cliente(t.f(), null);
        VentaSuelta venta = ventaEnLibro(t.f(), cliente, "500");
        long recibo = cobroHecho(t, cliente, "300", venta.comprobanteId());
        AtomicInteger intentos = fallarComoDeadlock(2);

        Respuesta r = anular(t, recibo);

        assertThat(r.estado()).as(r.cuerpo()).isEqualTo(200);
        assertThat(r.json().get("estado").asText()).isEqualTo("ANULADO");
        verify(registroEspiado, times(3)).registrar(any(), any(), any(), any());
        assertThat(intentos.get()).isEqualTo(3);
        assertThat(anulaciones(cliente)).isEqualTo(1);
        assertThat(saldo(cliente)).isEqualByComparingTo("500");
        assertInvariante(cliente);
    }
}
