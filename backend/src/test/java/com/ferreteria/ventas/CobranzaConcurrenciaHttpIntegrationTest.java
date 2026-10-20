package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.clientes.CuentaCorrienteTestBase;
import com.ferreteria.clientes.service.CuentaCorrienteService;
import com.ferreteria.support.BarreraDeLocks;
import com.ferreteria.support.BarreraDeLocks.Hito;
import com.ferreteria.support.BarreraDeLocks.Largada;
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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.test.util.AopTestUtils;

/**
 * CCT3-405 (CC-049, CC-044, CC-040): la cobranza BAJO CARGA por HTTP real (MockMvc con hilos de verdad contra MySQL real): todo
 * pedido termina con una respuesta de negocio (2xx / 409 / 422) y NINGUNA es 5xx. Cubre el contrato HTTP de CB-1/CB-2/CB-9: «un
 * conflicto transitorio se reintenta en la fachada y no se filtra como 500». Sin tiempos: largada común, y donde hace falta
 * ordenar a los hilos, {@link BarreraDeLocks} (la condición observable «el hilo está en LOCK WAIT»), nunca
 * {@code sleep}/{@code parkNanos}. Cada escenario exige delta {@code lock_deadlocks} = 0 e I1 + I2 al final. CUIT 30-8407NNNN-1.
 */
class CobranzaConcurrenciaHttpIntegrationTest extends CuentaCorrienteTestBase {

    /** Pausa al hilo registrado dentro de {@code asentarCobro}: ya tiene caja, cliente, numerador y recibo, y no confirmó. */
    @SpyBean
    CuentaCorrienteService servicioEspiado;

    private final AtomicReference<Thread> hiloAPausar = new AtomicReference<>();

    @Override
    protected String prefijoDeCuit() {
        return "30-8407";
    }

    @AfterEach
    void soltarElEspia() {
        Mockito.reset((CuentaCorrienteService) AopTestUtils.getUltimateTargetObject(servicioEspiado));
        hiloAPausar.set(null);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Armado
    // ---------------------------------------------------------------------------------------------------------------

    /** Ferretería con caja abierta hoy y el token de su Dueño. */
    private record Escenario(Ferreteria f, long caja, String token) {}

    private Escenario escenario() throws Exception {
        Ferreteria f = ferreteria();
        Logins login = logins(f);
        long caja = cajaAbiertaHoy(f.id());
        return new Escenario(f, caja, login(login.dueno()));
    }

    /** Lo que respondió un hilo: el estado HTTP y el cuerpo crudo. */
    private record Resp(int status, String cuerpo, String retryAfter) {
        JsonNode json() throws Exception {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(cuerpo);
        }

        boolean es5xx() {
            return status >= 500;
        }
    }

    private static String clave() {
        return UUID.randomUUID().toString();
    }

    private static String imp(long comprobanteId, String importe) {
        return "{\"comprobanteId\":" + comprobanteId + ",\"importe\":" + importe + "}";
    }

    private static String cuerpo(String clave, long cliente, String total, String... imputaciones) {
        return "{\"clave\":\"" + clave + "\",\"clienteId\":" + cliente + ",\"medio\":\"EFECTIVO\",\"total\":" + total
                + ",\"imputaciones\":[" + String.join(",", imputaciones) + "]}";
    }

    private Resp enviar(String url, String token, String cuerpo) throws Exception {
        var respuesta = mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(url)
                        .header("Authorization", "Bearer " + token)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(cuerpo)).andReturn().getResponse();
        return new Resp(respuesta.getStatus(), respuesta.getContentAsString(StandardCharsets.UTF_8),
                respuesta.getHeader("Retry-After"));
    }

    private Callable<Resp> cobrar(Escenario e, String cuerpo) {
        return () -> enviar("/api/cobranzas", e.token(), cuerpo);
    }

