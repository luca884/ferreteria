package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;

import com.ferreteria.clientes.CuentaCorrienteTestBase;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.Imputar;
import com.ferreteria.clientes.api.CuentaCorrienteParaVenta.AsientoVenta;
import com.ferreteria.clientes.service.ClienteService;
import com.ferreteria.clientes.service.CuentaCorrienteService;
import com.ferreteria.clientes.service.PendientesCuenta;
import com.ferreteria.common.exception.ReglaDeNegocioException;
import com.ferreteria.support.BarreraDeLocks;
import com.ferreteria.support.BarreraDeLocks.Hito;
import com.ferreteria.support.BarreraDeLocks.Largada;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.model.MedioPago;
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
import java.util.function.Function;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.util.AopTestUtils;

/**
 * CCT3-313..319 (CB-1..CB-12, CC-044..CC-050): la concurrencia de la cobranza COMPLETA ({@link CobranzaService}: pre-chequeo,
 * caja, cliente, numerador, recibo, asiento) contra MySQL real, con hilos de verdad y SIN tiempos. Los hilos usan los servicios
 * (el controlador llega en C4). Cada escenario se sincroniza con {@link BarreraDeLocks}: la condición observable «el hilo B
 * está en LOCK WAIT en MySQL», hitos con timeout y largada común; nunca {@code sleep}/{@code parkNanos}/jitter. La pausa del
 * hilo A «con todo tomado y sin confirmar» es un {@code @SpyBean} de {@code CuentaCorrienteService} (sobre
 * {@code asentarCobro}, {@code asentarVenta} o {@code verificarCupo}); el «holder» es una conexión JDBC cruda con la
 * transacción abierta. Todo escenario exige delta {@code lock_deadlocks} = 0 e I1 + I2 al final. CUIT 30-8404NNNN-1.
 *
 * <p>Requisito firme de Luca: toda cobranza va bajo transacción con el lock de fila del cliente ANTES de cualquier SELECT
 * plano. La defensa es de VARIAS capas (cliente en X, numerador de recibo, partidas e imputaciones leídas con FOR UPDATE), y
 * cada una absorbe el fallo de otra: por eso el test de orden no es uno solo sino la suma de los que ven una consecuencia
 * distinta (ver el apply-progress, tabla de mutaciones de B6).
 */
class CobranzaConcurrenciaIntegrationTest extends CuentaCorrienteTestBase {

    @Autowired
    CobranzaService cobranzaService;

    @Autowired
    VentaService ventaService;

    @Autowired
    CajaService cajaService;

    @Autowired
    ClienteService clienteService;

    /** Pausa al hilo registrado dentro de {@code asentarCobro}/{@code asentarVenta}/{@code verificarCupo}: ya tiene todo tomado. */
    @SpyBean
    CuentaCorrienteService servicioEspiado;

    private final AtomicReference<Thread> hiloAPausar = new AtomicReference<>();

    @Override
    protected String prefijoDeCuit() {
        return "30-8404";
    }

