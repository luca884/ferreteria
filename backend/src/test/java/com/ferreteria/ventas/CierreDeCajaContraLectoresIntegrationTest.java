package com.ferreteria.ventas;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import com.ferreteria.clientes.CuentaCorrienteTestBase;
import com.ferreteria.support.BarreraDeLocks;
import com.ferreteria.support.BarreraDeLocks.Hito;
import com.ferreteria.tenant.TenantContext;
import com.ferreteria.ventas.model.MedioPago;
import com.ferreteria.ventas.repository.MovimientoCajaRepository;
import com.ferreteria.ventas.service.AnulacionService;
import com.ferreteria.ventas.service.CajaService;
import com.ferreteria.ventas.service.CobranzaPedido;
import com.ferreteria.ventas.service.CobranzaRechazadaException;
import com.ferreteria.ventas.service.CobranzaService;
import com.ferreteria.ventas.service.ConfirmarVenta;
import com.ferreteria.ventas.service.ConfirmarVenta.Renglon;
import com.ferreteria.ventas.service.DetalleCaja;
import com.ferreteria.ventas.service.VentaRechazadaException;
import com.ferreteria.ventas.service.VentaService;
import java.math.BigDecimal;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.SpyBean;

/**
 * CC-092 (W1 del verify de C4): regresión de COMPORTAMIENTO, determinista y contra MySQL real, del deadlock entre el cierre de caja
 * y quienes toman la caja compartida (vender, cobrar, anular). El arreglo de producción es que el cierre toma la caja abierta por
 * {@code uk_caja_abierta} ANTES que por la clave primaria ({@code CajaRepository.abiertaPorIdExclusiva} / {@code paraCerrarScoped}),
 * el mismo camino de índice que los lectores compartidos. Con el cierre por la PK (X) y un lector que ya tiene el registro de
 * {@code uk_caja_abierta} en S esperando la PK, el UPDATE {@code abierta = NULL} del cierre pide X sobre ese registro: ciclo.
 *
 * <p>El escenario no depende de tiempos: se PAUSA el cierre REAL justo después de tomar la caja (y de leer el resumen, ya con todo
 * bloqueado y sin confirmar), con un {@code @SpyBean} sobre {@link MovimientoCajaRepository#totalesScoped} (el último dato que
 * lee el resumen; no cambia ningún comportamiento de producción), se lanzan los lectores y se espera a que estén en LOCK WAIT
 * sobre la caja (data_lock_waits); recién entonces se suelta el cierre. Esperado: el cierre confirma, cada lector recibe 422
 * {@code CAJA_CERRADA} (ve la caja cerrada, no una caja a medias) y el delta de {@code lock_deadlocks} es 0. Revertir el arreglo
 * (cierre otra vez por la PK) hace fallar estos tests SIEMPRE: el ciclo se forma sin carrera porque todos los lectores ya están
 * esperando antes de soltar al cierre. CUIT 30-8509NNNN-1.
 */
class CierreDeCajaContraLectoresIntegrationTest extends CuentaCorrienteTestBase {

    private static final String MOTIVO = "Cobro mal cargado";

    @Autowired
    CajaService cajaService;

    @Autowired
    VentaService ventaService;

    @Autowired
    CobranzaService cobranzaService;

    @Autowired
    AnulacionService anulacionService;

    /** Pausa al hilo del cierre dentro de su transacción, ya con la caja tomada en X. */
    @SpyBean
    MovimientoCajaRepository movimientosEspiados;

    private final AtomicReference<Thread> hiloDelCierre = new AtomicReference<>();

    @Override
    protected String prefijoDeCuit() {
        return "30-8509";
    }

