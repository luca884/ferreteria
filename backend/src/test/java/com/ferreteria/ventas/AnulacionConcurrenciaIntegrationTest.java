package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.ferreteria.clientes.CuentaCorrienteTestBase;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.Imputar;
import com.ferreteria.clientes.service.ClienteService;
import com.ferreteria.clientes.service.CuentaCorrienteService;
import com.ferreteria.clientes.service.PendientesCuenta;
import com.ferreteria.common.exception.ConflictException;
import com.ferreteria.common.exception.ReglaDeNegocioException;
import com.ferreteria.common.exception.ResourceNotFoundException;
import com.ferreteria.common.exception.SistemaOcupadoException;
import com.ferreteria.support.BarreraDeLocks;
import com.ferreteria.support.BarreraDeLocks.Hito;
import com.ferreteria.support.BarreraDeLocks.Largada;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.service.AnulacionService;
import com.ferreteria.ventas.service.CajaService;
import com.ferreteria.ventas.service.CobranzaPedido;
import com.ferreteria.ventas.service.CobranzaRechazadaException;
import com.ferreteria.ventas.service.CobranzaRechazadaException.Motivo;
import com.ferreteria.ventas.service.CobranzaService;
import com.ferreteria.ventas.service.CobroRegistrado;
import com.ferreteria.ventas.service.ConfirmarVenta;
import com.ferreteria.ventas.service.ConfirmarVenta.Renglon;
import com.ferreteria.ventas.service.DetalleCaja;
import com.ferreteria.ventas.service.VentaRechazadaException;
import com.ferreteria.ventas.service.VentaService;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;

/**
 * CCT3B1-401..407 y 409 (AN-1..AN-7, AN-9, AN-10, CC-086..CC-092): la concurrencia de la anulación COMPLETA
 * ({@link AnulacionService}: cuerpo, pre-resolución recibo → cliente, caja, cliente, recibo, imputaciones, libro, saldo) contra
 * MySQL real, con hilos de verdad y SIN tiempos. Los hilos usan los servicios (el HTTP llega en C6). Cada escenario se
 * sincroniza con {@link BarreraDeLocks}: la condición observable «el hilo B está en LOCK WAIT en MySQL», hitos con timeout y
 * largada común; nunca {@code sleep}/{@code parkNanos}/jitter. La pausa del hilo A «con todo tomado y sin confirmar» es un
 * {@code @SpyBean} de {@code CuentaCorrienteService} (sobre {@code asentarAnulacion}, {@code asentarCobro},
 * {@code asentarVenta} o {@code verificarCupo}); el «holder» es una conexión JDBC cruda con la transacción abierta. Todo
 * escenario exige delta {@code lock_deadlocks} = 0 e I1, I2a..I2e e I-inactivo al final. CUIT 30-8504NNNN-1.
 *
 * <p>La regresión que más importa (AN-1 y el entrelazado CC-091): una segunda anulación del MISMO recibo que esperó el turno del
 * cliente tiene que dar 409 {@code RECIBO_YA_ANULADO} EXACTO y no un 500. Con un recibo CON imputaciones la única defensa de
 * orden es que el primer SELECT plano del recibo corra con el cliente ya tomado (con un adelanto lo taparía el UNIQUE del
 * libro y la respuesta seguiría siendo 409): por eso AN-1 va en las dos variantes y la mutación obligatoria (mover
 * {@code findScoped} antes de {@code resolverParaAnulacion}) rompe la de las imputaciones.
 */
class AnulacionConcurrenciaIntegrationTest extends CuentaCorrienteTestBase {

    private static final String MOTIVO = "Cobro mal cargado";
    private static final String CON_SALDO = "No se puede desactivar: el cliente tiene saldo en cuenta corriente.";

    @Autowired
    CobranzaService cobranzaService;

    @Autowired
    AnulacionService anulacionService;

    @Autowired
    VentaService ventaService;

    @Autowired
    CajaService cajaService;

    @Autowired
    ClienteService clienteService;

    /** Pausa al hilo registrado dentro de un método del puerto de la cuenta corriente: ya tiene todo tomado y no confirmó. */
    @SpyBean
    CuentaCorrienteService servicioEspiado;

    /** Para entrar en la transacción de la anulación justo después de tomar la caja (AN-9, choque del UNIQUE). */
    @SpyBean
    CajaService cajaEspiada;

    private final AtomicReference<Thread> hiloAPausar = new AtomicReference<>();

    @Override
    protected String prefijoDeCuit() {
        return "30-8504";
    }

    @AfterEach
    void soltarLosEspias() {
        Mockito.reset((CuentaCorrienteService) AopTestUtils.getUltimateTargetObject(servicioEspiado));
        Mockito.reset((CajaService) AopTestUtils.getUltimateTargetObject(cajaEspiada));
        hiloAPausar.set(null);
    }

    // ===============================================================================================================
    // Armado
    // ===============================================================================================================

    /** Ferretería con caja abierta hoy y el tenant puesto en el hilo del test. */
    private record Escenario(Ferreteria f, long caja) {}

    private Escenario escenario() {
        Ferreteria f = ferreteria();
        long caja = cajaAbiertaHoy(f.id());
        TenantContext.setCurrentFerreteriaId(f.id());
        return new Escenario(f, caja);
    }

    private static String clave() {
        return UUID.randomUUID().toString();
    }

    private static CobranzaPedido pedido(long cliente, String total, Imputar... imputaciones) {
        return new CobranzaPedido(clave(), cliente, MedioPago.EFECTIVO, new BigDecimal(total), List.of(imputaciones));
    }

    private Callable<CobroRegistrado> cobrar(Escenario e, CobranzaPedido pedido) {
        return () -> cobranzaService.cobrar(pedido, e.f().usuarioId());
    }

    /** Un cobro ya hecho (síncrono, en el hilo del test); devuelve el id del recibo. */
    private long cobroHecho(Escenario e, long cliente, String total, Imputar... imputaciones) {
        return cobranzaService.cobrar(pedido(cliente, total, imputaciones), e.f().usuarioId()).recibo().getId();
    }

    private Callable<CobroRegistrado> anular(Escenario e, long recibo) {
        return () -> anulacionService.anular(recibo, MOTIVO, e.f().usuarioId());
    }

