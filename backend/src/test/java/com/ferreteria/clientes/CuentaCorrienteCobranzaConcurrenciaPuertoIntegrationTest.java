package com.ferreteria.clientes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.AsientoCobro;
import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionInvalidaException;
import com.ferreteria.clientes.service.CuentaCorrienteService;
import com.ferreteria.support.BarreraDeLocks;
import com.ferreteria.support.BarreraDeLocks.Hito;
import com.ferreteria.support.BarreraDeLocks.Largada;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
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
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * CCT3-211 (CC-044, CC-049): la concurrencia de la cobranza a nivel PUERTO, con hilos de verdad contra MySQL real y SIN
 * tiempos: cada hilo hace lo que hará RegistroCobranza (tomar al cliente → recibo → {@code asentarCobro}) y la sincronización
 * es la condición observable «el hilo B está en LOCK WAIT» de {@link BarreraDeLocks}, más hitos con timeout y largada común.
 * La pausa del hilo A «con todo tomado y sin confirmar» es un {@code @SpyBean} de {@code CuentaCorrienteService} sobre
 * {@code asentarCobro}. Todo escenario exige delta {@code lock_deadlocks} = 0 e I1 + I2 al final. CUIT 30-8402NNNN.
 */
class CuentaCorrienteCobranzaConcurrenciaPuertoIntegrationTest extends CuentaCorrienteTestBase {

    @SpyBean
    CuentaCorrienteService servicioEspiado;

    /** El hilo que se pausa dentro de {@code asentarCobro} (los demás pasan de largo). */
    private final AtomicReference<Thread> hiloAPausar = new AtomicReference<>();

    @Override
    protected String prefijoDeCuit() {
        return "30-8402";
    }

    @AfterEach
    void soltarElEspia() {
        Mockito.reset((CuentaCorrienteService) AopTestUtils.getUltimateTargetObject(servicioEspiado));
        hiloAPausar.set(null);
    }

    /** Hace que el hilo registrado en {@link #hiloAPausar} avise {@code llego} y espere {@code suelta} antes de asentar. */
    private void pausarAlHiloA(Hito llego, Hito suelta) {
        CuentaCorrienteService espia = AopTestUtils.getUltimateTargetObject(servicioEspiado);
        doAnswer(invocacion -> {
            if (Thread.currentThread() == hiloAPausar.get()) {
                llego.avisar();
                suelta.esperar();
            }
            return invocacion.callRealMethod();
        }).when(espia).asentarCobro(any());
    }

    private <T> Future<T> lanzar(ExecutorService pool, Ferreteria f, Callable<T> trabajo) {
        return pool.submit(() -> {
            TenantContext.setCurrentFerreteriaId(f.id());
            try {
                return trabajo.call();
            } finally {
                TenantContext.clear();
            }
        });
    }

    private static <T> T resultado(Future<T> futuro) throws Exception {
        try {
            return futuro.get(60, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof Exception causa) {
                throw causa;
            }
            throw e;
        }
    }

    // ---- 1. el segundo cobro del mismo comprobante espera el commit del primero y ve el pendiente nuevo ----

    @Test
    @DisplayName("(1) dos cobros del mismo cliente y comprobante (pendiente 100): A pausada con todo tomado, B bloqueada → A ok, B EXCEDE_DEUDA con pendiente 0; una imputación; saldo = deuda − 100")
    void mismoClienteMismoComprobante() throws Exception {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, cliente, "100.00");
        ventaEnLibro(f, cliente, "300.00"); // otra deuda: el saldo no es solo el de A
        long caja = cajaCerrada(f);
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        Hito llego = new Hito();
        Hito suelta = new Hito();
        pausarAlHiloA(llego, suelta);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> cobroA = lanzar(pool, f, () -> {
                hiloAPausar.set(Thread.currentThread());
                return cobrar(f, cliente, caja, "100.00", imputa(a.comprobanteId(), "100.00"));
            });
            llego.esperar(); // A tiene el cliente, el recibo y está dentro de asentarCobro, sin confirmar
            Future<?> cobroB = lanzar(pool, f, () -> cobrar(f, cliente, caja, "100.00", imputa(a.comprobanteId(), "100.00")));
            barrera.esperarBloqueados(1); // B está en LOCK WAIT sobre la fila del cliente
            suelta.avisar();