    @AfterEach
    void soltarElEspia() {
        Mockito.reset((CuentaCorrienteService) AopTestUtils.getUltimateTargetObject(servicioEspiado));
        hiloAPausar.set(null);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Armado
    // ---------------------------------------------------------------------------------------------------------------

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

    private static CobranzaPedido pedido(String clave, long cliente, MedioPago medio, String total, Imputar... imputaciones) {
        return new CobranzaPedido(clave, cliente, medio, new BigDecimal(total), List.of(imputaciones));
    }

    /** Cobranza en efectivo con clave nueva. */
    private static CobranzaPedido pedido(long cliente, String total, Imputar... imputaciones) {
        return pedido(clave(), cliente, MedioPago.EFECTIVO, total, imputaciones);
    }

    private Callable<CobroRegistrado> cobrar(Escenario e, CobranzaPedido pedido) {
        return () -> cobranzaService.cobrar(pedido, e.f().usuarioId());
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

    private Long ultimoNumero(long ferreteriaId) {
        List<Long> filas = jdbc.queryForList("select ultimo_numero from numerador_recibo where ferreteria_id = ?", Long.class,
                ferreteriaId);
        return filas.isEmpty() ? null : filas.get(0);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Hilos, pausas y resultados
    // ---------------------------------------------------------------------------------------------------------------

    /** Lo que terminó un hilo: su valor o el error con que cayó (para afirmar sobre cada uno sin ExecutionException). */
    private record Salida(Object valor, Throwable error) {
        CobroRegistrado cobro() {
            assertThat(error).as("se esperaba un cobro y falló con %s", error).isNull();
            return (CobroRegistrado) valor;
        }

        CobranzaRechazadaException rechazo() {
            assertThat(error).as("se esperaba un rechazo de la cobranza y fue %s", error)
                    .isInstanceOf(CobranzaRechazadaException.class);
            return (CobranzaRechazadaException) error;
        }

        boolean ok() {
            return error == null;
        }

        boolean excedeLaDeuda() {
            return error instanceof CobranzaRechazadaException r && r.motivo() == Motivo.IMPUTACION_EXCEDE_DEUDA;
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
    private enum Punto { ASENTAR_COBRO, ASENTAR_VENTA, VERIFICAR_CUPO }

    private void pausar(Punto punto, Hito llego, Hito suelta, AtomicReference<Long> comprobanteDeLaVenta) {
        CuentaCorrienteService espia = AopTestUtils.getUltimateTargetObject(servicioEspiado);
        org.mockito.stubbing.Answer<Object> pausa = invocacion -> {
            if (Thread.currentThread() == hiloAPausar.get()) {
                if (punto == Punto.ASENTAR_VENTA) {
                    comprobanteDeLaVenta.set(((AsientoVenta) invocacion.getArgument(0)).comprobanteId());
                }
                llego.avisar();
                suelta.esperar();
            }
            return invocacion.callRealMethod();
        };
        switch (punto) {
            case ASENTAR_COBRO -> doAnswer(pausa).when(espia).asentarCobro(any());
            case ASENTAR_VENTA -> doAnswer(pausa).when(espia).asentarVenta(any());
            case VERIFICAR_CUPO -> doAnswer(pausa).when(espia).verificarCupo(any(), any());
        }
    }

    private record Par(Salida primero, Salida segundo) {}

    /**
     * El escenario de casi todo test de orden: el PRIMERO queda retenido en {@code punto} con todo tomado y sin confirmar; el
     * SEGUNDO se lanza y se espera a que esté en LOCK WAIT en MySQL; se suelta al primero y se juntan los dos resultados. El
     * segundo recibe el id del comprobante de la venta del primero (solo con {@link Punto#ASENTAR_VENTA}: ya existe en su
     * transacción aunque no esté confirmado). Exige delta {@code lock_deadlocks} = 0.
     */
    private Par retenidoYBloqueado(Escenario e, Punto punto, Callable<?> primero, Function<Long, Callable<?>> segundo)
            throws Exception {
        return retenidoYBloqueado(e, punto, primero, segundo, barrera -> { });
    }

    /**
     * Igual, y {@code conElSegundoBloqueado} corre con el segundo YA en LOCK WAIT y el primero todavía retenido: el momento para
     * afirmar QUÉ está esperando (S1 del verify: «hay 1 bloqueado» no dice si espera lo que debe).
     */
    private Par retenidoYBloqueado(Escenario e, Punto punto, Callable<?> primero, Function<Long, Callable<?>> segundo,
            java.util.function.Consumer<BarreraDeLocks> conElSegundoBloqueado) throws Exception {
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        Hito llego = new Hito();
        Hito suelta = new Hito();
        AtomicReference<Long> comprobante = new AtomicReference<>();
        pausar(punto, llego, suelta, comprobante);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Salida> t1 = lanzarPausable(pool, e.f(), primero);
            llego.esperar(); // el primero tiene todo tomado, está dentro del punto y no confirmó
            Future<Salida> t2 = lanzar(pool, e.f(), segundo.apply(comprobante.get()));
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
        return retenidoYBloqueado(e, punto, primero, comprobante -> segundo);
    }

    /** Lo más común: el primero es una cobranza retenida en {@code asentarCobro}. */
    private Par retenidoYBloqueado(Escenario e, Callable<?> primero, Callable<?> segundo) throws Exception {
        return retenidoYBloqueado(e, Punto.ASENTAR_COBRO, primero, segundo);
    }

    /** Lo más común con una aserción sobre el momento del bloqueo (ver arriba). */
    private Par retenidoYBloqueado(Escenario e, Callable<?> primero, Callable<?> segundo,
            java.util.function.Consumer<BarreraDeLocks> conElSegundoBloqueado) throws Exception {
        return retenidoYBloqueado(e, Punto.ASENTAR_COBRO, primero, comprobante -> segundo, conElSegundoBloqueado);
    }

    // ===============================================================================================================
    // CCT3-313. CB-1 + CC-048
    // ===============================================================================================================

    @Test
    @DisplayName("CB-1: deuda 100; A cobra 100 (retenida con todo tomado), B cobra 100 (bloqueada) → A ok, B IMPUTACION_EXCEDE_DEUDA con pendiente 0; un recibo, saldo 0 y la numeración de B no se consumió")
    void cb1MismoComprobante() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "100");

        Par par = retenidoYBloqueado(e,
                cobrar(e, pedido(cliente, "100", imputa(a.comprobanteId(), "100"))),
                cobrar(e, pedido(cliente, "100", imputa(a.comprobanteId(), "100"))));

        assertThat(par.primero().cobro().recibo().getNumero()).isEqualTo(1L);
        CobranzaRechazadaException rechazo = par.segundo().rechazo();
        assertThat(rechazo.motivo()).isEqualTo(Motivo.IMPUTACION_EXCEDE_DEUDA);
        assertThat(rechazo.deudas()).singleElement().satisfies(deuda -> {
            assertThat(deuda.comprobanteId()).isEqualTo(a.comprobanteId());
            assertThat(deuda.pendiente()).isEqualByComparingTo("0");
        });
        assertThat(recibos(cliente)).isEqualTo(1);
        assertThat(cobros(cliente)).isEqualTo(1);
        assertThat(imputaciones(cliente)).isEqualTo(1);
        assertThat(imputadoA(a.comprobanteId())).isEqualByComparingTo("100");
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertThat(ultimoNumero(e.f().id())).as("el rechazo de B revirtió el numerador").isEqualTo(1L);
        // el siguiente cobro exitoso toma el número inmediato (2), no el 3
        assertThat(cobranzaService.cobrar(pedido(cliente, "10"), e.f().usuarioId()).recibo().getNumero()).isEqualTo(2L);
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("CC-048 (1): T1 retiene 1.000 de A (pend. 1.000); T2 imputa 500 a A → 409 (no 201) y Σ imputaciones de A = 1.000")
    void cc048SobreImputaElMismoComprobante() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "1000");

        Par par = retenidoYBloqueado(e,
                cobrar(e, pedido(cliente, "1000", imputa(a.comprobanteId(), "1000"))),
                cobrar(e, pedido(cliente, "500", imputa(a.comprobanteId(), "500"))));

        par.primero().cobro();
        assertThat(par.segundo().rechazo().motivo()).isEqualTo(Motivo.IMPUTACION_EXCEDE_DEUDA);
        assertThat(imputadoA(a.comprobanteId())).isEqualByComparingTo("1000");
        assertThat(recibos(cliente)).isEqualTo(1);
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("CC-048 (2): T1 retiene 1.000 de A; T2 imputa 1.000 a B → 201 una sola vez (dos recibos, dos COBRO)")
    void cc048OtroComprobanteEntra() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "1000");
        VentaSuelta b = ventaEnLibro(e.f(), cliente, "1000");

        Par par = retenidoYBloqueado(e,
                cobrar(e, pedido(cliente, "1000", imputa(a.comprobanteId(), "1000"))),
                cobrar(e, pedido(cliente, "1000", imputa(b.comprobanteId(), "1000"))));

        assertThat(par.primero().cobro().repetida()).isFalse();
        assertThat(par.segundo().cobro().repetida()).isFalse();
        assertThat(recibos(cliente)).isEqualTo(2);
        assertThat(cobros(cliente)).isEqualTo(2);
        assertThat(imputadoA(a.comprobanteId())).isEqualByComparingTo("1000");
        assertThat(imputadoA(b.comprobanteId())).isEqualByComparingTo("1000");
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("CC-048 (3): T2 llega con la clave y el contenido de T1 mientras T1 confirma → devuelve el recibo de T1 (repetida), no un segundo recibo")
    void cc048LaMismaClaveDevuelveElReciboDeT1() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "1000");
        String clave = clave();
        CobranzaPedido p = pedido(clave, cliente, MedioPago.EFECTIVO, "400", imputa(a.comprobanteId(), "400"));

        Par par = retenidoYBloqueado(e, cobrar(e, p), cobrar(e, p));

        CobroRegistrado original = par.primero().cobro();
        CobroRegistrado repetida = par.segundo().cobro();
        assertThat(original.repetida()).isFalse();
        assertThat(repetida.repetida()).isTrue();
        assertThat(repetida.recibo().getId()).isEqualTo(original.recibo().getId());
        assertThat(recibos(cliente)).isEqualTo(1);
        assertThat(cobros(cliente)).isEqualTo(1);
        assertThat(imputadoA(a.comprobanteId())).isEqualByComparingTo("400");
        assertThat(saldo(cliente)).isEqualByComparingTo("600");
        assertInvariante(cliente);
    }