    private long articulo(Ferreteria f) {
        String codigo = "C-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, "
                + "alicuota_iva, unidad, stock_actual, stock_minimo, activo) values (?, ?, 'Tornillo', 1.00, 1.00, 'IVA_21', "
                + "'UNIDAD', 10000000, 0, 1)", f.id(), codigo);
        return jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?", Long.class,
                f.id(), codigo);
    }

    /** Una venta íntegra a cuenta corriente de {@code unidades} × $ 1,00 (sin autorización de exceso: la hace «un Vendedor»). */
    private Callable<?> ventaCC(Escenario e, long articulo, long cliente, int unidades) {
        long forma = jdbc.queryForObject("select id from forma_pago where ferreteria_id = ? and medio = 'CUENTA_CORRIENTE'",
                Long.class, e.f().id());
        ConfirmarVenta venta = new ConfirmarVenta(clave(),
                List.of(new Renglon(articulo, new BigDecimal(unidades), new BigDecimal("1.00"))), new BigDecimal(unidades),
                forma, BigDecimal.ZERO, List.of(), null, null, true, cliente, false);
        return () -> ventaService.cobrar(venta, e.f().usuarioId(), false);
    }

    private Callable<?> desactivar(long cliente) {
        return () -> clienteService.cambiarActivo(cliente, false);
    }

    private boolean activo(long cliente) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select activo from cliente where id = ?", Boolean.class, cliente));
    }

    private Long ultimoNumero(long ferreteriaId) {
        List<Long> filas = jdbc.queryForList("select ultimo_numero from numerador_recibo where ferreteria_id = ?", Long.class,
                ferreteriaId);
        return filas.isEmpty() ? null : filas.get(0);
    }

    private String estado(long recibo) {
        return jdbc.queryForObject("select estado from recibo where id = ?", String.class, recibo);
    }

    private long anulaciones(long cliente) {
        return jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente where cliente_id = ? and tipo = 'ANULACION'",
                Long.class, cliente);
    }

    private long vigentes(long recibo) {
        return jdbc.queryForObject("select count(*) from imputacion where recibo_id = ? and vigente = 1", Long.class, recibo);
    }

    private long noVigentes(long recibo) {
        return jdbc.queryForObject("select count(*) from imputacion where recibo_id = ? and vigente = 0", Long.class, recibo);
    }

    /** El recibo sigue EMITIDO, sin ANULACION, con sus imputaciones vigentes y el saldo esperado (nada se escribió). */
    private void sinAnular(long cliente, long recibo, String saldoEsperado) {
        assertThat(estado(recibo)).as("estado del recibo").isEqualTo("EMITIDO");
        assertThat(anulaciones(cliente)).as("ANULACION en el libro").isZero();
        assertThat(noVigentes(recibo)).as("imputaciones no vigentes").isZero();
        assertThat(saldo(cliente)).as("saldo").isEqualByComparingTo(saldoEsperado);
    }

    // ===============================================================================================================
    // Hilos, pausas y resultados
    // ===============================================================================================================

    /** Lo que terminó un hilo: su valor o el error con que cayó (para afirmar sobre cada uno sin ExecutionException). */
    private record Salida(Object valor, Throwable error) {

        CobroRegistrado cobro() {
            assertThat(error).as("se esperaba un resultado y falló con %s", error).isNull();
            return (CobroRegistrado) valor;
        }

        boolean ok() {
            return error == null;
        }

        CobranzaRechazadaException rechazo() {
            assertThat(error).as("se esperaba un rechazo de cobranza/anulación y fue %s", error)
                    .isInstanceOf(CobranzaRechazadaException.class);
            return (CobranzaRechazadaException) error;
        }

        /** El 409 EXACTO de la segunda anulación: RECIBO_YA_ANULADO con su status y el mensaje de quién y cuándo. */
        void esYaAnulado() {
            CobranzaRechazadaException r = rechazo();
            assertThat(r.motivo()).as("motivo del rechazo (se esperaba 409 y no un 500)").isEqualTo(Motivo.RECIBO_YA_ANULADO);
            assertThat(r.motivo().estadoHttp()).isEqualTo(409);
            assertThat(r.getMessage()).matches("Este recibo ya fue anulado por .+ el \\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2}\\.");
            assertThat(r.anuladoPor()).isNotBlank();
            assertThat(r.fechaAnulacion()).isNotNull();
        }
    }

    private Future<Salida> lanzar(ExecutorService pool, Ferreteria f, Callable<?> trabajo) {
        return pool.submit(() -> {
            TenantContext.setCurrentFerreteriaId(f.id());
            try {
                return new Salida(trabajo.call(), null);
            } catch (Throwable t) {
                return new Salida(null, t);
            } finally {
                TenantContext.clear();
            }
        });
    }

    /** Igual, pero registra el hilo como el que se pausa en el punto elegido. */
    private Future<Salida> lanzarPausable(ExecutorService pool, Ferreteria f, Callable<?> trabajo) {
        return lanzar(pool, f, () -> {
            hiloAPausar.set(Thread.currentThread());
            return trabajo.call();
        });
    }

    private static Salida resultado(Future<Salida> futuro) throws Exception {
        return futuro.get(60, TimeUnit.SECONDS);
    }

    /** Lo que tiene que terminar SOLO, sin que nadie lo suelte: si queda esperando es un lock de más (falla con ese motivo). */
    private static Salida terminaSola(Future<Salida> futuro, String siSeBloquea) throws Exception {
        try {
            return futuro.get(20, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            throw new AssertionError("Quedó esperando más de 20 s sin que nadie lo soltara: " + siSeBloquea, e);
        }
    }

    /** N trabajos con largada común (todos listos antes de que arranque el primero); devuelve cada resultado en orden. */
    private List<Salida> enParalelo(Ferreteria f, List<? extends Callable<?>> trabajos) throws Exception {
        Largada largada = new Largada(trabajos.size());
        ExecutorService pool = Executors.newFixedThreadPool(trabajos.size());
        try {
            List<Future<Salida>> futuros = new ArrayList<>();
            for (Callable<?> trabajo : trabajos) {
                futuros.add(lanzar(pool, f, () -> {
                    largada.listoYEsperar();
                    return trabajo.call();
                }));
            }
            largada.esperarListos(Duration.ofSeconds(30));
            largada.largar();
            List<Salida> salidas = new ArrayList<>();
            for (Future<Salida> futuro : futuros) {
                salidas.add(resultado(futuro));
            }
            return salidas;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Dónde se pausa el primero: dentro de cuál método del puerto (siempre con la caja, el cliente y, según el punto, más tomados). */
    private enum Punto { ASENTAR_ANULACION, ASENTAR_COBRO, ASENTAR_VENTA, VERIFICAR_CUPO }

    private void pausar(Punto punto, Hito llego, Hito suelta, AtomicReference<Long> comprobanteDeLaVenta) {
        CuentaCorrienteService espia = AopTestUtils.getUltimateTargetObject(servicioEspiado);
        org.mockito.stubbing.Answer<Object> pausa = invocacion -> {
            if (Thread.currentThread() == hiloAPausar.get()) {
                if (punto == Punto.ASENTAR_VENTA) {
                    comprobanteDeLaVenta.set(((com.ferreteria.clientes.api.CuentaCorrienteParaVenta.AsientoVenta) invocacion
                            .getArgument(0)).comprobanteId());
                }
                llego.avisar();
                suelta.esperar();
            }
            return invocacion.callRealMethod();
        };
        switch (punto) {
            case ASENTAR_ANULACION -> doAnswer(pausa).when(espia).asentarAnulacion(any());
            case ASENTAR_COBRO -> doAnswer(pausa).when(espia).asentarCobro(any());
            case ASENTAR_VENTA -> doAnswer(pausa).when(espia).asentarVenta(any());
            case VERIFICAR_CUPO -> doAnswer(pausa).when(espia).verificarCupo(any(), any());
        }
    }

    private record Par(Salida primero, Salida segundo) {}

    /**
     * El escenario de casi todo test de orden: el PRIMERO queda retenido en {@code punto} con todo tomado y sin confirmar; el
     * SEGUNDO se lanza y se espera a que esté en LOCK WAIT en MySQL; {@code conElSegundoBloqueado} corre con el segundo YA en
     * LOCK WAIT y el primero todavía retenido (para afirmar QUÉ espera); se suelta al primero y se juntan los dos resultados.
     * Exige delta {@code lock_deadlocks} = 0.
     */
    private Par retenidoYBloqueado(Escenario e, Punto punto, Callable<?> primero, Callable<?> segundo,
            Consumer<BarreraDeLocks> conElSegundoBloqueado) throws Exception {
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        Hito llego = new Hito();
        Hito suelta = new Hito();
        pausar(punto, llego, suelta, new AtomicReference<>());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Salida> t1 = lanzarPausable(pool, e.f(), primero);
            llego.esperar(); // el primero tiene todo tomado, está dentro del punto y no confirmó
            Future<Salida> t2 = lanzar(pool, e.f(), segundo);
            barrera.esperarBloqueados(1); // el segundo está en LOCK WAIT en MySQL
            conElSegundoBloqueado.accept(barrera);
            suelta.avisar();
            Par par = new Par(resultado(t1), resultado(t2));
            barrera.assertSinDeadlocks(deadlocksAntes);
            return par;
        } finally {
            suelta.avisar();
            pool.shutdownNow();
        }
    }

    private Par retenidoYBloqueado(Escenario e, Punto punto, Callable<?> primero, Callable<?> segundo) throws Exception {
        return retenidoYBloqueado(e, punto, primero, segundo, barrera -> { });
    }

    private static void esperaAlCliente(BarreraDeLocks barrera) {
        assertThat(barrera.objetosEnEspera()).as("el segundo espera al cliente (ya pasó la caja en S)").containsExactly("cliente");
    }

    // ===============================================================================================================
    // CCT3B1-401. AN-1, AN-2, AN-3
    // ===============================================================================================================

    @Test
    @DisplayName("AN-1 (con imputaciones): recibo de 600 imputado a A; A anula (retenida) y B anula el MISMO recibo (bloqueada en el cliente) → A 200 y B 409 RECIBO_YA_ANULADO exacto (NO 500); una ANULACION y el saldo sube 600 una vez")
    void an1ConImputaciones() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta venta = ventaEnLibro(e.f(), cliente, "600");
        long recibo = cobroHecho(e, cliente, "600", imputa(venta.comprobanteId(), "600"));
        assertThat(saldo(cliente)).isEqualByComparingTo("0");

        Par par = retenidoYBloqueado(e, Punto.ASENTAR_ANULACION, anular(e, recibo), anular(e, recibo),
                AnulacionConcurrenciaIntegrationTest::esperaAlCliente);

        assertThat(par.primero().cobro().recibo().isAnulado()).isTrue();
        par.segundo().esYaAnulado();
        assertThat(anulaciones(cliente)).isEqualTo(1);
        assertThat(saldo(cliente)).as("+600 una sola vez").isEqualByComparingTo("600");
        assertThat(estado(recibo)).isEqualTo("ANULADO");
        assertThat(noVigentes(recibo)).isEqualTo(1);
        assertThat(vigentes(recibo)).isZero();
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("AN-1 (adelanto, sin imputaciones): A anula el recibo de 600 y B el mismo → 200 y 409 RECIBO_YA_ANULADO exacto; una ANULACION y saldo 0 (−600 + 600)")
    void an1Adelanto() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), null);
        long recibo = cobroHecho(e, cliente, "600");
        assertThat(saldo(cliente)).isEqualByComparingTo("-600");

        Par par = retenidoYBloqueado(e, Punto.ASENTAR_ANULACION, anular(e, recibo), anular(e, recibo),
                AnulacionConcurrenciaIntegrationTest::esperaAlCliente);

        par.primero().cobro();
        par.segundo().esYaAnulado();
        assertThat(anulaciones(cliente)).isEqualTo(1);
        assertThat(saldo(cliente)).isZero();
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("AN-2: recibos de 300 y 500 del mismo cliente; B espera a A (mismo cliente) → ambas 200, saldo +800 y el recibo de cada una anulado")
    void an2DosRecibosDelMismoCliente() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "300");
        VentaSuelta b = ventaEnLibro(e.f(), cliente, "500");
        long r1 = cobroHecho(e, cliente, "300", imputa(a.comprobanteId(), "300"));
        long r2 = cobroHecho(e, cliente, "500", imputa(b.comprobanteId(), "500"));
        assertThat(saldo(cliente)).isZero();

        Par par = retenidoYBloqueado(e, Punto.ASENTAR_ANULACION, anular(e, r1), anular(e, r2),
                AnulacionConcurrenciaIntegrationTest::esperaAlCliente);

        par.primero().cobro();
        par.segundo().cobro();
        assertThat(saldo(cliente)).isEqualByComparingTo("800");
        assertThat(anulaciones(cliente)).isEqualTo(2);
        assertThat(estado(r1)).isEqualTo("ANULADO");
        assertThat(estado(r2)).isEqualTo("ANULADO");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("AN-3: A (cliente 1) retenida y B (cliente 2) anula → B termina SOLA con 200, sin ninguna transacción en LOCK WAIT (live e INNODB_TRX): la anulación no serializa la ferretería (no hay numerador)")
    void an3ClientesDistintosNoSeEsperan() throws Exception {
        Escenario e = escenario();
        long c1 = cliente(e.f(), null);
        long c2 = cliente(e.f(), null);
        long r1 = cobroHecho(e, c1, "100");
        long r2 = cobroHecho(e, c2, "200");
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        Hito llego = new Hito();
        Hito suelta = new Hito();
        pausar(Punto.ASENTAR_ANULACION, llego, suelta, new AtomicReference<>());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Salida> a = lanzarPausable(pool, e.f(), anular(e, r1));
            llego.esperar(); // A tiene la caja (S) y a c1 (X) y no confirmó

            Salida b = terminaSola(lanzar(pool, e.f(), anular(e, r2)),
                    "la anulación del cliente 2 se bloqueó con la del cliente 1 (comparten algún lock)");

            assertThat(b.cobro().recibo().isAnulado()).isTrue();
            assertThat(barrera.bloqueadosAhora()).as("transacciones en LOCK WAIT (live, data_lock_waits)").isZero();
            assertThat(jdbcRoot().queryForObject("select count(*) from information_schema.INNODB_TRX where trx_state = 'LOCK WAIT'",
                    Long.class)).as("transacciones en LOCK WAIT (INNODB_TRX)").isZero();
            assertThat(estado(r1)).as("A sigue sin confirmar").isEqualTo("EMITIDO");
            suelta.avisar();
            a.get(60, TimeUnit.SECONDS).cobro();
        } finally {
            suelta.avisar();
            pool.shutdownNow();
        }
        assertThat(saldo(c1)).isZero();
        assertThat(saldo(c2)).isZero();
        assertThat(ultimoNumero(e.f().id())).as("la anulación no consume numerador").isEqualTo(2L);
        assertInvariante(c1);
        assertInvariante(c2);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    // ===============================================================================================================
    // CCT3B1-402. AN-4: anulación × cobranza
    // ===============================================================================================================

    /** A de 1.000 y R1 que la saldó: pendiente 0, saldo 0. */
    private record ConR1(long cliente, VentaSuelta a, long r1) {}

    private ConR1 deudaSaldadaPorR1(Escenario e) {
        long cliente = cliente(e.f(), "10000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "1000");
        long r1 = cobroHecho(e, cliente, "1000", imputa(a.comprobanteId(), "1000"));
        assertThat(saldo(cliente)).isZero();
        return new ConR1(cliente, a, r1);
    }

    @Test
    @DisplayName("AN-4 (i): la anulación de R1 retenida; la cobranza R2 de 600 imputada a A queda bloqueada → al soltar es 201 imputando 600 a A (lectura actual + vigencia), A pendiente 400 y saldo 400")
    void an4AnulacionPrimero() throws Exception {
        Escenario e = escenario();
        ConR1 c = deudaSaldadaPorR1(e);

        Par par = retenidoYBloqueado(e, Punto.ASENTAR_ANULACION, anular(e, c.r1()),
                cobrar(e, pedido(c.cliente(), "600", imputa(c.a().comprobanteId(), "600"))),
                AnulacionConcurrenciaIntegrationTest::esperaAlCliente);

        assertThat(par.primero().cobro().recibo().isAnulado()).isTrue();
        CobroRegistrado r2 = par.segundo().cobro();
        assertThat(r2.imputaciones()).singleElement().satisfies(i -> assertThat(i.importe()).isEqualByComparingTo("600"));
        assertThat(servicioEspiado.pendientes(c.cliente()).comprobantes()).singleElement()
                .satisfies(p -> assertThat(p.pendiente()).isEqualByComparingTo("400"));
        assertThat(saldo(c.cliente())).isEqualByComparingTo("400");
        assertThat(estado(r2.recibo().getId())).isEqualTo("EMITIDO");
        assertInvariante(c.cliente());
    }

    @Test
    @DisplayName("AN-4 (ii): la cobranza R2 retenida; la anulación de R1 bloqueada → al soltar R2 es 409 IMPUTACION_EXCEDE_DEUDA con pendiente 0 (R1 aún vigente cuando R2 validó) y la anulación 200 (saldo 1.000, A pendiente 1.000); nunca otra combinación")
    void an4CobranzaPrimero() throws Exception {
        Escenario e = escenario();
        ConR1 c = deudaSaldadaPorR1(e);

        Par par = retenidoYBloqueado(e, Punto.ASENTAR_COBRO,
                cobrar(e, pedido(c.cliente(), "600", imputa(c.a().comprobanteId(), "600"))), anular(e, c.r1()),
                AnulacionConcurrenciaIntegrationTest::esperaAlCliente);

        CobranzaRechazadaException rechazo = par.primero().rechazo();
        assertThat(rechazo.motivo()).isEqualTo(Motivo.IMPUTACION_EXCEDE_DEUDA);
        assertThat(rechazo.deudas()).singleElement().satisfies(deuda -> {
            assertThat(deuda.comprobanteId()).isEqualTo(c.a().comprobanteId());
            assertThat(deuda.pendiente()).isEqualByComparingTo("0");
        });
        assertThat(par.segundo().cobro().recibo().isAnulado()).isTrue();
        assertThat(saldo(c.cliente())).isEqualByComparingTo("1000");
        assertThat(servicioEspiado.pendientes(c.cliente()).comprobantes()).singleElement()
                .satisfies(p -> assertThat(p.pendiente()).isEqualByComparingTo("1000"));
        assertThat(recibos(c.cliente())).as("R2 no se emitió (rollback)").isEqualTo(1);
        assertInvariante(c.cliente());
    }

    @Test
    @DisplayName("AN-4 (iii): anulación de R1 retenida y cobranza «a cuenta» de 300 (sin imputar) bloqueada → ambas exitosas, un COBRO por recibo y saldo coherente (1.000 − 300 = 700)")
    void an4ACuenta() throws Exception {
        Escenario e = escenario();
        ConR1 c = deudaSaldadaPorR1(e);

        Par par = retenidoYBloqueado(e, Punto.ASENTAR_ANULACION, anular(e, c.r1()), cobrar(e, pedido(c.cliente(), "300")),
                AnulacionConcurrenciaIntegrationTest::esperaAlCliente);

        par.primero().cobro();
        par.segundo().cobro();
        assertThat(cobros(c.cliente())).isEqualTo(2);
        assertThat(recibos(c.cliente())).isEqualTo(2);
        assertThat(saldo(c.cliente())).isEqualByComparingTo("700");
        PendientesCuenta pendientes = servicioEspiado.pendientes(c.cliente());
        assertThat(pendientes.aCuenta()).isEqualByComparingTo("300");
        assertThat(pendientes.comprobantes()).singleElement()
                .satisfies(p -> assertThat(p.pendiente()).isEqualByComparingTo("1000"));
        assertInvariante(c.cliente());
    }

    // ===============================================================================================================
    // CCT3B1-403. AN-5: anulación × venta a cuenta
    // ===============================================================================================================

    /** Límite 5.000, saldo 4.000 tras un cobro R de 1.000 (la deuda original de 5.000). */
    private record Limite(long cliente, long recibo) {}

    private Limite limite5000ConSaldo4000(Escenario e) {
        long cliente = cliente(e.f(), "5000");
        VentaSuelta v = ventaEnLibro(e.f(), cliente, "5000");
        long r = cobroHecho(e, cliente, "1000", imputa(v.comprobanteId(), "1000"));
        assertThat(saldo(cliente)).isEqualByComparingTo("4000");
        return new Limite(cliente, r);
    }

    @Test
    @DisplayName("AN-5 (i): venta CC de 800 retenida (sin autorizar) y la anulación bloqueada → al soltar la venta es 201 (saldo 4.800) y la anulación 200 (saldo 5.800: excedido, permitido); la venta aparece en pendientes con su deuda completa")
    void an5VentaPrimero() throws Exception {
        Escenario e = escenario();
        Limite l = limite5000ConSaldo4000(e);
        long articulo = articulo(e.f());

        Par par = retenidoYBloqueado(e, Punto.ASENTAR_VENTA, ventaCC(e, articulo, l.cliente(), 800), anular(e, l.recibo()),
                AnulacionConcurrenciaIntegrationTest::esperaAlCliente);

        assertThat(par.primero().error()).as("la venta de 800 entra: el saldo era 4.000").isNull();
        assertThat(par.segundo().cobro().recibo().isAnulado()).isTrue();
        assertThat(saldo(l.cliente())).isEqualByComparingTo("5800");
        assertThat(jdbc.queryForObject("select sum(debe - haber) from movimiento_cuenta_cliente where cliente_id = ?",
                BigDecimal.class, l.cliente())).as("saldo = Σ libro").isEqualByComparingTo("5800");
        assertThat(jdbc.queryForObject("select excedio_limite from movimiento_cuenta_cliente where cliente_id = ? "
                + "and tipo = 'ANULACION'", Boolean.class, l.cliente())).isFalse();
        PendientesCuenta pendientes = servicioEspiado.pendientes(l.cliente());
        assertThat(pendientes.comprobantes()).as("la venta de 800 con su deuda completa y la original liberada").anySatisfy(p -> {
            assertThat(p.importe()).isEqualByComparingTo("800");
            assertThat(p.imputado()).isEqualByComparingTo("0");
            assertThat(p.pendiente()).isEqualByComparingTo("800");
        });
        assertInvariante(l.cliente());
    }

    @Test
    @DisplayName("AN-5 (ii): la anulación retenida y la venta CC de 800 bloqueada → al soltar la venta (por VentaService real) es 422 LIMITE_CUENTA_CORRIENTE y el saldo queda en 5.000 (= Σ libro)")
    void an5AnulacionPrimero() throws Exception {
        Escenario e = escenario();
        Limite l = limite5000ConSaldo4000(e);
        long articulo = articulo(e.f());
        long ventasAntes = jdbc.queryForObject("select count(*) from comprobante where ferreteria_id = ?", Long.class, e.f().id());

        Par par = retenidoYBloqueado(e, Punto.ASENTAR_ANULACION, anular(e, l.recibo()), ventaCC(e, articulo, l.cliente(), 800),
                AnulacionConcurrenciaIntegrationTest::esperaAlCliente);

        assertThat(par.primero().cobro().recibo().isAnulado()).isTrue();
        assertThat(par.segundo().error()).isInstanceOfSatisfying(VentaRechazadaException.class,
                v -> assertThat(v.motivo()).isEqualTo(VentaRechazadaException.Motivo.LIMITE_CUENTA_CORRIENTE));
        assertThat(saldo(l.cliente())).isEqualByComparingTo("5000");
        assertThat(jdbc.queryForObject("select sum(debe - haber) from movimiento_cuenta_cliente where cliente_id = ?",
                BigDecimal.class, l.cliente())).isEqualByComparingTo("5000");
        assertThat(jdbc.queryForObject("select count(*) from comprobante where ferreteria_id = ?", Long.class, e.f().id()))
                .as("la venta rechazada no dejó comprobante").isEqualTo(ventasAntes);
        assertInvariante(l.cliente());
    }

    // ===============================================================================================================
    // CCT3B1-404. AN-6: anulación × cierre de caja
    // ===============================================================================================================

    private void inicial(Escenario e, String importe) {
        jdbc.update("update caja set efectivo_inicial = ? where id = ?", new BigDecimal(importe), e.caja());
    }

    @Test
    @DisplayName("AN-6 (i): caja inicial 5.000 y cobro ef. 1.000 (esperado 6.000); la anulación retenida (caja S) y el cierre bloqueado en la caja → al soltar la anulación es 200 y el cierre trae anulaciones.EFECTIVO 1.000 y esperado 5.000; recibo ANULADO ⇔ figura en el resumen de esa caja")
    void an6AnulacionPrimero() throws Exception {
        Escenario e = escenario();
        inicial(e, "5000");
        long cliente = cliente(e.f(), null);
        long recibo = cobroHecho(e, cliente, "1000");

        Par par = retenidoYBloqueado(e, Punto.ASENTAR_ANULACION, anular(e, recibo),
                () -> cajaService.cerrar(e.caja(), new BigDecimal("5000"), e.f().usuarioId()),
                barrera -> assertThat(barrera.objetosEnEspera()).as("el cierre espera la CAJA (X contra la S de la anulación)")
                        .containsExactly("caja"));

        par.primero().cobro();
        assertThat(par.segundo().error()).isNull();
        DetalleCaja cierre = (DetalleCaja) par.segundo().valor();
        assertThat(cierre.resumen().anulacionesEnEfectivo()).isEqualByComparingTo("1000");
        assertThat(cierre.resumen().cobranzasEnEfectivo()).as("las cobranzas siguen brutas").isEqualByComparingTo("1000");
        assertThat(cierre.resumen().efectivoEsperado()).isEqualByComparingTo("5000");
        assertThat(cierre.caja().getEfectivoEsperado()).isEqualByComparingTo("5000");
        assertThat(cierre.caja().getDiferencia()).isEqualByComparingTo("0");
        assertThat(jdbc.queryForObject("select caja_anulacion_id from recibo where id = ?", Long.class, recibo)).isEqualTo(e.caja());
        // recibo ANULADO ⇔ su anulación figura en el resumen de la caja que la absorbió
        assertThat(jdbc.queryForObject("select coalesce(sum(total), 0) from recibo where caja_anulacion_id = ? and estado = 'ANULADO'",
                BigDecimal.class, e.caja())).isEqualByComparingTo(cierre.resumen().totalAnulaciones());
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("AN-6 (ii): un holder cierra la caja (X) sin confirmar; la anulación queda bloqueada → al confirmar es 422 CAJA_CERRADA «Abrí la caja para anular.», recibo EMITIDO, sin ANULACION y saldo intacto; nunca una anulación con caja cerrada")
    void an6LaCajaSeCierraMientrasLaAnulacionEspera() throws Exception {
        Escenario e = escenario();
        inicial(e, "5000");
        long cliente = cliente(e.f(), null);
        long recibo = cobroHecho(e, cliente, "1000");
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        // el mismo SQL que el cierre: toma la caja (FOR UPDATE) y la marca cerrada, sin confirmar
        BarreraDeLocks.HolderJdbc holder = BarreraDeLocks.holderJdbc("select id from caja where id = " + e.caja() + " for update",
                "update caja set abierta = null, fecha_cierre = now(6), usuario_cierre_id = " + e.f().usuarioId()
                        + ", efectivo_contado = 6000, efectivo_esperado = 6000, diferencia = 0 where id = " + e.caja());
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<Salida> anulacion = lanzar(pool, e.f(), anular(e, recibo));
            barrera.esperarBloqueados(1);
            assertThat(barrera.objetosEnEspera()).as("la anulación espera la caja").containsExactly("caja");
            assertThat(BarreraDeLocks.sePuedeTomarYa("cliente", cliente))
                    .as("la anulación bloqueada en la caja NO retiene al cliente (caja antes que cliente)").isTrue();
            holder.commit();

            CobranzaRechazadaException rechazo = resultado(anulacion).rechazo();
            assertThat(rechazo.motivo()).isEqualTo(Motivo.CAJA_CERRADA);
            assertThat(rechazo.getMessage()).isEqualTo("Abrí la caja para anular.");
        } finally {
            holder.rollback();
            pool.shutdownNow();
        }
        sinAnular(cliente, recibo, "-1000");
        assertThat(jdbc.queryForObject("select count(*) from recibo where caja_anulacion_id = ?", Long.class, e.caja())).isZero();
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    @Test
    @DisplayName("AN-6 (iii): con el cierre REAL ya hecho (esperado 6.000, cobranzas 1.000), la anulación es 422 CAJA_CERRADA: recibo EMITIDO, sin ANULACION, esperado guardado 6.000 y anulaciones 0")
    void an6CierreRealPrimero() {
        Escenario e = escenario();
        inicial(e, "5000");
        long cliente = cliente(e.f(), null);
        long recibo = cobroHecho(e, cliente, "1000");
        DetalleCaja cierre = cajaService.cerrar(e.caja(), new BigDecimal("6000"), e.f().usuarioId());
        assertThat(cierre.resumen().efectivoEsperado()).isEqualByComparingTo("6000");

        assertThatThrownBy(() -> anulacionService.anular(recibo, MOTIVO, e.f().usuarioId()))
                .isInstanceOfSatisfying(CobranzaRechazadaException.class, r -> assertThat(r.motivo()).isEqualTo(Motivo.CAJA_CERRADA));

        sinAnular(cliente, recibo, "-1000");
        assertThat(jdbc.queryForObject("select efectivo_esperado from caja where id = ?", BigDecimal.class, e.caja()))
                .isEqualByComparingTo("6000");
        TenantContext.setCurrentFerreteriaId(e.f().id());
        assertThat(cajaService.detalle(e.caja()).resumen().anulacionesEnEfectivo()).isEqualByComparingTo("0");
        assertInvariante(cliente);
    }

    // ===============================================================================================================
    // CCT3B1-405. AN-7: anulación × desactivar cliente
    // ===============================================================================================================

    @Test
    @DisplayName("AN-7 (i): R ya imputado (saldo 0); la anulación retenida y desactivar bloqueado en el cliente → al soltar desactivar es 422 por saldo ≠ 0 (1.000) y el cliente sigue activo")
    void an7AnulacionPrimeroDesactivarFalla() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta venta = ventaEnLibro(e.f(), cliente, "1000");
        long recibo = cobroHecho(e, cliente, "1000", imputa(venta.comprobanteId(), "1000"));
        assertThat(saldo(cliente)).isZero();

        Par par = retenidoYBloqueado(e, Punto.ASENTAR_ANULACION, anular(e, recibo), desactivar(cliente),
                AnulacionConcurrenciaIntegrationTest::esperaAlCliente);

        par.primero().cobro();
        assertThat(par.segundo().error()).isInstanceOf(ReglaDeNegocioException.class).hasMessage(CON_SALDO);
        assertThat(saldo(cliente)).isEqualByComparingTo("1000");
        assertThat(activo(cliente)).as("nunca un cliente inactivo con saldo ≠ 0").isTrue();
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("AN-7 (ii): un holder desactivó al cliente (saldo 0) y no confirmó; la anulación queda bloqueada → al confirmar es 422 «El cliente está desactivado.» sin efectos (recibo EMITIDO, sin ANULACION)")
    void an7DesactivarPrimeroLaAnulacionFalla() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta venta = ventaEnLibro(e.f(), cliente, "300");
        long recibo = cobroHecho(e, cliente, "300", imputa(venta.comprobanteId(), "300"));
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        // el mismo SQL que cambiarActivo: toma al cliente (FOR UPDATE) y lo desactiva (saldo 0), sin confirmar
        BarreraDeLocks.HolderJdbc holder = BarreraDeLocks.holderJdbc("select id from cliente where id = " + cliente + " for update",
                "update cliente set activo = 0 where id = " + cliente);
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<Salida> anulacion = lanzar(pool, e.f(), anular(e, recibo));
            barrera.esperarBloqueados(1);
            assertThat(barrera.objetosEnEspera()).as("la anulación espera al cliente").containsExactly("cliente");
            holder.commit();

            assertThat(resultado(anulacion).error()).isInstanceOf(ReglaDeNegocioException.class)
                    .hasMessage("El cliente está desactivado.");
        } finally {
            holder.rollback();
            pool.shutdownNow();
        }
        sinAnular(cliente, recibo, "0");
        assertThat(vigentes(recibo)).isEqualTo(1);
        assertThat(activo(cliente)).isFalse();
        assertInvariante(cliente); // I-inactivo: saldo 0
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    @Test
    @DisplayName("AN-7 (iii-a): adelanto de 500 (saldo −500); la anulación retenida y desactivar bloqueado → al soltar el saldo es 0 y desactivar da 200 (cliente inactivo con saldo 0)")
    void an7AdelantoAnulacionPrimeroDesactivaEntra() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), null);
        long recibo = cobroHecho(e, cliente, "500");
        assertThat(saldo(cliente)).isEqualByComparingTo("-500");

        Par par = retenidoYBloqueado(e, Punto.ASENTAR_ANULACION, anular(e, recibo), desactivar(cliente),
                AnulacionConcurrenciaIntegrationTest::esperaAlCliente);

        par.primero().cobro();
        assertThat(par.segundo().error()).isNull();
        assertThat(saldo(cliente)).isZero();
        assertThat(activo(cliente)).isFalse();
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("AN-7 (iii-b): adelanto de 500; un holder toma al cliente y desactivar y la anulación esperan a la vez → la anulación es 200 siempre; desactivar es 422 (saldo −500, si fue primero) o 200 (saldo 0, si fue después); nunca un cliente inactivo con saldo ≠ 0")
    void an7AdelantoDesactivarPrimeroFalla() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), null);
        long recibo = cobroHecho(e, cliente, "500");
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        BarreraDeLocks.HolderJdbc holder = BarreraDeLocks.holderJdbc("select id from cliente where id = " + cliente + " for update");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Salida> desactivacion = lanzar(pool, e.f(), desactivar(cliente));
            barrera.esperarBloqueados(1); // desactivar entra primero a la cola del cliente
            Future<Salida> anulacion = lanzar(pool, e.f(), anular(e, recibo));
            barrera.esperarBloqueados(2);
            holder.commit();

            Salida d = resultado(desactivacion);
            assertThat(resultado(anulacion).cobro().recibo().isAnulado()).isTrue();
            // el orden en que MySQL le da el lock al que espera no lo fija el test: valen las dos salidas de la spec (CC-090)
            if (d.ok()) {
                assertThat(activo(cliente)).as("desactivar entró DESPUÉS de la anulación: saldo 0").isFalse();
            } else {
                assertThat(d.error()).isInstanceOf(ReglaDeNegocioException.class).hasMessage(CON_SALDO);
                assertThat(activo(cliente)).as("desactivar entró ANTES: saldo −500 → 422").isTrue();
            }
        } finally {
            holder.rollback();
            pool.shutdownNow();
        }
        assertThat(saldo(cliente)).isZero();
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    @Test
    @DisplayName("AN-7 (iii-c): desactivar primero con el adelanto vigente (saldo −500) es 422 y después la anulación es 200 (cliente activo): nunca una anulación sobre un desactivado")
    void an7DesactivarYDespuesAnular() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), null);
        long recibo = cobroHecho(e, cliente, "500");

        assertThatThrownBy(() -> clienteService.cambiarActivo(cliente, false)).isInstanceOf(ReglaDeNegocioException.class)
                .hasMessage(CON_SALDO);
        anulacionService.anular(recibo, MOTIVO, e.f().usuarioId());

        assertThat(saldo(cliente)).isZero();
        assertThat(activo(cliente)).isTrue();
        assertInvariante(cliente);
    }

    // ===============================================================================================================
    // CCT3B1-406. AN-9: atomicidad
    // ===============================================================================================================

    /** Recibo imputado a una venta (con imputaciones que tocar) y el cliente, para ver que nada se mueve. */
    private record Imputado(long cliente, long recibo) {}

    private Imputado reciboConImputaciones(Escenario e) {
        long cliente = cliente(e.f(), "5000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "400");
        VentaSuelta b = ventaEnLibro(e.f(), cliente, "600");
        long recibo = cobroHecho(e, cliente, "1000", imputa(a.comprobanteId(), "400"), imputa(b.comprobanteId(), "600"));
        assertThat(saldo(cliente)).isZero();
        return new Imputado(cliente, recibo);
    }

    private void nadaCambio(Escenario e, Imputado i) {
        sinAnular(i.cliente(), i.recibo(), "0");
        assertThat(vigentes(i.recibo())).isEqualTo(2);
        assertThat(ultimoNumero(e.f().id())).as("el numerador no cambia").isEqualTo(1L);
        assertThat(servicioEspiado.pendientes(i.cliente()).comprobantes()).as("sin pendientes: todo sigue imputado").isEmpty();
        assertInvariante(i.cliente());
    }

    @Test
    @DisplayName("AN-9 (a): falla simulada ANTES de escribir en asentarAnulacion (con el recibo ya actualizado en la transacción) → recibo EMITIDO, imputaciones vigentes, sin ANULACION y saldo intacto; el numerador no cambia")
    void an9FallaAntesDeAsentar() {
        Escenario e = escenario();
        Imputado i = reciboConImputaciones(e);
        CuentaCorrienteService espia = AopTestUtils.getUltimateTargetObject(servicioEspiado);
        doThrow(new IllegalStateException("falla simulada antes de asentar")).when(espia).asentarAnulacion(any());

        assertThatThrownBy(() -> anulacionService.anular(i.recibo(), MOTIVO, e.f().usuarioId()))
                .isInstanceOf(IllegalStateException.class).hasMessage("falla simulada antes de asentar");

        nadaCambio(e, i);
    }

    @Test
    @DisplayName("AN-9 (b): falla DESPUÉS de que el asiento real escribió imputaciones, libro y saldo → todo se revierte junto (recibo EMITIDO, imputaciones vigentes, sin ANULACION, saldo intacto)")
    void an9FallaDespuesDeAsentar() {
        Escenario e = escenario();
        Imputado i = reciboConImputaciones(e);
        CuentaCorrienteService espia = AopTestUtils.getUltimateTargetObject(servicioEspiado);
        AtomicBoolean escribio = new AtomicBoolean();
        doAnswer(invocacion -> {
            invocacion.callRealMethod();
            escribio.set(true);
            throw new IllegalStateException("falla simulada tras asentar");
        }).when(espia).asentarAnulacion(any());

        assertThatThrownBy(() -> anulacionService.anular(i.recibo(), MOTIVO, e.f().usuarioId()))
                .isInstanceOf(IllegalStateException.class).hasMessage("falla simulada tras asentar");

        assertThat(escribio).as("el asiento real llegó a escribir").isTrue();
        nadaCambio(e, i);
    }

    /** Un trigger de MySQL que falla en un punto del asiento (falla REAL de la base, sin mocks); se borra siempre. */
    private void conTrigger(String nombre, String tabla, String evento, String condicion, Runnable prueba) {
        JdbcTemplate root = jdbcRoot();
        root.execute("drop trigger if exists " + nombre);
        root.execute("create trigger " + nombre + " before " + evento + " on " + tabla + " for each row begin if " + condicion
                + " then signal sqlstate '45000' set message_text = 'falla simulada en la base'; end if; end");
        try {
            prueba.run();
        } finally {
            root.execute("drop trigger if exists " + nombre);
        }
    }

    @Test
    @DisplayName("AN-9 (c): la base falla al insertar la ANULACION en el libro (tras actualizar el recibo y dar de baja las imputaciones) → todo revertido; lo mismo si falla el UPDATE del saldo (tras el INSERT del libro)")
    void an9FallaLaBaseEnElLibroYEnElSaldo() {
        Escenario e = escenario();
        Imputado i = reciboConImputaciones(e);

        conTrigger("t_an9_libro", "movimiento_cuenta_cliente", "insert",
                "new.tipo = 'ANULACION' and new.cliente_id = " + i.cliente(), () -> {
                    assertThatThrownBy(() -> anulacionService.anular(i.recibo(), MOTIVO, e.f().usuarioId()))
                            .hasStackTraceContaining("falla simulada en la base");
                    nadaCambio(e, i);
                });
        conTrigger("t_an9_saldo", "cliente", "update", "new.id = " + i.cliente() + " and new.saldo <> old.saldo", () -> {
            assertThatThrownBy(() -> anulacionService.anular(i.recibo(), MOTIVO, e.f().usuarioId()))
                    .hasStackTraceContaining("falla simulada en la base");
            nadaCambio(e, i);
        });

        // y sin fallas la misma anulación entra: el rollback no dejó nada que la bloquee
        anulacionService.anular(i.recibo(), MOTIVO, e.f().usuarioId());
        assertThat(saldo(i.cliente())).isEqualByComparingTo("1000");
        assertInvariante(i.cliente());
    }

    @Test
    @DisplayName("AN-9 / CC-061: si la transacción de A quedó con una foto vieja y otra anulación del mismo recibo ya confirmó, el INSERT choca con uk_mcc_recibo_tipo y la fachada lo traduce a 409 RECIBO_YA_ANULADO (nunca 500); lo de A se revierte y queda UNA sola ANULACION")
    void an9ChoqueDelUniqueSeTraduceA409() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), null);
        long recibo = cobroHecho(e, cliente, "600");
        BigDecimal total = new BigDecimal("600");
        // En la transacción de A, justo tras tomar la caja: (1) una lectura plana fija la foto REPEATABLE READ (lo que haría una
        // regresión de orden, mutación M1); (2) otra «transacción» (conexión root) anula el MISMO recibo completo y confirma.
        // Después A toma al cliente (libre), lee el recibo con la foto vieja (EMITIDO), llega al asiento y el INSERT choca.
        CajaService cajaEspia = AopTestUtils.getUltimateTargetObject(cajaEspiada);
        doAnswer(invocacion -> {
            if (Thread.currentThread() == hiloAPausar.get()) {
                em.createNativeQuery("select estado from recibo where id = ?1").setParameter(1, recibo).getSingleResult();
                JdbcTemplate root = jdbcRoot();
                root.update("update recibo set estado = 'ANULADO', fecha_anulacion = now(6), usuario_anulacion_id = ?, "
                        + "caja_anulacion_id = ?, motivo_anulacion = 'Anulada por otra transacción' where id = ?",
                        e.f().usuarioId(), e.caja(), recibo);
                root.update("insert into movimiento_cuenta_cliente (ferreteria_id, cliente_id, tipo, debe, haber, fecha, usuario_id, "
                        + "recibo_id, referencia, motivo) values (?, ?, 'ANULACION', ?, 0, now(6), ?, ?, 'Anulación Recibo N.º 1', "
                        + "'Anulada por otra transacción')", e.f().id(), cliente, total, e.f().usuarioId(), recibo);
                root.update("update cliente set saldo = saldo + ? where id = ?", total, cliente);
            }
            return invocacion.callRealMethod();
        }).when(cajaEspia).cajaParaAnular();
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Salida a = resultado(lanzarPausable(pool, e.f(), anular(e, recibo)));

            a.esYaAnulado();
            assertThat(a.rechazo().anuladoPor()).isEqualTo("Dueno CC");
        } finally {
            pool.shutdownNow();
        }
        assertThat(anulaciones(cliente)).as("solo la ANULACION de la otra transacción").isEqualTo(1);
        assertThat(saldo(cliente)).as("+600 una sola vez").isZero();
        assertThat(jdbc.queryForObject("select motivo_anulacion from recibo where id = ?", String.class, recibo))
                .as("lo de A se revirtió: queda lo de la otra").isEqualTo("Anulada por otra transacción");
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    // ===============================================================================================================
    // CCT3B1-407. AN-10: sin deadlocks ni 500 bajo carga
    // ===============================================================================================================

    /** Una respuesta de negocio (2xx o un 4xx esperado) o el 503 «reintentá»: lo único que puede salir de las operaciones. */
    private static boolean esRespuestaDeNegocio(Throwable error) {
        return error == null || error instanceof CobranzaRechazadaException || error instanceof VentaRechazadaException
                || error instanceof ReglaDeNegocioException || error instanceof ResourceNotFoundException
                || error instanceof ConflictException || error instanceof SistemaOcupadoException;
    }

    @Test
    @DisplayName("AN-10 (a): 6 anulaciones de 6 recibos distintos + 4 cobranzas + 3 ventas CC sobre 3 clientes, largada común → todas 2xx, ninguna 500, delta lock_deadlocks 0, saldo final = base + Σ anulaciones − Σ cobranzas + Σ ventas")
    void an10CargaMixtaTodoEntra() throws Exception {
        Escenario e = escenario();
        long articulo = articulo(e.f());
        List<Long> clientes = new ArrayList<>();
        List<Long> recibos = new ArrayList<>();
        List<VentaSuelta> deudas = new ArrayList<>();
        for (int c = 0; c < 3; c++) {
            long cliente = cliente(e.f(), "100000");
            clientes.add(cliente);
            VentaSuelta deuda = ventaEnLibro(e.f(), cliente, "5000");
            deudas.add(deuda);
            recibos.add(cobroHecho(e, cliente, "300", imputa(deuda.comprobanteId(), "300")));
            recibos.add(cobroHecho(e, cliente, "200", imputa(deuda.comprobanteId(), "200")));
        }
        BigDecimal baseTotal = clientes.stream().map(this::saldo).reduce(BigDecimal.ZERO, BigDecimal::add); // 3 × 4.500
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        List<Callable<?>> trabajos = new ArrayList<>();
        recibos.forEach(r -> trabajos.add(anular(e, r)));
        for (int c = 0; c < 4; c++) {
            long cliente = clientes.get(c % 3);
            trabajos.add(cobrar(e, pedido(cliente, "100", imputa(deudas.get(c % 3).comprobanteId(), "100"))));
        }
        for (int c = 0; c < 3; c++) {
            trabajos.add(ventaCC(e, articulo, clientes.get(c), 50));
        }

        List<Salida> salidas = enParalelo(e.f(), trabajos);

        salidas.forEach(s -> assertThat(s.error()).as("toda operación entra (sin cierre ni desactivación)").isNull());
        BigDecimal esperado = baseTotal.add(new BigDecimal("1500")) // 3 × (300 + 200) anulados
                .subtract(new BigDecimal("400")).add(new BigDecimal("150")); // 4 cobranzas de 100 y 3 ventas de 50
        assertThat(clientes.stream().map(this::saldo).reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(esperado);
        recibos.forEach(r -> assertThat(estado(r)).isEqualTo("ANULADO"));
        clientes.forEach(this::assertInvariante);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    @Test
    @DisplayName("AN-10 (b): en 4 rondas, 6 anulaciones + 4 cobranzas + 3 ventas CC + un cierre + una desactivación por ferretería a la vez → cada respuesta es de negocio o 503 (NINGUNA 500), delta lock_deadlocks 0, saldo final = base + Σ(anulaciones 200) − Σ(cobranzas 201) + Σ(ventas 201) e invariantes")
    void an10CargaConCierresYDesactivaciones() throws Exception {
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        for (int ronda = 0; ronda < 4; ronda++) {
            Escenario e = escenario();
            long articulo = articulo(e.f());
            List<Long> clientes = new ArrayList<>();
            List<Long> recibos = new ArrayList<>();
            List<VentaSuelta> deudas = new ArrayList<>();
            for (int c = 0; c < 3; c++) {
                long cliente = cliente(e.f(), "100000");
                clientes.add(cliente);
                VentaSuelta deuda = ventaEnLibro(e.f(), cliente, "5000");
                deudas.add(deuda);
                recibos.add(cobroHecho(e, cliente, "300", imputa(deuda.comprobanteId(), "300")));
                recibos.add(cobroHecho(e, cliente, "200", imputa(deuda.comprobanteId(), "200")));
            }
            BigDecimal base = clientes.stream().map(this::saldo).reduce(BigDecimal.ZERO, BigDecimal::add);
            List<Callable<?>> trabajos = new ArrayList<>();
            List<String> rotulos = new ArrayList<>();
            for (int r = 0; r < recibos.size(); r++) {
                trabajos.add(anular(e, recibos.get(r)));
                rotulos.add("anulacion:" + (r % 2 == 0 ? "300" : "200"));
            }
            for (int c = 0; c < 4; c++) {
                trabajos.add(cobrar(e, pedido(clientes.get(c % 3), "100", imputa(deudas.get(c % 3).comprobanteId(), "100"))));
                rotulos.add("cobranza:100");
            }
            for (int c = 0; c < 3; c++) {
                trabajos.add(ventaCC(e, articulo, clientes.get(c), 50));
                rotulos.add("venta:50");
            }
            trabajos.add(() -> cajaService.cerrar(e.caja(), BigDecimal.ZERO, e.f().usuarioId()));
            rotulos.add("cierre");
            trabajos.add(desactivar(clientes.get(2)));
            rotulos.add("desactivar");

            List<Salida> salidas = enParalelo(e.f(), trabajos);

            BigDecimal delta = BigDecimal.ZERO;
            for (int s = 0; s < salidas.size(); s++) {
                Salida salida = salidas.get(s);
                assertThat(esRespuestaDeNegocio(salida.error())).as("ronda %d, %s: %s", ronda, rotulos.get(s), salida.error()).isTrue();
                if (salida.ok()) {
                    String[] partes = rotulos.get(s).split(":");
                    BigDecimal importe = partes.length > 1 ? new BigDecimal(partes[1]) : BigDecimal.ZERO;
                    delta = switch (partes[0]) {
                        case "anulacion", "venta" -> delta.add(importe);
                        case "cobranza" -> delta.subtract(importe);
                        default -> delta;
                    };
                }
            }
            assertThat(clientes.stream().map(this::saldo).reduce(BigDecimal.ZERO, BigDecimal::add)).as("ronda %d", ronda)
                    .isEqualByComparingTo(base.add(delta));
            clientes.forEach(this::assertInvariante);
        }
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    // ===============================================================================================================
    // CCT3B1-409. CC-091 a nivel servicio: entrelazados forzados
    // ===============================================================================================================

    @Test
    @DisplayName("CC-091 (1): T1 anula R (retenida sin confirmar) y T2 y T3 anulan el mismo R ya pre-resueltas y esperando → T1 confirma → T2 y T3 son 409 RECIBO_YA_ANULADO (ninguna 200), una sola ANULACION y el saldo sube una vez")
    void cc091SegundaYTerceraPreResueltasDan409() throws Exception {
        Escenario e = escenario();
        Imputado i = reciboConImputaciones(e);
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        Hito llego = new Hito();
        Hito suelta = new Hito();
        pausar(Punto.ASENTAR_ANULACION, llego, suelta, new AtomicReference<>());
        ExecutorService pool = Executors.newFixedThreadPool(3);
        List<Salida> restantes = new ArrayList<>();
        try {
            Future<Salida> t1 = lanzarPausable(pool, e.f(), anular(e, i.recibo()));
            llego.esperar();
            Future<Salida> t2 = lanzar(pool, e.f(), anular(e, i.recibo()));
            Future<Salida> t3 = lanzar(pool, e.f(), anular(e, i.recibo()));
            barrera.esperarBloqueados(2); // las dos ya pasaron la pre-resolución y la caja y esperan al cliente
            assertThat(barrera.objetosEnEspera()).containsExactly("cliente");
            suelta.avisar();

            t1.get(60, TimeUnit.SECONDS).cobro();
            restantes.add(resultado(t2));
            restantes.add(resultado(t3));
        } finally {
            suelta.avisar();
            pool.shutdownNow();
        }
        restantes.forEach(Salida::esYaAnulado);
        assertThat(anulaciones(i.cliente())).isEqualTo(1);
        assertThat(saldo(i.cliente())).isEqualByComparingTo("1000");
        assertThat(noVigentes(i.recibo())).isEqualTo(2);
        assertInvariante(i.cliente());
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    @Test
    @DisplayName("CC-091 (2): T1 anula R1 (retenida) y T2 anula R2 del MISMO cliente esperando → T2 200 y el saldo = base + T1 + T2 (UPDATE relativo, sin pisar la foto vieja)")
    void cc091DosRecibosDelMismoClienteSumanSinPisarse() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), null);
        long r1 = cobroHecho(e, cliente, "300");
        long r2 = cobroHecho(e, cliente, "500");
        assertThat(saldo(cliente)).isEqualByComparingTo("-800");

        Par par = retenidoYBloqueado(e, Punto.ASENTAR_ANULACION, anular(e, r1), anular(e, r2),
                AnulacionConcurrenciaIntegrationTest::esperaAlCliente);

        par.primero().cobro();
        par.segundo().cobro();
        assertThat(saldo(cliente)).as("−800 + 300 + 500").isZero();
        assertThat(anulaciones(cliente)).isEqualTo(2);
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("CC-091 (3): T1 cobranza retenida imputando a A y T2 anulación de R1 (imputada a B) esperando → la anulación no pierde ni duplica la imputación de T1 (400 a A vigente) y baja solo la de R1")
    void cc091CobranzaRetenidaYAnulacionEsperando() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "10000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "1000");
        VentaSuelta b = ventaEnLibro(e.f(), cliente, "500");
        long r1 = cobroHecho(e, cliente, "300", imputa(b.comprobanteId(), "300"));
        assertThat(saldo(cliente)).isEqualByComparingTo("1200");

        Par par = retenidoYBloqueado(e, Punto.ASENTAR_COBRO,
                cobrar(e, pedido(cliente, "400", imputa(a.comprobanteId(), "400"))), anular(e, r1),
                AnulacionConcurrenciaIntegrationTest::esperaAlCliente);

        CobroRegistrado r2 = par.primero().cobro();
        par.segundo().cobro();
        assertThat(imputadoA(a.comprobanteId())).as("la imputación de T1 a A queda una sola vez").isEqualByComparingTo("400");
        assertThat(vigentes(r2.recibo().getId())).isEqualTo(1);
        assertThat(noVigentes(r1)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select coalesce(sum(importe), 0) from imputacion where comprobante_id = ? and vigente = 1",
                BigDecimal.class, b.comprobanteId())).as("B quedó sin imputar").isEqualByComparingTo("0");
        assertThat(saldo(cliente)).as("1.500 − 300 − 400 + 300").isEqualByComparingTo("1100");
        assertInvariante(cliente);
    }
}