            resultado(cobroA);
            assertThatThrownBy(() -> resultado(cobroB)).isInstanceOfSatisfying(ImputacionInvalidaException.class, e -> {
                assertThat(e.deudas()).singleElement().satisfies(deuda -> {
                    assertThat(deuda.comprobanteId()).isEqualTo(a.comprobanteId());
                    assertThat(deuda.pendiente()).isEqualByComparingTo("0");
                });
            });
        } finally {
            suelta.avisar();
            pool.shutdownNow();
        }

        assertThat(imputaciones(cliente)).isEqualTo(1);
        assertThat(imputadoA(a.comprobanteId())).isEqualByComparingTo("100.00");
        assertThat(recibos(cliente)).as("el recibo de B se revirtió con su rechazo").isEqualTo(1);
        assertThat(cobros(cliente)).isEqualTo(1);
        assertThat(saldo(cliente)).isEqualByComparingTo("300.00");
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    // ---- 2. el llamador olvidó el lock: el escritor se defiende solo ----

    @Test
    @DisplayName("(2) el llamador NO toma al cliente: asentarCobro re-toma la fila (bloquea) y da el mismo resultado que (1)")
    void elLlamadorOlvidoElLock() throws Exception {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, cliente, "100.00");
        long caja = cajaCerrada(f);
        // El recibo de B se emite ANTES (en su propia tx confirmada): así B no toca al cliente (la FK del recibo lo haría) y
        // lo primero que hace asentarCobro es re-tomar la cuenta.
        TenantContext.setCurrentFerreteriaId(f.id());
        ReciboEmitido reciboDeB = new TransactionTemplate(transactionManager).execute(s -> emitirRecibo(f, cliente, caja, "100.00"));
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        Hito llego = new Hito();
        Hito suelta = new Hito();
        pausarAlHiloA(llego, suelta);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> cobroA = lanzar(pool, f, () -> {
                hiloAPausar.set(Thread.currentThread());
                return cobrar(f, cliente, caja, "100.00", imputa(a.comprobanteId(), "100.00"));
            });
            llego.esperar();
            Future<?> cobroB = lanzar(pool, f, () -> new TransactionTemplate(transactionManager).execute(status ->
                    // sin tomarParaVenta: lo primero que hace asentarCobro es re-tomar la cuenta
                    cobranza.asentarCobro(new AsientoCobro(cliente, reciboDeB.id(), reciboDeB.numero(), new BigDecimal("100.00"),
                            List.of(imputa(a.comprobanteId(), "100.00")), f.usuarioId()))));
            barrera.esperarBloqueados(1);
            suelta.avisar();

            resultado(cobroA);
            assertThatThrownBy(() -> resultado(cobroB)).isInstanceOfSatisfying(ImputacionInvalidaException.class,
                    e -> assertThat(e.deudas().get(0).pendiente()).isEqualByComparingTo("0"));
        } finally {
            suelta.avisar();
            pool.shutdownNow();
        }

        jdbc.update("delete from recibo where id = ?", reciboDeB.id()); // el recibo suelto de B nunca tuvo su COBRO: se descarta
        assertThat(imputaciones(cliente)).isEqualTo(1);
        assertThat(imputadoA(a.comprobanteId())).isEqualByComparingTo("100.00");
        assertThat(saldo(cliente)).isEqualByComparingTo("0");
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    // ---- 3. orden estable de partidas: A,B contra B,A ----

    @Test
    @DisplayName("(3) dos cobros del mismo cliente imputan a {A,B} en orden A,B y B,A: los dos terminan sin excepción de lock y delta lock_deadlocks = 0")
    void ordenEstableDePartidas() throws Exception {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, cliente, "500.00");
        VentaSuelta b = ventaEnLibro(f, cliente, "500.00");
        long caja = cajaCerrada(f);
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        Largada largada = new Largada(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> ab = lanzar(pool, f, () -> {
                largada.listoYEsperar();
                return cobrar(f, cliente, caja, "200.00", imputa(a.comprobanteId(), "100.00"), imputa(b.comprobanteId(), "100.00"));
            });
            Future<?> ba = lanzar(pool, f, () -> {
                largada.listoYEsperar();
                return cobrar(f, cliente, caja, "200.00", imputa(b.comprobanteId(), "100.00"), imputa(a.comprobanteId(), "100.00"));
            });
            largada.esperarListos(Duration.ofSeconds(30));
            largada.largar();

            resultado(ab);
            resultado(ba);
        } finally {
            pool.shutdownNow();
        }

        assertThat(imputadoA(a.comprobanteId())).isEqualByComparingTo("200.00");
        assertThat(imputadoA(b.comprobanteId())).isEqualByComparingTo("200.00");
        assertThat(cobros(cliente)).isEqualTo(2);
        assertThat(saldo(cliente)).isEqualByComparingTo("600.00");
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    // ---- 4. clientes distintos: no se bloquean entre sí ----

    @Test
    @DisplayName("(4) clientes distintos: con el cobro del cliente 1 pausado con todo tomado, el del cliente 2 completa sin bloquearse")
    void clientesDistintosNoSeBloquean() throws Exception {
        Ferreteria f = ferreteria();
        long uno = cliente(f, "5000.00");
        long dos = cliente(f, "5000.00");
        VentaSuelta deUno = ventaEnLibro(f, uno, "100.00");
        VentaSuelta deDos = ventaEnLibro(f, dos, "100.00");
        long caja = cajaCerrada(f);
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        Hito llego = new Hito();
        Hito suelta = new Hito();
        pausarAlHiloA(llego, suelta);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> cobroUno = lanzar(pool, f, () -> {
                hiloAPausar.set(Thread.currentThread());
                return cobrar(f, uno, caja, "100.00", imputa(deUno.comprobanteId(), "100.00"));
            });
            llego.esperar();

            Future<?> cobroDos = lanzar(pool, f, () -> cobrar(f, dos, caja, "100.00", imputa(deDos.comprobanteId(), "100.00")));
            // termina entero mientras el 1 sigue con todo tomado: si se bloqueara, este tope corto lo delata
            assertThat(cobroDos.get(10, TimeUnit.SECONDS)).as("el cobro 2 completó sin esperar al 1").isNotNull();
            assertThat(cobroUno.isDone()).as("el cobro 1 sigue sosteniendo su cliente").isFalse();
            assertThat(barrera.bloqueadosAhora()).as("nadie quedó en LOCK WAIT").isZero();
            suelta.avisar();
            resultado(cobroUno);
        } finally {
            suelta.avisar();
            pool.shutdownNow();
        }

        assertThat(saldo(uno)).isEqualByComparingTo("0");
        assertThat(saldo(dos)).isEqualByComparingTo("0");
        assertInvariante(uno);
        assertInvariante(dos);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }

    // ---- 5. N hilos sobre el mismo comprobante ----

    @Test
    @DisplayName("(5) 8 hilos cobran $100 a la vez al mismo comprobante (pendiente 500): exactamente 5 entran y 3 son EXCEDE_DEUDA; Σ imputado ≤ pendiente")
    void ochoHilosSobreElMismoComprobante() throws Exception {
        Ferreteria f = ferreteria();
        long cliente = cliente(f, "5000.00");
        VentaSuelta a = ventaEnLibro(f, cliente, "500.00");
        VentaSuelta b = ventaEnLibro(f, cliente, "500.00"); // el saldo es 1.000 con dos comprobantes
        long caja = cajaCerrada(f);
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        int hilos = 8;
        Largada largada = new Largada(hilos);
        ExecutorService pool = Executors.newFixedThreadPool(hilos);
        List<Future<Boolean>> futuros = new ArrayList<>();
        try {
            for (int i = 0; i < hilos; i++) {
                futuros.add(lanzar(pool, f, () -> {
                    largada.listoYEsperar();
                    try {
                        cobrar(f, cliente, caja, "100.00", imputa(a.comprobanteId(), "100.00"));
                        return true;
                    } catch (ImputacionInvalidaException e) {
                        return false;
                    }
                }));
            }
            largada.esperarListos(Duration.ofSeconds(30));
            largada.largar();
            int entraron = 0;
            for (Future<Boolean> futuro : futuros) {
                if (resultado(futuro)) {
                    entraron++;
                }
            }

            assertThat(entraron).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }

        assertThat(imputadoA(a.comprobanteId())).isEqualByComparingTo("500.00");
        assertThat(imputadoA(b.comprobanteId())).isEqualByComparingTo("0");
        assertThat(cobros(cliente)).isEqualTo(5);
        assertThat(recibos(cliente)).isEqualTo(5);
        assertThat(saldo(cliente)).isEqualByComparingTo("500.00");
        assertInvariante(cliente);
        barrera.assertSinDeadlocks(deadlocksAntes);
    }
}