    @AfterEach
    void soltarElEspia() {
        Mockito.reset(movimientosEspiados);
        hiloDelCierre.set(null);
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Armado
    // ---------------------------------------------------------------------------------------------------------------

    /** Ferretería con caja abierta hoy, un cliente con deuda, un recibo para anular y un artículo para vender. */
    private record Escenario(Ferreteria f, long caja, long cliente, long recibo, long articulo) {}

    private Escenario escenario() {
        Ferreteria f = ferreteria();
        long caja = cajaAbiertaHoy(f.id());
        TenantContext.setCurrentFerreteriaId(f.id());
        long cliente = cliente(f, "100000");
        VentaSuelta deuda = ventaEnLibro(f, cliente, "1000");
        long recibo = cobranzaService.cobrar(new CobranzaPedido(UUID.randomUUID().toString(), cliente, MedioPago.EFECTIVO,
                new BigDecimal("400"), List.of(imputa(deuda.comprobanteId(), "400"))), f.usuarioId()).recibo().getId();
        String codigo = "C-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into articulo (ferreteria_id, codigo_interno, descripcion, costo, precio_final, alicuota_iva, unidad, "
                + "stock_actual, stock_minimo, activo) values (?, ?, 'Tornillo', 1.00, 1.00, 'IVA_21', 'UNIDAD', 10000000, 0, 1)",
                f.id(), codigo);
        long articulo = jdbc.queryForObject("select id from articulo where ferreteria_id = ? and codigo_interno = ?", Long.class,
                f.id(), codigo);
        return new Escenario(f, caja, cliente, recibo, articulo);
    }

    private Callable<?> venta(Escenario e) {
        long forma = jdbc.queryForObject("select id from forma_pago where ferreteria_id = ? and medio = 'CUENTA_CORRIENTE'",
                Long.class, e.f().id());
        ConfirmarVenta venta = new ConfirmarVenta(UUID.randomUUID().toString(),
                List.of(new Renglon(e.articulo(), new BigDecimal("50"), new BigDecimal("1.00"))), new BigDecimal("50"), forma,
                BigDecimal.ZERO, List.of(), null, null, true, e.cliente(), false);
        return () -> ventaService.cobrar(venta, e.f().usuarioId(), false);
    }

    private Callable<?> cobranza(Escenario e) {
        CobranzaPedido pedido = new CobranzaPedido(UUID.randomUUID().toString(), e.cliente(), MedioPago.EFECTIVO,
                new BigDecimal("100"), List.of());
        return () -> cobranzaService.cobrar(pedido, e.f().usuarioId());
    }