    private long articulo(Ferreteria f) {
        String codigo = "H-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, "
                + "alicuota_iva, unidad, stock_actual, stock_minimo, activo) values (?, ?, 'Tornillo', 1.00, 1.00, 'IVA_21', "
                + "'UNIDAD', 10000000, 0, 1)", f.id(), codigo);
        return jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?", Long.class,
                f.id(), codigo);
    }

    /** Una venta íntegra a cuenta corriente de {@code unidades} × $ 1,00, por HTTP. */
    private Callable<Resp> ventaCC(Escenario e, long articulo, long cliente, int unidades) {
        return ventaCC(e, articulo, cliente, unidades, clave());
    }

    /** Igual, con la clave dada (para reintentar la MISMA venta). */
    private Callable<Resp> ventaCC(Escenario e, long articulo, long cliente, int unidades, String clave) {
        long forma = jdbc.queryForObject("select id from forma_pago where ferreteria_id = ? and medio = 'CUENTA_CORRIENTE'",
                Long.class, e.f().id());
        String venta = "{\"clave\":\"" + clave + "\",\"renglones\":[{\"articuloId\":" + articulo + ",\"cantidad\":" + unidades
                + ",\"precioUnitario\":1.00}],\"totalMostrado\":" + unidades + ".00,\"formaPagoId\":" + forma
                + ",\"clienteId\":" + cliente + "}";
        return () -> enviar("/api/ventas", e.token(), venta);
    }

    private Long ultimoNumero(long ferreteriaId) {
        List<Long> filas = jdbc.queryForList("select ultimo_numero from numerador_recibo where ferreteria_id = ?", Long.class,
                ferreteriaId);
        return filas.isEmpty() ? null : filas.get(0);
    }

    private long recibosDe(long ferreteriaId) {
        return jdbc.queryForObject("select count(*) from recibo where ferreteria_id = ?", Long.class, ferreteriaId);
    }

    private List<Long> numerosDeRecibo(long ferreteriaId) {
        return jdbc.queryForList("select numero from recibo where ferreteria_id = ? order by numero", Long.class, ferreteriaId);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Hilos
    // ---------------------------------------------------------------------------------------------------------------

    private static Resp resultado(Future<Resp> futuro) throws Exception {
        return futuro.get(60, TimeUnit.SECONDS);
    }

    /** N pedidos con largada común (todos listos antes de que arranque el primero); devuelve cada respuesta en orden. */
    private List<Resp> enParalelo(List<? extends Callable<Resp>> trabajos) throws Exception {
        Largada largada = new Largada(trabajos.size());
        ExecutorService pool = Executors.newFixedThreadPool(trabajos.size());
        try {
            List<Future<Resp>> futuros = new ArrayList<>();
            for (Callable<Resp> trabajo : trabajos) {
                futuros.add(pool.submit(() -> {
                    largada.listoYEsperar();
                    return trabajo.call();
                }));
            }
            largada.esperarListos(Duration.ofSeconds(30));
            largada.largar();
            List<Resp> respuestas = new ArrayList<>();
            for (Future<Resp> futuro : futuros) {
                respuestas.add(resultado(futuro));
            }
            return respuestas;
        } finally {
            pool.shutdownNow();
        }
    }

    private static long cuantas(List<Resp> respuestas, int status) {
        return respuestas.stream().filter(r -> r.status() == status).count();
    }

    /** Ninguna respuesta es 5xx y cada una es una respuesta de negocio de las permitidas. */
    private static void sinErroresDeServidor(List<Resp> respuestas, Integer... permitidos) {
        List<Integer> estados = respuestas.stream().map(Resp::status).toList();
        assertThat(respuestas).as("ninguna 5xx (estados: %s)", estados).noneMatch(Resp::es5xx);
        assertThat(estados).as("estados de negocio").isSubsetOf(permitidos);
    }

    // ===============================================================================================================
    // Carga
    // ===============================================================================================================

    @Test
    @DisplayName("8 POST de $ 100 a la vez sobre un cliente con saldo 1.000 y 2 comprobantes (A 300, B 700, 4 a cada uno) → 7 × 201 y 1 × 409, ninguna 5xx; saldo 300; recibos 1..7")
    void ochoDeCien() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "300");
        VentaSuelta b = ventaEnLibro(e.f(), cliente, "700");
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        List<Callable<Resp>> trabajos = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            long comprobante = i % 2 == 0 ? a.comprobanteId() : b.comprobanteId();
            trabajos.add(cobrar(e, cuerpo(clave(), cliente, "100", imp(comprobante, "100"))));
        }

        List<Resp> respuestas = enParalelo(trabajos);

        sinErroresDeServidor(respuestas, 201, 409);
        assertThat(cuantas(respuestas, 201)).isEqualTo(7);
        assertThat(cuantas(respuestas, 409)).isEqualTo(1);
        assertThat(imputadoA(a.comprobanteId())).isEqualByComparingTo("300");
        assertThat(imputadoA(b.comprobanteId())).isEqualByComparingTo("400");
        assertThat(saldo(cliente)).isEqualByComparingTo("300");
        assertThat(numerosDeRecibo(e.f().id())).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L);
        assertThat(cobros(cliente)).isEqualTo(7);
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    @Test
    @DisplayName("dos POST de $ 800 a la vez sobre un pendiente de 1.000 → un 201 y un 409; Σ imputaciones 800, saldo 200 y el 409 trae la deuda actual (200)")
    void dosDeOchocientos() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "1000");
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();

        List<Resp> respuestas = enParalelo(List.of(
                cobrar(e, cuerpo(clave(), cliente, "800", imp(a.comprobanteId(), "800"))),
                cobrar(e, cuerpo(clave(), cliente, "800", imp(a.comprobanteId(), "800")))));

        sinErroresDeServidor(respuestas, 201, 409);
        assertThat(cuantas(respuestas, 201)).isEqualTo(1);
        Resp conflicto = respuestas.stream().filter(r -> r.status() == 409).findFirst().orElseThrow();
        assertThat(conflicto.json().get("motivo").asText()).isEqualTo("IMPUTACION_EXCEDE_DEUDA");
        assertThat(conflicto.json().get("comprobantes").get(0).get("pendiente").decimalValue()).isEqualByComparingTo("200");
        assertThat(imputadoA(a.comprobanteId())).isEqualByComparingTo("800");
        assertThat(saldo(cliente)).isEqualByComparingTo("200");
        assertThat(recibosDe(e.f().id())).isEqualTo(1);
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    @Test
    @DisplayName("8 POST que imputan a {A,B} en orden A,B y B,A a la vez → todos 201, ninguna 5xx ni deadlock")
    void ordenInverso() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "1000");
        VentaSuelta b = ventaEnLibro(e.f(), cliente, "1000");
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        List<Callable<Resp>> trabajos = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            trabajos.add(i % 2 == 0
                    ? cobrar(e, cuerpo(clave(), cliente, "200", imp(a.comprobanteId(), "100"), imp(b.comprobanteId(), "100")))
                    : cobrar(e, cuerpo(clave(), cliente, "200", imp(b.comprobanteId(), "100"), imp(a.comprobanteId(), "100"))));
        }

        List<Resp> respuestas = enParalelo(trabajos);

        sinErroresDeServidor(respuestas, 201);
        assertThat(imputadoA(a.comprobanteId())).isEqualByComparingTo("800");
        assertThat(imputadoA(b.comprobanteId())).isEqualByComparingTo("800");
        assertThat(saldo(cliente)).isEqualByComparingTo("400");
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    @Test
    @DisplayName("la misma clave 6 veces a la vez → un 201 y cinco 200 con el MISMO recibo (JSON idéntico); un recibo y un COBRO")
    void mismaClaveALaVez() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "500");
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        String pedido = cuerpo(clave(), cliente, "300", imp(a.comprobanteId(), "100"));
        List<Callable<Resp>> trabajos = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            trabajos.add(cobrar(e, pedido));
        }

        List<Resp> respuestas = enParalelo(trabajos);

        sinErroresDeServidor(respuestas, 200, 201);
        assertThat(cuantas(respuestas, 201)).isEqualTo(1);
        assertThat(cuantas(respuestas, 200)).isEqualTo(5);
        assertThat(respuestas.stream().map(Resp::cuerpo).distinct()).as("el mismo JSON en las seis").hasSize(1);
        assertThat(recibosDe(e.f().id())).isEqualTo(1);
        assertThat(cobros(cliente)).isEqualTo(1);
        assertThat(saldo(cliente)).isEqualByComparingTo("200");
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    @Test
    @DisplayName("dos adelantos de $ 300 a la vez a un cliente sin deuda → 201 y 201, saldo −600, recibos 1 y 2")
    void dosAdelantos() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), null);
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();

        List<Resp> respuestas = enParalelo(List.of(cobrar(e, cuerpo(clave(), cliente, "300")),
                cobrar(e, cuerpo(clave(), cliente, "300"))));

        sinErroresDeServidor(respuestas, 201);
        assertThat(saldo(cliente)).isEqualByComparingTo("-600");
        assertThat(numerosDeRecibo(e.f().id())).containsExactly(1L, 2L);
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    @Test
    @DisplayName("cobranza × venta a cuenta por HTTP, varias a la vez sobre el mismo cliente → todas 2xx, ninguna 5xx; saldo = 1.000 + ventas − cobros")
    void cobranzaPorVenta() throws Exception {
        Escenario e = escenario();
        long articulo = articulo(e.f());
        long cliente = cliente(e.f(), "10000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "1000");
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        List<Callable<Resp>> trabajos = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            trabajos.add(cobrar(e, cuerpo(clave(), cliente, "100", imp(a.comprobanteId(), "100"))));
            trabajos.add(ventaCC(e, articulo, cliente, 30));
        }

        List<Resp> respuestas = enParalelo(trabajos);

        sinErroresDeServidor(respuestas, 200, 201);
        assertThat(saldo(cliente)).isEqualByComparingTo("720"); // 1.000 − 4 × 100 de cobros + 4 × 30 de ventas
        assertThat(imputadoA(a.comprobanteId())).isEqualByComparingTo("400");
        assertThat(cobros(cliente)).isEqualTo(4);
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    // ===============================================================================================================
    // W3: la carrera de la misma clave con OTRO cliente (1062 real contra MySQL)
    // ===============================================================================================================

    @Test
    @DisplayName("W3: la misma clave con DOS clientes distintos a la vez (no comparten lock de cliente; chocan en uk_recibo_clave de MySQL) → un 201 y un 422 CLAVE_CON_OTRO_CONTENIDO; un recibo y el número del perdedor no se consume")
    void claveIgualOtroCliente() throws Exception {
        Escenario e = escenario();
        long cliente1 = cliente(e.f(), "5000");
        long cliente2 = cliente(e.f(), "5000");
        String clave = clave();
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
        }).when(espia).asentarCobro(any());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // El primero queda con caja (S), cliente 1, numerador (N.º 1) y recibo con la clave, sin confirmar.
            Future<Resp> primero = pool.submit(() -> {
                hiloAPausar.set(Thread.currentThread());
                return enviar("/api/cobranzas", e.token(), cuerpo(clave, cliente1, "40"));
            });
            llego.esperar();
            // El segundo no ve el recibo de la clave (no confirmado), toma su cliente y queda esperando el numerador.
            Future<Resp> segundo = pool.submit(() -> enviar("/api/cobranzas", e.token(), cuerpo(clave, cliente2, "40")));
            barrera.esperarBloqueados(1);
            suelta.avisar();

            Resp ganador = resultado(primero);
            Resp perdedor = resultado(segundo);

            assertThat(ganador.status()).isEqualTo(201);
            assertThat(perdedor.status()).as("el perdedor es un 422 de negocio, no un error genérico: %s", perdedor.cuerpo()).isEqualTo(422);
            assertThat(perdedor.json().get("motivo").asText()).isEqualTo("CLAVE_CON_OTRO_CONTENIDO");
        } finally {
            suelta.avisar();
            pool.shutdownNow();
        }

        assertThat(recibosDe(e.f().id())).isEqualTo(1);
        assertThat(ultimoNumero(e.f().id())).as("el número del perdedor se revirtió").isEqualTo(1L);
        assertThat(cobros(cliente1)).isEqualTo(1);
        assertThat(cobros(cliente2)).isZero();
        assertThat(saldo(cliente1)).isEqualByComparingTo("-40");
        assertThat(saldo(cliente2)).isEqualByComparingTo("0");
        assertInvariante(cliente1);
        assertInvariante(cliente2);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    // ===============================================================================================================
    // El reintento de la fachada protege del 500
    // ===============================================================================================================

    @Test
    @DisplayName("un deadlock transitorio al asentar (el que elige InnoDB de víctima) se reintenta en la fachada: 201, un recibo N.º 1 (el número del intento fallido se revirtió) y un solo COBRO")
    void elReintentoProtege() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "500");
        CuentaCorrienteService espia = AopTestUtils.getUltimateTargetObject(servicioEspiado);
        doThrow(new DeadlockLoserDataAccessException("deadlock simulado como víctima de InnoDB", null))
                .doCallRealMethod().when(espia).asentarCobro(any());

        Resp respuesta = cobrar(e, cuerpo(clave(), cliente, "100", imp(a.comprobanteId(), "100"))).call();

        assertThat(respuesta.status()).as("cuerpo %s", respuesta.cuerpo()).isEqualTo(201);
        assertThat(respuesta.json().get("numero").asLong()).isEqualTo(1L);
        verify(espia, times(2)).asentarCobro(any());
        assertThat(recibosDe(e.f().id())).isEqualTo(1);
        assertThat(cobros(cliente)).isEqualTo(1);
        assertThat(imputadoA(a.comprobanteId())).isEqualByComparingTo("100");
        assertThat(saldo(cliente)).isEqualByComparingTo("400");
        assertInvariante(cliente);
    }

    // ===============================================================================================================
    // S9: agotados los reintentos, 503 «reintentá» (transitorio) y no 500
    // ===============================================================================================================

    @Test
    @DisplayName("S9 cobranza: si los 3 intentos terminan en deadlock o timeout, la respuesta es 503 «El sistema está ocupado, reintentá.» con Retry-After y sin rastros; el reintento con la MISMA clave entra (201, recibo N.º 1)")
    void cobranzaConLocksAgotadosEs503() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "500");
        CuentaCorrienteService espia = AopTestUtils.getUltimateTargetObject(servicioEspiado);
        doThrow(new DeadlockLoserDataAccessException("deadlock simulado, siempre", null)).when(espia).asentarCobro(any());
        String pedido = cuerpo(clave(), cliente, "100", imp(a.comprobanteId(), "100"));

        Resp agotada = cobrar(e, pedido).call();

        assertThat(agotada.status()).as("cuerpo %s", agotada.cuerpo()).isEqualTo(503);
        assertThat(agotada.json().get("message").asText()).isEqualTo("El sistema está ocupado, reintentá.");
        assertThat(agotada.json().get("status").asInt()).isEqualTo(503);
        assertThat(agotada.retryAfter()).isEqualTo("2");
        verify(espia, times(3)).asentarCobro(any());
        assertThat(recibosDe(e.f().id())).as("recibos").isZero();
        assertThat(ultimoNumero(e.f().id())).as("número consumido").isNull();
        assertThat(cobros(cliente)).isZero();
        assertThat(saldo(cliente)).isEqualByComparingTo("500");

        Mockito.reset(espia); // pasó la tormenta: el cliente reintenta lo mismo con la misma clave
        Resp reintento = cobrar(e, pedido).call();

        assertThat(reintento.status()).as("cuerpo %s", reintento.cuerpo()).isEqualTo(201);
        assertThat(reintento.json().get("numero").asLong()).isEqualTo(1L);
        assertThat(recibosDe(e.f().id())).isEqualTo(1);
        assertThat(saldo(cliente)).isEqualByComparingTo("400");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("S9 cobranza: un error que NO es de lock (IllegalStateException al asentar) sigue siendo 500 «Error interno», sin Retry-After, y no se reintenta")
    void otroErrorDeLaCobranzaSigueSiendo500() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "500");
        CuentaCorrienteService espia = AopTestUtils.getUltimateTargetObject(servicioEspiado);
        doThrow(new IllegalStateException("falla simulada que no es de lock")).when(espia).asentarCobro(any());

        Resp respuesta = cobrar(e, cuerpo(clave(), cliente, "100", imp(a.comprobanteId(), "100"))).call();

        assertThat(respuesta.status()).as("cuerpo %s", respuesta.cuerpo()).isEqualTo(500);
        assertThat(respuesta.json().get("message").asText()).isEqualTo("Error interno");
        assertThat(respuesta.retryAfter()).isNull();
        verify(espia, times(1)).asentarCobro(any());
        assertThat(recibosDe(e.f().id())).isZero();
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("S9 venta: con la misma condición (3 intentos con timeout de lock al asentar la cuenta corriente) POST /api/ventas responde 503 «reintentá» con Retry-After, sin comprobante; el reintento con la MISMA clave entra (201)")
    void ventaConLocksAgotadosEs503() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        long articulo = articulo(e.f());
        CuentaCorrienteService espia = AopTestUtils.getUltimateTargetObject(servicioEspiado);
        doThrow(new org.springframework.dao.CannotAcquireLockException("Lock wait timeout exceeded, simulado")).when(espia)
                .asentarVenta(any());
        String clave = clave();

        Resp agotada = ventaCC(e, articulo, cliente, 10, clave).call();

        assertThat(agotada.status()).as("cuerpo %s", agotada.cuerpo()).isEqualTo(503);
        assertThat(agotada.json().get("message").asText()).isEqualTo("El sistema está ocupado, reintentá.");
        assertThat(agotada.retryAfter()).isEqualTo("2");
        verify(espia, times(3)).asentarVenta(any());
        assertThat(jdbc.queryForObject("select count(*) from comprobante where ferreteria_id = ?", Long.class, e.f().id()))
                .as("comprobantes").isZero();
        assertThat(saldo(cliente)).isEqualByComparingTo("0");

        Mockito.reset(espia);
        Resp reintento = ventaCC(e, articulo, cliente, 10, clave).call();

        assertThat(reintento.status()).as("cuerpo %s", reintento.cuerpo()).isEqualTo(201);
        assertThat(saldo(cliente)).isEqualByComparingTo("10");
        assertInvariante(cliente);
    }
}