    // ===============================================================================================================
    // CCT3-314. CB-2, CB-3 y variantes de CC-044
    // ===============================================================================================================

    @Test
    @DisplayName("CB-2: 8 cobranzas de $ 200 a la vez con la MISMA sugerencia FIFO (200 al primero de 4 comprobantes de $ 250) → exactamente 1 gana y 7 son 409; saldo = deuda − 200")
    void cb2OchoConLaMismaSugerencia() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        List<VentaSuelta> comprobantes = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            comprobantes.add(ventaEnLibro(e.f(), cliente, "250"));
        }
        long primero = comprobantes.get(0).comprobanteId();
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        List<Callable<?>> trabajos = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            trabajos.add(cobrar(e, pedido(cliente, "200", imputa(primero, "200"))));
        }

        List<Salida> salidas = enParalelo(e.f(), trabajos);

        assertThat(salidas.stream().filter(Salida::ok).count()).as("ganadoras").isEqualTo(1);
        assertThat(salidas.stream().filter(Salida::excedeLaDeuda).count()).as("409").isEqualTo(7);
        assertThat(imputadoA(primero)).isEqualByComparingTo("200");
        assertThat(recibos(cliente)).isEqualTo(1);
        assertThat(saldo(cliente)).isEqualByComparingTo("800");
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    @Test
    @DisplayName("CB-3: 8 cobranzas «a cuenta» a la vez (sin imputar) → 8 éxitos, numeración 1..8 sin huecos, un COBRO por recibo y saldo = deuda − Σ")
    void cb3OchoACuenta() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        ventaEnLibro(e.f(), cliente, "1000");
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        List<Callable<?>> trabajos = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            trabajos.add(cobrar(e, pedido(cliente, "100")));
        }

        List<Salida> salidas = enParalelo(e.f(), trabajos);

        List<Long> numeros = salidas.stream().map(s -> s.cobro().recibo().getNumero()).sorted().toList();
        assertThat(numeros).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        assertThat(recibos(cliente)).isEqualTo(8);
        assertThat(cobros(cliente)).isEqualTo(8);
        assertThat(imputaciones(cliente)).isZero();
        assertThat(saldo(cliente)).isEqualByComparingTo("200");
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    @Test
    @DisplayName("600 + 400 sobre un pendiente de 1.000 a la vez → ambas 201, pendiente 0 y saldo 0")
    void seiscientosMasCuatrocientos() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "1000");
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();

        List<Salida> salidas = enParalelo(e.f(), List.of(
                cobrar(e, pedido(cliente, "600", imputa(a.comprobanteId(), "600"))),
                cobrar(e, pedido(cliente, "400", imputa(a.comprobanteId(), "400")))));

        salidas.forEach(Salida::cobro);
        assertThat(imputadoA(a.comprobanteId())).isEqualByComparingTo("1000");
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertThat(recibos(cliente)).isEqualTo(2);
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    @Test
    @DisplayName("orden inverso: dos cobranzas imputan a {A,B} en orden A,B y B,A a la vez → terminan (éxito o 409), nunca una excepción de lock; delta lock_deadlocks 0")
    void ordenInversoDeComprobantes() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "500");
        VentaSuelta b = ventaEnLibro(e.f(), cliente, "500");
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();

        List<Salida> salidas = enParalelo(e.f(), List.of(
                cobrar(e, pedido(cliente, "200", imputa(a.comprobanteId(), "100"), imputa(b.comprobanteId(), "100"))),
                cobrar(e, pedido(cliente, "200", imputa(b.comprobanteId(), "100"), imputa(a.comprobanteId(), "100")))));

        salidas.forEach(s -> assertThat(s.ok() || s.excedeLaDeuda()).as("resultado %s", s.error()).isTrue());
        assertThat(imputadoA(a.comprobanteId())).isEqualByComparingTo("200");
        assertThat(imputadoA(b.comprobanteId())).isEqualByComparingTo("200");
        assertThat(saldo(cliente)).isEqualByComparingTo("600");
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    @Test
    @DisplayName("la misma clave y el mismo contenido a la vez → un recibo y un COBRO; resultados repetida=false y repetida=true con el mismo recibo")
    void laMismaClaveSimultanea() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "500");
        CobranzaPedido p = pedido(clave(), cliente, MedioPago.EFECTIVO, "200", imputa(a.comprobanteId(), "200"));
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();

        List<Salida> salidas = enParalelo(e.f(), List.of(cobrar(e, p), cobrar(e, p)));

        List<CobroRegistrado> cobros = salidas.stream().map(Salida::cobro).toList();
        assertThat(cobros.stream().filter(CobroRegistrado::repetida).count()).isEqualTo(1);
        assertThat(cobros.get(0).recibo().getId()).isEqualTo(cobros.get(1).recibo().getId());
        assertThat(recibos(cliente)).isEqualTo(1);
        assertThat(cobros(cliente)).isEqualTo(1);
        assertThat(saldo(cliente)).isEqualByComparingTo("300");
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    @Test
    @DisplayName("dos adelantos simultáneos a cuenta de 300 + 300 (cliente sin deuda) → saldo −600 y recibos correlativos 1 y 2")
    void dosAdelantos() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), null);
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();

        List<Salida> salidas = enParalelo(e.f(), List.of(cobrar(e, pedido(cliente, "300")), cobrar(e, pedido(cliente, "300"))));

        assertThat(salidas.stream().map(s -> s.cobro().recibo().getNumero()).sorted().toList()).containsExactly(1L, 2L);
        assertThat(saldo(cliente)).isEqualByComparingTo("-600");
        assertThat(cobros(cliente)).isEqualTo(2);
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    // ===============================================================================================================
    // CCT3-315. CB-4: clientes distintos
    // ===============================================================================================================

    @Test
    @DisplayName("CB-4: A (cliente 1) retenida y B (cliente 2) lanzada → B se bloquea SOLO en numerador_recibo, termina ok al soltar A; numeración correlativa y sin deadlock")
    void cb4ClientesDistintosSerializanEnElNumerador() throws Exception {
        Escenario e = escenario();
        long c1 = cliente(e.f(), "5000");
        long c2 = cliente(e.f(), "5000");
        VentaSuelta v1 = ventaEnLibro(e.f(), c1, "100");
        VentaSuelta v2 = ventaEnLibro(e.f(), c2, "100");

        Par par = retenidoYBloqueado(e,
                cobrar(e, pedido(c1, "100", imputa(v1.comprobanteId(), "100"))),
                cobrar(e, pedido(c2, "100", imputa(v2.comprobanteId(), "100"))),
                // S1: lo que espera B es el numerador de recibos (ya pasó caja S y su cliente, que están libres), y nada más
                barrera -> assertThat(barrera.objetosEnEspera()).as("B espera el numerador de recibos").containsExactly("numerador_recibo"));

        assertThat(par.primero().cobro().recibo().getNumero()).isEqualTo(1L);
        assertThat(par.segundo().cobro().recibo().getNumero()).isEqualTo(2L);
        assertThat(saldo(c1)).isEqualByComparingTo("0");
        assertThat(saldo(c2)).isEqualByComparingTo("0");
        assertInvariante(c1);
        assertInvariante(c2);
    }

    @Test
    @DisplayName("CB-4: con un holder JDBC sobre el cliente 1, la cobranza del cliente 2 NO se bloquea en el cliente: completa sola, sin ninguna transacción en LOCK WAIT")
    void cb4ElHolderDelOtroClienteNoLaBloquea() throws Exception {
        Escenario e = escenario();
        long c1 = cliente(e.f(), "5000");
        long c2 = cliente(e.f(), "5000");
        VentaSuelta v2 = ventaEnLibro(e.f(), c2, "100");
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        BarreraDeLocks.HolderJdbc holder = BarreraDeLocks.holderJdbc("select id from cliente where id = " + c1 + " for update");
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<Salida> b = lanzar(pool, e.f(), cobrar(e, pedido(c2, "100", imputa(v2.comprobanteId(), "100"))));

            // completa mientras el holder sigue con el cliente 1 en X: si tomara un lock global, se quedaría esperando acá
            Salida salida = terminaSola(b, "la cobranza del cliente 2 se bloqueó con el holder del cliente 1");

            assertThat(salida.cobro().recibo().getNumero()).isEqualTo(1L);
            assertThat(barrera.bloqueadosAhora()).as("transacciones en LOCK WAIT").isZero();
        } finally {
            holder.rollback();
            pool.shutdownNow();
        }
        assertThat(saldo(c2)).isEqualByComparingTo("0");
        assertInvariante(c1);
        assertInvariante(c2);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    // ===============================================================================================================
    // CCT3-316. CB-5: cobranza × venta a cuenta (ambos órdenes)
    // ===============================================================================================================

    @Test
    @DisplayName("CB-5 (i): venta CC retenida en asentarVenta; la cobranza que imputa a ESA venta queda bloqueada → al soltar ve la deuda confirmada y la imputa (201); saldo = deuda − cobro")
    void cb5VentaPrimeroLaCobranzaVeSuDeuda() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        long articulo = articulo(e.f());

        Par par = retenidoYBloqueado(e, Punto.ASENTAR_VENTA, ventaCC(e, articulo, cliente, 300),
                comprobanteDeLaVenta -> cobrar(e, pedido(cliente, "100", imputa(comprobanteDeLaVenta, "100"))));

        assertThat(par.primero().error()).isNull();
        CobroRegistrado cobro = par.segundo().cobro();
        assertThat(cobro.imputaciones()).singleElement()
                .satisfies(i -> assertThat(i.importe()).isEqualByComparingTo("100"));
        assertThat(saldo(cliente)).isEqualByComparingTo("200");
        assertThat(imputaciones(cliente)).isEqualTo(1);
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("CB-5 (ii): cobranza de 500 retenida; venta CC de 600 bloqueada (límite 1.000, saldo 900) → al soltar la venta ve el saldo reducido y entra (201); saldo 1.000")
    void cb5CobranzaPrimeroLaVentaVeElSaldoReducido() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "1000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "900");
        long articulo = articulo(e.f());

        Par par = retenidoYBloqueado(e, cobrar(e, pedido(cliente, "500", imputa(a.comprobanteId(), "500"))),
                ventaCC(e, articulo, cliente, 600));

        par.primero().cobro();
        assertThat(par.segundo().error()).as("la venta de 600 entra porque el saldo ya bajó a 400").isNull();
        assertThat(saldo(cliente)).isEqualByComparingTo("1000");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("CB-5 (spec, cobranza primero): límite 5.000, saldo 4.000; cobranza 1.000 retenida y venta 1.500 bloqueada → ambas 201, saldo 4.500; la imputación queda en A, no en la venta nueva")
    void cb5SpecCobranzaPrimero() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "4000");
        long articulo = articulo(e.f());

        Par par = retenidoYBloqueado(e, cobrar(e, pedido(cliente, "1000", imputa(a.comprobanteId(), "1000"))),
                ventaCC(e, articulo, cliente, 1500));

        par.primero().cobro();
        assertThat(par.segundo().error()).isNull();
        assertThat(saldo(cliente)).isEqualByComparingTo("4500");
        assertThat(jdbc.queryForList("select comprobante_id from imputacion where cliente_id = ?", Long.class, cliente))
                .containsExactly(a.comprobanteId());
        assertThat(imputadoA(a.comprobanteId())).isEqualByComparingTo("1000");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("CB-5 (spec, venta primero): la venta de 1.500 retenida en la verificación del cupo y la cobranza de 1.000 bloqueada → venta 422 LIMITE, cobranza 201, saldo 3.000")
    void cb5SpecVentaPrimero() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "4000");
        long articulo = articulo(e.f());

        Par par = retenidoYBloqueado(e, Punto.VERIFICAR_CUPO, ventaCC(e, articulo, cliente, 1500),
                cobrar(e, pedido(cliente, "1000", imputa(a.comprobanteId(), "1000"))));

        assertThat(par.primero().error()).isInstanceOfSatisfying(VentaRechazadaException.class,
                v -> assertThat(v.motivo()).isEqualTo(VentaRechazadaException.Motivo.LIMITE_CUENTA_CORRIENTE));
        par.segundo().cobro();
        assertThat(saldo(cliente)).isEqualByComparingTo("3000");
        assertThat(imputadoA(a.comprobanteId())).isEqualByComparingTo("1000");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("CB-5 (clientes distintos): una cobranza que ESPERA a la venta de su cliente no retiene el numerador ni nada: la del otro cliente y su venta completan; solo hay 1 transacción en LOCK WAIT")
    void cb5ClientesDistintosSinInterferencia() throws Exception {
        Escenario e = escenario();
        long c1 = cliente(e.f(), "5000");
        long c2 = cliente(e.f(), "5000");
        VentaSuelta a1 = ventaEnLibro(e.f(), c1, "100");
        VentaSuelta a2 = ventaEnLibro(e.f(), c2, "100");
        long articulo = articulo(e.f());
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        Hito llego = new Hito();
        Hito suelta = new Hito();
        // se retiene en la verificación del cupo: ya tiene la caja (S) y a c1 (X) pero todavía NO el numerador de comprobantes,
        // así la venta de c2 puede pasar (las ventas de una ferretería se serializan en ese numerador, no en el cliente)
        pausar(Punto.VERIFICAR_CUPO, llego, suelta, new AtomicReference<>());
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            Future<Salida> ventaDeC1 = lanzarPausable(pool, e.f(), ventaCC(e, articulo, c1, 50));
            llego.esperar(); // la venta de c1 tiene a c1 en X y no confirmó
            Future<Salida> cobranzaDeC1 = lanzar(pool, e.f(), cobrar(e, pedido(c1, "100", imputa(a1.comprobanteId(), "100"))));
            barrera.esperarBloqueados(1); // la cobranza de c1 espera al cliente c1

            // La de c2 completa SIN esperar nada: la cobranza de c1 espera al cliente ANTES de tomar el numerador de recibos.
            Salida cobranzaDeC2 = terminaSola(
                    lanzar(pool, e.f(), cobrar(e, pedido(c2, "100", imputa(a2.comprobanteId(), "100")))),
                    "la cobranza del cliente 2 quedó esperando a la cobranza del cliente 1 (retiene un lock compartido)");
            assertThat(cobranzaDeC2.cobro().recibo().getNumero()).isEqualTo(1L);
            Salida ventaDeC2 = terminaSola(lanzar(pool, e.f(), ventaCC(e, articulo, c2, 20)),
                    "la venta del cliente 2 quedó esperando");
            assertThat(ventaDeC2.error()).isNull();
            assertThat(barrera.bloqueadosAhora()).as("solo la cobranza de c1 está esperando").isEqualTo(1);

            suelta.avisar();
            assertThat(resultado(ventaDeC1).error()).isNull();
            assertThat(resultado(cobranzaDeC1).cobro().recibo().getNumero()).isEqualTo(2L);
        } finally {
            suelta.avisar();
            pool.shutdownNow();
        }
        assertThat(saldo(c1)).isEqualByComparingTo("50");
        assertThat(saldo(c2)).isEqualByComparingTo("20");
        assertInvariante(c1);
        assertInvariante(c2);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    // ===============================================================================================================
    // CCT3-317. CB-6: cobranza × cierre de caja
    // ===============================================================================================================

    @Test
    @DisplayName("CB-6 (i): cobranza en efectivo retenida (caja S) y cierre bloqueado → al soltar el cierre ve lo cobrado en efectivoEsperado y en cobranzasPorMedio; el recibo queda en esa caja")
    void cb6ElCierreEsperaYSumaLaCobranza() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "1000");

        Par par = retenidoYBloqueado(e,
                cobrar(e, pedido(cliente, "700", imputa(a.comprobanteId(), "700"))),
                () -> cajaService.cerrar(e.caja(), new BigDecimal("700"), e.f().usuarioId()));

        CobroRegistrado cobro = par.primero().cobro();
        assertThat(par.segundo().error()).isNull();
        DetalleCaja cierre = (DetalleCaja) par.segundo().valor();
        assertThat(cierre.resumen().cobranzasPorMedio().get(MedioPago.EFECTIVO)).isEqualByComparingTo("700");
        assertThat(cierre.resumen().cobranzasEnEfectivo()).isEqualByComparingTo("700");
        assertThat(cierre.resumen().efectivoEsperado()).isEqualByComparingTo("700");
        assertThat(cierre.resumen().totalVentas()).as("la cobranza no es una venta").isEqualByComparingTo("0");
        assertThat(cierre.caja().getEfectivoEsperado()).isEqualByComparingTo("700");
        assertThat(cierre.caja().getDiferencia()).isEqualByComparingTo("0");
        assertThat(cobro.recibo().getCajaId()).isEqualTo(e.caja());
        // un recibo existe si y solo si figura en el resumen del cierre
        assertThat(jdbc.queryForObject("select coalesce(sum(total), 0) from recibo where caja_id = ?", BigDecimal.class,
                e.caja())).isEqualByComparingTo(cierre.resumen().totalCobranzas());
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("CB-6 (ii): un holder cierra la caja (X) y no confirmó; la cobranza queda bloqueada → al confirmar es 422 CAJA_CERRADA, sin recibo ni COBRO ni número consumido; saldo intacto")
    void cb6LaCajaSeCierraMientrasLaCobranzaEspera() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "300");
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        // el mismo SQL que el cierre: toma la caja (FOR UPDATE) y la marca cerrada, sin confirmar
        BarreraDeLocks.HolderJdbc holder = BarreraDeLocks.holderJdbc(
                "select id from caja where id = " + e.caja() + " for update",
                "update caja set abierta = null, fecha_cierre = now(6), usuario_cierre_id = " + e.f().usuarioId()
                        + ", efectivo_contado = 0, efectivo_esperado = 0, diferencia = 0 where id = " + e.caja());
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<Salida> cobranza = lanzar(pool, e.f(), cobrar(e, pedido(cliente, "100", imputa(a.comprobanteId(), "100"))));
            barrera.esperarBloqueados(1); // la cobranza espera la caja
            // W2: espera la CAJA y todavía no tiene al cliente (la caja va antes): otra conexión lo toma sin esperar
            assertThat(barrera.objetosEnEspera()).as("la cobranza espera la caja").containsExactly("caja");
            assertThat(BarreraDeLocks.sePuedeTomarYa("cliente", cliente))
                    .as("la cobranza bloqueada en la caja NO retiene al cliente (caja antes que cliente)").isTrue();
            holder.commit();

            assertThat(resultado(cobranza).rechazo().motivo()).isEqualTo(Motivo.CAJA_CERRADA);
        } finally {
            holder.rollback();
            pool.shutdownNow();
        }
        assertThat(recibos(cliente)).isZero();
        assertThat(cobros(cliente)).isZero();
        assertThat(imputaciones(cliente)).isZero();
        assertThat(ultimoNumero(e.f().id())).as("no se consumió ningún número").isNull();
        assertThat(saldo(cliente)).isEqualByComparingTo("300");
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    // ===============================================================================================================
    // CCT3-318. CB-7 + CB-11: cobranza × desactivar y × cambiar límite
    // ===============================================================================================================

    private Callable<?> desactivar(long cliente) {
        return () -> clienteService.cambiarActivo(cliente, false);
    }

    private boolean activo(long cliente) {
        return Boolean.TRUE.equals(jdbc.queryForObject("select activo from cliente where id = ?", Boolean.class, cliente));
    }

    @Test
    @DisplayName("CB-7 (i, con deuda): cobranza de 400 sobre 1.000 retenida y desactivar bloqueado → al soltar desactivar es 422 por saldo ≠ 0 (600) y el cliente sigue activo")
    void cb7DesactivarConDeudaEsperaYFalla() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "1000");

        Par par = retenidoYBloqueado(e, cobrar(e, pedido(cliente, "400", imputa(a.comprobanteId(), "400"))), desactivar(cliente));

        par.primero().cobro();
        assertThat(par.segundo().error()).isInstanceOf(ReglaDeNegocioException.class)
                .hasMessage("No se puede desactivar: el cliente tiene saldo en cuenta corriente.");
        assertThat(saldo(cliente)).isEqualByComparingTo("600");
        assertThat(activo(cliente)).isTrue();
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("CB-7 (i, a favor): adelanto de 500 a un cliente con saldo 0 retenido y desactivar bloqueado → desactivar es 422 (saldo −500, a favor) y el cliente sigue activo")
    void cb7AdelantoYDesactivar() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), null);

        Par par = retenidoYBloqueado(e, cobrar(e, pedido(cliente, "500")), desactivar(cliente));

        par.primero().cobro();
        assertThat(par.segundo().error()).isInstanceOf(ReglaDeNegocioException.class)
                .hasMessage("No se puede desactivar: el cliente tiene saldo en cuenta corriente.");
        assertThat(saldo(cliente)).isEqualByComparingTo("-500");
        assertThat(activo(cliente)).as("nunca un cliente inactivo con saldo ≠ 0").isTrue();
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("CB-7 (cobranza 1.000 sobre saldo 1.000, cobranza primero): ambas terminan bien; el cliente queda inactivo con saldo 0")
    void cb7CobranzaTotalPrimeroYDesactivarEntra() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "1000");

        Par par = retenidoYBloqueado(e, cobrar(e, pedido(cliente, "1000", imputa(a.comprobanteId(), "1000"))), desactivar(cliente));

        par.primero().cobro();
        assertThat(par.segundo().error()).isNull();
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertThat(activo(cliente)).isFalse();
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("CB-7 (desactivar primero, con saldo 1.000): desactivar es 422 por saldo y la cobranza de 1.000 entra (201); el cliente sigue activo y el saldo queda en 0")
    void cb7DesactivarPrimeroConSaldoYDespuesLaCobranza() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "1000");

        // Con saldo ≠ 0 desactivar rechaza apenas toma al cliente: ese orden se fuerza ejecutándolo antes (no hay nada que retener).
        assertThatThrownBy(() -> clienteService.cambiarActivo(cliente, false)).isInstanceOf(ReglaDeNegocioException.class)
                .hasMessage("No se puede desactivar: el cliente tiene saldo en cuenta corriente.");
        cobranzaService.cobrar(pedido(cliente, "1000", imputa(a.comprobanteId(), "1000")), e.f().usuarioId());

        assertThat(activo(cliente)).isTrue();
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("CB-7 (ii): un holder desactivó al cliente (saldo 0) y no confirmó; la cobranza queda bloqueada → al confirmar es 422 «desactivado», sin recibo ni número consumido")
    void cb7ElHolderDesactivaYLaCobranzaFalla() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), null);
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        // el mismo SQL que cambiarActivo: toma al cliente (FOR UPDATE) y lo desactiva, sin confirmar
        BarreraDeLocks.HolderJdbc holder = BarreraDeLocks.holderJdbc("select id from cliente where id = " + cliente + " for update",
                "update cliente set activo = 0 where id = " + cliente);
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            Future<Salida> cobranza = lanzar(pool, e.f(), cobrar(e, pedido(cliente, "500")));
            barrera.esperarBloqueados(1);
            // W2: espera al CLIENTE y ya tiene la caja en S (la caja va antes): otra conexión no la puede tomar en X
            assertThat(barrera.objetosEnEspera()).as("la cobranza espera al cliente").containsExactly("cliente");
            assertThat(BarreraDeLocks.sePuedeTomarYa("caja", e.caja()))
                    .as("la cobranza bloqueada en el cliente YA tiene la caja en S (caja antes que cliente)").isFalse();
            holder.commit();

            assertThat(resultado(cobranza).error()).isInstanceOf(ReglaDeNegocioException.class)
                    .hasMessage("El cliente está desactivado.");
        } finally {
            holder.rollback();
            pool.shutdownNow();
        }
        assertThat(recibos(cliente)).isZero();
        assertThat(ultimoNumero(e.f().id())).isNull();
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertThat(activo(cliente)).isFalse();
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    @Test
    @DisplayName("CB-11: cobranza retenida y cambiarLimite bloqueado (mismo lock del cliente) → serializados: el límite cambia, el cobro no se altera y no hay deadlock")
    void cb11CambiarLimiteSeSerializaConLaCobranza() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "1000");

        Par par = retenidoYBloqueado(e, cobrar(e, pedido(cliente, "400", imputa(a.comprobanteId(), "400"))),
                () -> servicioEspiado.cambiarLimite(cliente, new BigDecimal("800")));

        par.primero().cobro();
        assertThat(par.segundo().error()).isNull();
        assertThat(jdbc.queryForObject("select limite_cuenta_corriente from cliente where id = ?", BigDecimal.class, cliente))
                .isEqualByComparingTo("800");
        assertThat(saldo(cliente)).isEqualByComparingTo("600");
        assertThat(imputadoA(a.comprobanteId())).isEqualByComparingTo("400");
        assertInvariante(cliente);
    }

    // ===============================================================================================================
    // CCT3-319. CB-9, CB-10, CB-12 y CC-049
    // ===============================================================================================================

    @Test
    @DisplayName("CC-049: 8 cobranzas de $ 100 a la vez (6 a A y 2 a B, cada comprobante de 500) → entran 7 (5 a A, 2 a B) y el saldo final = 1.000 − Σ(éxitos × 100)")
    void cc049OchoDeCien() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "500");
        VentaSuelta b = ventaEnLibro(e.f(), cliente, "500");
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        List<Callable<?>> trabajos = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            trabajos.add(cobrar(e, pedido(cliente, "100", imputa(a.comprobanteId(), "100"))));
        }
        for (int i = 0; i < 2; i++) {
            trabajos.add(cobrar(e, pedido(cliente, "100", imputa(b.comprobanteId(), "100"))));
        }

        List<Salida> salidas = enParalelo(e.f(), trabajos);

        long exitos = salidas.stream().filter(Salida::ok).count();
        salidas.stream().filter(s -> !s.ok()).forEach(s -> assertThat(s.excedeLaDeuda()).as("fallo %s", s.error()).isTrue());
        assertThat(exitos).isEqualTo(7);
        assertThat(saldo(cliente)).isEqualByComparingTo(new BigDecimal("1000").subtract(new BigDecimal(exitos * 100)));
        assertThat(imputadoA(a.comprobanteId())).isEqualByComparingTo("500");
        assertThat(imputadoA(b.comprobanteId())).isEqualByComparingTo("200");
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    @Test
    @DisplayName("CB-9: 6 cobranzas cruzadas (imputaciones en orden distinto, 3 clientes) mezcladas con 3 ventas CC, todas a la vez → todo 201, sin error de lock filtrado y delta lock_deadlocks 0")
    void cb9SinDeadlocks() throws Exception {
        Escenario e = escenario();
        long articulo = articulo(e.f());
        List<Long> clientes = new ArrayList<>();
        List<VentaSuelta> as = new ArrayList<>();
        List<VentaSuelta> bs = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            long c = cliente(e.f(), "10000");
            clientes.add(c);
            as.add(ventaEnLibro(e.f(), c, "500"));
            bs.add(ventaEnLibro(e.f(), c, "500"));
        }
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        List<Callable<?>> trabajos = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            long a = as.get(i).comprobanteId();
            long b = bs.get(i).comprobanteId();
            long c = clientes.get(i);
            trabajos.add(cobrar(e, pedido(c, "200", imputa(a, "100"), imputa(b, "100"))));
            trabajos.add(cobrar(e, pedido(c, "200", imputa(b, "100"), imputa(a, "100"))));
            trabajos.add(ventaCC(e, articulo, c, 10));
        }

        List<Salida> salidas = enParalelo(e.f(), trabajos);

        salidas.forEach(s -> assertThat(s.error()).as("ninguna operación falla").isNull());
        List<Long> numeros = new ArrayList<>();
        salidas.stream().filter(s -> s.valor() instanceof CobroRegistrado)
                .forEach(s -> numeros.add(((CobroRegistrado) s.valor()).recibo().getNumero()));
        assertThat(numeros).hasSize(6).doesNotHaveDuplicates();
        for (int i = 0; i < 3; i++) {
            long c = clientes.get(i);
            assertThat(saldo(c)).isEqualByComparingTo("610"); // 1.000 − 2 × 200 + 10
            assertThat(imputadoA(as.get(i).comprobanteId())).isEqualByComparingTo("200");
            assertThat(imputadoA(bs.get(i).comprobanteId())).isEqualByComparingTo("200");
            assertInvariante(c);
        }
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    @Test
    @DisplayName("CB-10: si asentarCobro falla (antes de escribir, o DESPUÉS de haber escrito todo) la cobranza no deja nada: sin recibo, imputaciones, COBRO, saldo movido ni número consumido")
    void cb10Atomicidad() {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "1000");
        CuentaCorrienteService espia = AopTestUtils.getUltimateTargetObject(servicioEspiado);

        // (a) falla antes de asentar: el recibo y el numerador ya estaban escritos en la transacción
        doThrow(new IllegalStateException("falla simulada antes de asentar")).when(espia).asentarCobro(any());
        assertThatThrownBy(() -> cobranzaService.cobrar(pedido(cliente, "400", imputa(a.comprobanteId(), "400")), e.f().usuarioId()))
                .isInstanceOf(IllegalStateException.class).hasMessage("falla simulada antes de asentar");
        sinRastros(e, cliente);

        // (b) falla DESPUÉS de que el asiento real escribió imputaciones, libro y saldo: todo se revierte junto
        Mockito.reset(espia);
        AtomicBoolean escribio = new AtomicBoolean();
        doAnswer(invocacion -> {
            invocacion.callRealMethod();
            escribio.set(true);
            throw new IllegalStateException("falla simulada tras asentar");
        }).when(espia).asentarCobro(any());
        assertThatThrownBy(() -> cobranzaService.cobrar(pedido(cliente, "400", imputa(a.comprobanteId(), "400")), e.f().usuarioId()))
                .isInstanceOf(IllegalStateException.class).hasMessage("falla simulada tras asentar");
        assertThat(escribio).as("el asiento real llegó a escribir").isTrue();
        sinRastros(e, cliente);

        // y el siguiente cobro exitoso toma el número 1 y funciona
        Mockito.reset(espia);
        assertThat(cobranzaService.cobrar(pedido(cliente, "400", imputa(a.comprobanteId(), "400")), e.f().usuarioId())
                .recibo().getNumero()).isEqualTo(1L);
        assertThat(saldo(cliente)).isEqualByComparingTo("600");
        assertInvariante(cliente);
    }

    private void sinRastros(Escenario e, long cliente) {
        assertThat(recibos(cliente)).as("recibos").isZero();
        assertThat(imputaciones(cliente)).as("imputaciones").isZero();
        assertThat(cobros(cliente)).as("COBRO en el libro").isZero();
        assertThat(saldo(cliente)).as("saldo").isEqualByComparingTo("1000");
        assertThat(ultimoNumero(e.f().id())).as("número consumido").isNull();
        assertInvariante(cliente);
    }

    @Test
    @DisplayName("CB-12: con una cobranza retenida, los pendientes (otra transacción) responden SIN bloquearse con el estado ANTERIOR consistente; después ven el nuevo")
    void cb12PendientesNoBloqueanYSonConsistentes() throws Exception {
        Escenario e = escenario();
        long cliente = cliente(e.f(), "5000");
        VentaSuelta a = ventaEnLibro(e.f(), cliente, "1000");
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        Hito llego = new Hito();
        Hito suelta = new Hito();
        pausar(Punto.ASENTAR_COBRO, llego, suelta, new AtomicReference<>());
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Salida> cobro = lanzarPausable(pool, e.f(), cobrar(e, pedido(cliente, "400", imputa(a.comprobanteId(), "400"))));
            llego.esperar(); // la cobranza tiene al cliente en X, ya escribió y no confirmó

            Salida consulta = terminaSola(lanzar(pool, e.f(), () -> servicioEspiado.pendientes(cliente)),
                    "los pendientes se bloquearon esperando a la cobranza en vuelo");

            assertThat(barrera.bloqueadosAhora()).as("la consulta no espera a nadie").isZero();
            assertThat(consulta.error()).as("los pendientes responden").isNull();
            PendientesCuenta antes = (PendientesCuenta) consulta.valor();
            assertThat(antes.saldo()).isEqualByComparingTo("1000");
            assertThat(antes.aCuenta()).isEqualByComparingTo("0");
            assertThat(antes.comprobantes()).singleElement().satisfies(p -> {
                assertThat(p.imputado()).isEqualByComparingTo("0");
                assertThat(p.pendiente()).isEqualByComparingTo("1000");
            });
            suelta.avisar();
            resultado(cobro).cobro();
        } finally {
            suelta.avisar();
            pool.shutdownNow();
        }
        PendientesCuenta despues = servicioEspiado.pendientes(cliente);
        assertThat(despues.saldo()).isEqualByComparingTo("600");
        assertThat(despues.comprobantes()).singleElement()
                .satisfies(p -> assertThat(p.pendiente()).isEqualByComparingTo("600"));
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }
}