    private Callable<?> anulacion(Escenario e) {
        return () -> anulacionService.anular(e.recibo(), MOTIVO, e.f().usuarioId());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Hilos
    // ---------------------------------------------------------------------------------------------------------------

    private record Salida(Object valor, Throwable error) {}

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

    private static Salida resultado(Future<Salida> futuro) throws Exception {
        return futuro.get(60, TimeUnit.SECONDS);
    }

    /**
     * Pausa al hilo del cierre dentro de {@code MovimientoCajaRepository.totalesScoped}: la caja ya está tomada con
     * {@code FOR UPDATE} (la toma {@code paraCerrarScoped} antes de calcular el resumen) y nada se confirmó. Después de la pausa
     * sigue con el método real (un {@code default} de la interfaz, que consulta al repositorio real).
     */
    private void pausarElCierre(Hito llego, Hito suelta) {
        doAnswer(invocacion -> {
            if (Thread.currentThread() == hiloDelCierre.get()) {
                llego.avisar();
                suelta.esperar();
            }
            return invocacion.callRealMethod();
        }).when(movimientosEspiados).totalesScoped(any());
    }

    /**
     * El cierre REAL queda pausado con la caja tomada; se lanzan los lectores y se espera a que TODOS estén en LOCK WAIT sobre la
     * caja; se suelta al cierre. Verifica: cero deadlocks, cierre OK, caja cerrada y cada lector con 422 CAJA_CERRADA y su mensaje.
     */
    private void cierreContraLectores(Escenario e, List<Callable<?>> lectores, List<String> mensajes) throws Exception {
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocksAntes = barrera.deadlocksAntes();
        Hito llego = new Hito();
        Hito suelta = new Hito();
        pausarElCierre(llego, suelta);
        ExecutorService pool = Executors.newFixedThreadPool(1 + lectores.size());
        Salida cierre;
        List<Salida> salidas = new ArrayList<>();
        try {
            Future<Salida> futuroCierre = lanzar(pool, e.f(), () -> {
                hiloDelCierre.set(Thread.currentThread());
                return cajaService.cerrar(e.caja(), new BigDecimal("0"), e.f().usuarioId());
            });
            llego.esperar(); // el cierre tiene la caja en X (por uk_caja_abierta y la PK), calculó el resumen y no confirmó
            List<Future<Salida>> futuros = new ArrayList<>();
            for (Callable<?> lector : lectores) {
                futuros.add(lanzar(pool, e.f(), lector));
            }
            barrera.esperarBloqueados(lectores.size()); // todos los lectores esperan en MySQL
            assertThat(barrera.objetosEnEspera()).as("los lectores esperan la CAJA (S contra la X del cierre)").containsExactly("caja");
            suelta.avisar();
            cierre = resultado(futuroCierre);
            for (Future<Salida> futuro : futuros) {
                salidas.add(resultado(futuro));
            }
        } finally {
            suelta.avisar();
            pool.shutdownNow();
        }

        barrera.assertSinDeadlocks(deadlocksAntes);
        assertThat(cierre.error()).as("el cierre tiene que confirmar y fue %s", cierre.error()).isNull();
        assertThat(((DetalleCaja) cierre.valor()).caja().estaAbierta()).isFalse();
        assertThat(jdbc.queryForObject("select count(*) from caja where id = ? and abierta is null", Long.class, e.caja()))
                .as("la caja quedó cerrada").isEqualTo(1);
        for (int i = 0; i < salidas.size(); i++) {
            Throwable error = salidas.get(i).error();
            assertThat(error).as("lector %d: se esperaba el rechazo CAJA_CERRADA y fue %s", i, error).isNotNull();
            if (error instanceof VentaRechazadaException v) {
                assertThat(v.motivo()).isEqualTo(VentaRechazadaException.Motivo.CAJA_CERRADA);
                assertThat(v.motivo().estadoHttp()).isEqualTo(422);
            } else {
                assertThat(error).isInstanceOf(CobranzaRechazadaException.class);
                CobranzaRechazadaException c = (CobranzaRechazadaException) error;
                assertThat(c.motivo()).isEqualTo(CobranzaRechazadaException.Motivo.CAJA_CERRADA);
                assertThat(c.motivo().estadoHttp()).isEqualTo(422);
            }
            assertThat(error.getMessage()).isEqualTo(mensajes.get(i));
        }
        // ningún lector escribió nada: ni venta, ni cobranza nueva, ni anulación; el saldo es el sembrado (1.000 − 400)
        assertThat(saldo(e.cliente())).isEqualByComparingTo("600");
        assertThat(jdbc.queryForObject("select count(*) from movimiento_cuenta_cliente where cliente_id = ? and tipo = 'ANULACION'",
                Long.class, e.cliente())).isZero();
        assertThat(jdbc.queryForObject("select estado from recibo where id = ?", String.class, e.recibo())).isEqualTo("EMITIDO");
        assertThat(jdbc.queryForObject("select count(*) from recibo where ferreteria_id = ?", Long.class, e.f().id())).isEqualTo(1);
        assertInvariante(e.cliente());
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Tests
    // ---------------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("CC-092 venta × cierre (con el cierre REAL pausado tras tomar la caja): la venta en LOCK WAIT sobre la caja recibe 422 CAJA_CERRADA «Abrí la caja para vender.»; el cierre confirma y lock_deadlocks 0")
    void ventaContraElCierre() throws Exception {
        Escenario e = escenario();
        cierreContraLectores(e, List.of(venta(e)), List.of("Abrí la caja para vender."));
    }

    @Test
    @DisplayName("CC-092 cobranza × cierre (cierre REAL pausado): la cobranza en LOCK WAIT recibe 422 CAJA_CERRADA «Abrí la caja para cobrar.»; el cierre confirma y lock_deadlocks 0")
    void cobranzaContraElCierre() throws Exception {
        Escenario e = escenario();
        cierreContraLectores(e, List.of(cobranza(e)), List.of("Abrí la caja para cobrar."));
    }

    @Test
    @DisplayName("CC-092 anulación × cierre (cierre REAL pausado): la anulación en LOCK WAIT recibe 422 CAJA_CERRADA «Abrí la caja para anular.»; recibo EMITIDO; el cierre confirma y lock_deadlocks 0")
    void anulacionContraElCierre() throws Exception {
        Escenario e = escenario();
        cierreContraLectores(e, List.of(anulacion(e)), List.of("Abrí la caja para anular."));
    }

    @Test
    @DisplayName("CC-092 los tres lectores a la vez (venta + cobranza + anulación, los tres en LOCK WAIT sobre la caja) × cierre REAL pausado: cierre OK, los tres 422 CAJA_CERRADA y lock_deadlocks 0")
    void ventaCobranzaYAnulacionContraElCierre() throws Exception {
        Escenario e = escenario();
        cierreContraLectores(e, List.of(venta(e), cobranza(e), anulacion(e)),
                List.of("Abrí la caja para vender.", "Abrí la caja para cobrar.", "Abrí la caja para anular."));
    }
}
