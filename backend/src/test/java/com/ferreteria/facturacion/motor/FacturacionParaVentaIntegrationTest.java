package com.ferreteria.facturacion.motor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.facturacion.api.FacturacionParaVenta;
import com.ferreteria.facturacion.api.FacturacionParaVenta.CondicionIva;
import com.ferreteria.facturacion.api.FacturacionParaVenta.Decision;
import com.ferreteria.facturacion.api.FacturacionParaVenta.EstadoFiscalVenta;
import com.ferreteria.facturacion.api.FacturacionParaVenta.ReceptorVenta;
import com.ferreteria.facturacion.api.FacturacionParaVenta.RenglonParaFacturar;
import com.ferreteria.facturacion.api.FacturacionParaVenta.TipoDocumento;
import com.ferreteria.facturacion.config.EjecutorFiscalConfig;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.repository.ComprobanteFiscalRepository;
import com.ferreteria.facturacion.repository.ConfigArcaRepository;
import com.ferreteria.facturacion.service.FacturacionParaVentaImpl;
import com.ferreteria.facturacion.wsfe.WsfeConFallas.Falla;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * El puerto de facturación para la venta ({@link FacturacionParaVenta}; cu-07-venta-facturada S1 b y c, S3 y S4) contra
 * MySQL real y el simulador: {@code decidir} y {@code registrar} dentro de la transacción de la venta (acá, un
 * {@link TransactionTemplate}), y {@code autorizar} y {@code estado} después, sin transacción. Vive junto al soporte de los
 * tests del motor ({@link MotorFiscalTestBase}: una ferretería RI con el simulador y la facturación habilitada por test, el
 * reloj fijo y {@link WsfeDePrueba}). La espera de {@code autorizar} es de 2 s, para que los casos que la agotan no demoren
 * la suite; se mide con el reloj de pared (el {@code Clock} está fijo).
 */
@TestPropertySource(properties = "app.facturacion.venta.espera=2s")
class FacturacionParaVentaIntegrationTest extends MotorFiscalTestBase {

    private static final Duration ESPERA = Duration.ofSeconds(2);
    /** Lo que puede tardar {@code autorizar} por encima de la espera (la lectura final, un hilo lento). */
    private static final Duration MARGEN = Duration.ofMillis(1500);
    private static final String CUIT_RECEPTOR = "30786013301";

    @Autowired
    FacturacionParaVenta facturacion;
    @Autowired
    PlatformTransactionManager transacciones;
    @Autowired
    PasosFiscales pasos;
    @Autowired
    ComprobanteFiscalRepository registros;
    @Autowired
    ConfigArcaRepository configs;
    @Autowired
    @Qualifier(EjecutorFiscalConfig.EJECUTOR_FISCAL)
    ThreadPoolTaskExecutor ejecutor;

    // ---- decidir ----

    @Test
    @DisplayName("sin fila de config_arca no factura, y el receptor cuenta como completo aunque le falte el CUIT")
    void sinConfiguracionNoFactura() {
        TenantContext.setCurrentFerreteriaId(ferreteriaSinConfig(cuitNuevo()));
        ReceptorVenta riSinCuit = new ReceptorVenta(CondicionIva.RESPONSABLE_INSCRIPTO, null, null, "Corralón Sur SA", null);

        Decision decision = enLaVenta(() -> facturacion.decidir(riSinCuit));

        assertThat(decision).isEqualTo(new Decision(false, true, null));
    }

    @Test
    @DisplayName("con el simulador asignado pero la facturación apagada no factura")
    void conLaFacturacionApagadaNoFactura() {
        long f = ferreteriaSinConfig(cuitNuevo());
        jdbc.update("insert into config_arca (ferreteria_id, de_prueba, ambiente, punto_venta, facturacion_habilitada) "
                + "values (?, true, 'SIMULADOR', 1, false)", f);
        TenantContext.setCurrentFerreteriaId(f);

        assertThat(enLaVenta(() -> facturacion.decidir(ReceptorVenta.sinCliente()))).isEqualTo(Decision.SIN_FACTURA);
    }

    @Test
    @DisplayName("con la facturación habilitada factura, con el tope de identificación de ARCA ($ 10.000.000)")
    void conLaFacturacionHabilitadaFacturaConElTopeDeArca() {
        Decision decision = enLaVenta(() -> facturacion.decidir(ReceptorVenta.sinCliente()));

        assertThat(decision.factura()).isTrue();
        assertThat(decision.receptorCompleto()).isTrue();
        assertThat(decision.topeIdentificacion()).isEqualByComparingTo("10000000.00");
    }

    static Stream<Arguments> receptores() {
        return Stream.of(
                Arguments.of("Consumidor Final sin documento", receptor(CondicionIva.CONSUMIDOR_FINAL, null, null), true),
                Arguments.of("Consumidor Final con DNI y sin domicilio",
                        receptor(CondicionIva.CONSUMIDOR_FINAL, TipoDocumento.DNI, null), true),
                Arguments.of("Responsable Inscripto con CUIT y domicilio",
                        receptor(CondicionIva.RESPONSABLE_INSCRIPTO, TipoDocumento.CUIT, "Av. Colón 2000"), true),
                Arguments.of("Monotributista con CUIT y domicilio",
                        receptor(CondicionIva.MONOTRIBUTISTA, TipoDocumento.CUIT, "Av. Colón 2000"), true),
                Arguments.of("Exento con CUIT y domicilio",
                        receptor(CondicionIva.EXENTO, TipoDocumento.CUIT, "Av. Colón 2000"), true),
                Arguments.of("Responsable Inscripto sin documento",
                        receptor(CondicionIva.RESPONSABLE_INSCRIPTO, null, "Av. Colón 2000"), false),
                Arguments.of("Monotributista con DNI en vez de CUIT",
                        receptor(CondicionIva.MONOTRIBUTISTA, TipoDocumento.DNI, "Av. Colón 2000"), false),
                Arguments.of("Exento con CUIL en vez de CUIT",
                        receptor(CondicionIva.EXENTO, TipoDocumento.CUIL, "Av. Colón 2000"), false),
                Arguments.of("Responsable Inscripto con CUIT y sin domicilio",
                        receptor(CondicionIva.RESPONSABLE_INSCRIPTO, TipoDocumento.CUIT, null), false),
                Arguments.of("Exento con CUIT y el domicilio en blanco",
                        receptor(CondicionIva.EXENTO, TipoDocumento.CUIT, "   "), false));
    }

    @ParameterizedTest(name = "{0} → completo: {2}")
    @MethodSource("receptores")
    @DisplayName("receptor completo (D4): Consumidor Final, o RI, Monotributista o Exento con CUIT y domicilio")
    void receptorCompletoSegunLaCondicion(String caso, ReceptorVenta receptor, boolean completo) {
        Decision decision = enLaVenta(() -> facturacion.decidir(receptor));

        assertThat(decision.factura()).isTrue();
        assertThat(decision.receptorCompleto()).isEqualTo(completo);
    }

    @Test
    @DisplayName("decidir deja tomado el lock compartido de config_arca hasta el commit: el panel no la cambia mientras tanto")
    void decidirSostieneElLockCompartidoHastaElCommit() {
        String paraCambiarla = "select id from config_arca where ferreteria_id = ? for update nowait";

        enLaVenta(() -> {
            facturacion.decidir(ReceptorVenta.sinCliente());
            assertThatThrownBy(() -> enOtroHilo(() -> jdbc.queryForList(paraCambiarla, Long.class, ferreteriaId)))
                    .rootCause().hasMessageContaining("NOWAIT");
            return null;
        });

        assertThat(enOtroHilo(() -> jdbc.queryForList(paraCambiarla, Long.class, ferreteriaId))).hasSize(1);
    }

    @Test
    @DisplayName("decidir y registrar exigen la transacción de la venta (MANDATORY) y sin ella no guardan nada")
    void decidirYRegistrarExigenUnaTransaccion() {
        long venta = venta(ferreteriaId);

        assertThatThrownBy(() -> facturacion.decidir(ReceptorVenta.sinCliente()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> facturacion.registrar(venta, ReceptorVenta.sinCliente(), renglones121(), AHORA))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(jdbc.queryForObject("select count(*) from comprobante_fiscal where ferreteria_id = ?", Long.class,
                ferreteriaId)).isZero();
    }

    // ---- registrar ----

    @Test
    @DisplayName("registrar da de alta el PENDIENTE con la letra del emisor y el receptor, la fecha de la venta y las alícuotas")
    void registrarDaDeAltaElPendiente() {
        long venta = venta(ferreteriaId);
        Instant fecha = AHORA.minus(Duration.ofHours(2));
        ReceptorVenta monotributista = new ReceptorVenta(CondicionIva.MONOTRIBUTISTA, TipoDocumento.CUIT, CUIT_RECEPTOR,
                "N".repeat(130), "D".repeat(210));

        long id = enLaVenta(() -> {
            facturacion.decidir(monotributista);
            return facturacion.registrar(venta, monotributista, List.of(
                    new RenglonParaFacturar(new BigDecimal("21.00"), new BigDecimal("100.00")),
                    new RenglonParaFacturar(new BigDecimal("10.5"), new BigDecimal("50.00"))), fecha);
        });

        Map<String, Object> r = registro(id);
        assertThat(r).containsEntry("comprobante_id", venta).containsEntry("estado", "PENDIENTE")
                .containsEntry("letra", "A").containsEntry("cbte_tipo", FACTURA_A).containsEntry("numero", null)
                .containsEntry("receptor_doc_tipo", 80).containsEntry("receptor_doc_nro", Long.parseLong(CUIT_RECEPTOR))
                .containsEntry("receptor_condicion_iva_id", 6);
        // El nombre y el domicilio entran cortados a lo que guarda el registro (120 y 200).
        assertThat((String) r.get("receptor_nombre")).isEqualTo("N".repeat(120));
        assertThat((String) r.get("receptor_domicilio")).isEqualTo("D".repeat(200));
        // La fecha de la venta, no la del reloj.
        assertThat(registros.findScoped(id).orElseThrow().getRegistradoEn()).isEqualTo(fecha);
        assertThat(jdbc.queryForList("select iva_id from comprobante_fiscal_alicuota where comprobante_fiscal_id = ? "
                + "order by iva_id", Integer.class, id)).containsExactly(4, 5);
    }

    @Test
    @DisplayName("una alícuota que ARCA no tiene es un error de quien llama y no da de alta nada")
    void registrarConUnaAlicuotaDesconocidaSeNiega() {
        long venta = venta(ferreteriaId);

        assertThatThrownBy(() -> enLaVenta(() -> {
            facturacion.decidir(ReceptorVenta.sinCliente());
            return facturacion.registrar(venta, ReceptorVenta.sinCliente(),
                    List.of(new RenglonParaFacturar(new BigDecimal("15"), new BigDecimal("100.00"))), AHORA);
        })).isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject("select count(*) from comprobante_fiscal where comprobante_id = ?", Long.class,
                venta)).isZero();
    }

    // ---- autorizar y estado ----

    @Test
    @DisplayName("autorizar pide el CAE y devuelve el registro AUTORIZADO con su letra, número, CAE y vencimiento")
    void autorizarDevuelveElAutorizado() {
        long venta = ventaFacturada();

        Optional<EstadoFiscalVenta> estado = facturacion.autorizar(venta);

        Map<String, Object> r = registro(registroDe(venta));
        assertThat(estado).contains(new EstadoFiscalVenta("AUTORIZADO", "FACTURA", "B", 1, 1L, (String) r.get("cae"),
                ((Date) r.get("cae_vencimiento")).toLocalDate(), "SIMULADOR"));
        assertThat(estado.orElseThrow().cae()).matches("\\d{14}");
        assertThat(wsfe.solicitudes()).isEqualTo(1);
    }

    @Test
    @DisplayName("autorizar dentro de una transacción se niega sin llamar a ARCA (la llamada no va con una conexión tomada)")
    void autorizarDentroDeUnaTransaccionSeNiega() {
        long venta = ventaFacturada();

        assertThatThrownBy(() -> enLaVenta(() -> facturacion.autorizar(venta))).isInstanceOf(IllegalStateException.class);
        assertThat(wsfe.solicitudes()).isZero();
        assertThat(estado(registroDe(venta))).isEqualTo("PENDIENTE");
    }

    @Test
    @DisplayName("autorizar lo ya autorizado y estado devuelven lo guardado al instante, sin tocar la serie ni llamar a ARCA")
    void loResueltoNoVuelveAArca() {
        long venta = ventaFacturada();
        EstadoFiscalVenta autorizado = facturacion.autorizar(venta).orElseThrow();
        assertThat(autorizado.estado()).isEqualTo("AUTORIZADO");
        // Con la serie tomada por otra instancia, una atención esperaría toda la espera.
        tomarLaSerieDesdeOtraInstancia();

        long inicio = System.nanoTime();
        assertThat(facturacion.autorizar(venta)).contains(autorizado);
        assertThat(facturacion.estado(venta)).contains(autorizado);

        assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(ESPERA.dividedBy(2));
        assertThat(wsfe.solicitudes()).isEqualTo(1);
    }

    @Test
    @DisplayName("estado devuelve el registro como está, sin llamar a ARCA")
    void estadoNoLlamaAArca() {
        long venta = ventaFacturada();

        assertThat(facturacion.estado(venta)).contains(
                new EstadoFiscalVenta("PENDIENTE", "FACTURA", "B", 1, null, null, null, "SIMULADOR"));
        assertThat(wsfe.solicitudes()).isZero();
        assertThat(estado(registroDe(venta))).isEqualTo("PENDIENTE");
    }

    @Test
    @DisplayName("una venta sin registro fiscal no tiene estado: autorizar y estado vuelven vacíos")
    void sinRegistroFiscalVuelveVacio() {
        long venta = venta(ferreteriaId);

        assertThat(facturacion.autorizar(venta)).isEmpty();
        assertThat(facturacion.estado(venta)).isEmpty();
        assertThat(wsfe.solicitudes()).isZero();
    }

    @Test
    @DisplayName("el registro de otra ferretería no se ve: autorizar y estado vuelven vacíos y no lo tocan")
    void elRegistroDeOtraFerreteriaNoSeVe() {
        long ajena = ventaFacturada();
        TenantContext.setCurrentFerreteriaId(ferreteriaConFacturacion(cuitNuevo()));

        assertThat(facturacion.autorizar(ajena)).isEmpty();
        assertThat(facturacion.estado(ajena)).isEmpty();

        assertThat(wsfe.solicitudes()).isZero();
        assertThat(estado(registroDe(ajena))).isEqualTo("PENDIENTE");
    }

    @Test
    @DisplayName("con el simulador más lento que la espera, responde a tiempo con PENDIENTE o EN_CURSO y después se autoriza")
    void conElSimuladorLentoRespondeATiempo() {
        long venta = ventaFacturada();
        WsfeDePrueba.Pausa pausa = wsfe.pausarElSiguienteFeCaeSolicitar();

        long inicio = System.nanoTime();
        Optional<EstadoFiscalVenta> estado = facturacion.autorizar(venta);
        Duration tardo = Duration.ofNanos(System.nanoTime() - inicio);

        assertThat(tardo).isBetween(ESPERA.minusMillis(100), ESPERA.plus(MARGEN));
        assertThat(estado).get().extracting(EstadoFiscalVenta::estado).isIn("PENDIENTE", "EN_CURSO");
        pausa.esperarQueLlegue();
        pausa.soltar();
        esperarHasta(() -> "AUTORIZADO".equals(estado(registroDe(venta))) && leaseDe() == null);
        assertThat(facturacion.estado(venta)).get().extracting(EstadoFiscalVenta::numero).isEqualTo(1L);
        assertThat(wsfe.solicitudes()).isEqualTo(1);
    }

    @Test
    @DisplayName("con la serie OCUPADA por otra instancia que la suelta a los 300 ms, reintenta y se autoriza dentro de la espera")
    void conLaSerieOcupadaReintentaHastaQueSeLibera() {
        long venta = ventaFacturada();
        long serie = tomarLaSerieDesdeOtraInstancia();
        CompletableFuture<Void> soltar = CompletableFuture.runAsync(() -> {
            dormir(Duration.ofMillis(300));
            jdbc.update("update serie_fiscal set lease_dueno = null, lease_vence = null where id = ?", serie);
        });

        long inicio = System.nanoTime();
        Optional<EstadoFiscalVenta> estado = facturacion.autorizar(venta);
        Duration tardo = Duration.ofNanos(System.nanoTime() - inicio);

        soltar.join();
        assertThat(estado).get().extracting(EstadoFiscalVenta::estado).isEqualTo("AUTORIZADO");
        assertThat(tardo).isBetween(Duration.ofMillis(250), ESPERA);
        assertThat(wsfe.solicitudes()).isEqualTo(1);
    }

    @Test
    @DisplayName("vencida la espera con la serie tomada, devuelve PENDIENTE y deja de reintentar")
    void vencidaLaEsperaDejaDeReintentar() {
        long venta = ventaFacturada();
        long serie = tomarLaSerieDesdeOtraInstancia();

        Optional<EstadoFiscalVenta> estado = facturacion.autorizar(venta);
        assertThat(estado).get().extracting(EstadoFiscalVenta::estado).isEqualTo("PENDIENTE");
        jdbc.update("update serie_fiscal set lease_dueno = null, lease_vence = null where id = ?", serie);

        // Varias pausas entre reintentos (200 ms): si siguiera, ya habría tomado la serie y pedido el CAE.
        dormir(Duration.ofMillis(800));
        assertThat(estado(registroDe(venta))).isEqualTo("PENDIENTE");
        assertThat(wsfe.solicitudes()).isZero();
    }

    @Test
    @DisplayName("un timeout después de autorizar deja EN_VERIFICACION, y la venta siguiente de la serie adopta su CAE")
    void elTimeoutDespuesDeAutorizarLoRecuperaLaVentaSiguiente() {
        long primera = ventaFacturada();
        wsfe.fallas().programar(Falla.TIMEOUT_DESPUES_DE_AUTORIZAR);

        assertThat(facturacion.autorizar(primera)).get()
                .satisfies(e -> assertThat(e.estado()).isEqualTo("EN_VERIFICACION"))
                .satisfies(e -> assertThat(e.numero()).isEqualTo(1L))
                .satisfies(e -> assertThat(e.cae()).isNull());

        long segunda = ventaFacturada();
        assertThat(facturacion.autorizar(segunda)).get()
                .satisfies(e -> assertThat(e.estado()).isEqualTo("AUTORIZADO"))
                .satisfies(e -> assertThat(e.numero()).isEqualTo(2L));
        assertThat(facturacion.estado(primera)).get()
                .satisfies(e -> assertThat(e.estado()).isEqualTo("AUTORIZADO"))
                .satisfies(e -> assertThat(e.numero()).isEqualTo(1L))
                .satisfies(e -> assertThat(e.cae()).isEqualTo(caeEnArca(FACTURA_B, 1)));
        assertThat(wsfe.solicitudes()).isEqualTo(2);
    }

    @Test
    @DisplayName("una excepción sin traducir del puerto no sale de autorizar: devuelve el estado en que quedó")
    void unaExcepcionDelPuertoNoSaleDeAutorizar() {
        long venta = ventaFacturada();
        wsfe.lanzarEnElSiguienteFeCaeSolicitar(new IllegalStateException("Falla del adaptador sin traducir (inyectada)."));

        Optional<EstadoFiscalVenta> estado = facturacion.autorizar(venta);

        assertThat(estado).get().extracting(EstadoFiscalVenta::estado).isEqualTo("EN_CURSO");
        assertThat(leaseDe()).isNull();
    }

    @Test
    @DisplayName("sin lugar en el ejecutor (hilos ocupados y cola llena), devuelve el estado actual sin esperar ni llamar a ARCA")
    void sinLugarEnElEjecutorDevuelveElEstadoActual() throws Exception {
        long venta = ventaFacturada();
        ThreadPoolTaskExecutor unHiloSinCola = new ThreadPoolTaskExecutor();
        unHiloSinCola.setCorePoolSize(1);
        unHiloSinCola.setMaxPoolSize(1);
        unHiloSinCola.setQueueCapacity(0);
        unHiloSinCola.initialize();
        CountDownLatch soltar = new CountDownLatch(1);
        try {
            unHiloSinCola.submit(() -> {
                soltar.await();
                return null;
            });
            FacturacionParaVenta conUnHiloOcupado = new FacturacionParaVentaImpl(configs, registros, motor, unHiloSinCola,
                    transacciones, new BigDecimal("10000000.00"), ESPERA, Duration.ofMillis(200));

            long inicio = System.nanoTime();
            Optional<EstadoFiscalVenta> estado = conUnHiloOcupado.autorizar(venta);

            assertThat(Duration.ofNanos(System.nanoTime() - inicio)).isLessThan(ESPERA.dividedBy(2));
            assertThat(estado).get().extracting(EstadoFiscalVenta::estado).isEqualTo("PENDIENTE");
            assertThat(wsfe.solicitudes()).isZero();
        } finally {
            soltar.countDown();
            unHiloSinCola.shutdown();
        }
    }

    @Test
    @DisplayName("con el hilo interrumpido, devuelve el estado actual sin lanzar y deja la interrupción marcada")
    void conElHiloInterrumpidoDevuelveElEstadoYConservaLaMarca() {
        long venta = ventaFacturada();

        Thread.currentThread().interrupt();
        Optional<EstadoFiscalVenta> estado;
        boolean interrumpido;
        try {
            estado = facturacion.autorizar(venta);
        } finally {
            interrumpido = Thread.interrupted();
        }

        assertThat(interrumpido).isTrue();
        assertThat(estado).get().extracting(EstadoFiscalVenta::estado).isIn("PENDIENTE", "EN_CURSO", "AUTORIZADO");
        // La atención, si llegó a arrancar antes de abandonarla, termina sola; si no, no arranca.
        sinAtencionesEnMarcha();
        assertThat(estado(registroDe(venta))).isIn("PENDIENTE", "AUTORIZADO");
        assertThat(leaseDe()).isNull();
    }

    // ---- Ayudas ----

    /**
     * Lo que haya quedado en marcha en el ejecutor fiscal (una atención abandonada termina sola) termina antes del test
     * siguiente, que reinicia el contador de pedidos de {@link WsfeDePrueba}.
     */
    @AfterEach
    void sinAtencionesEnMarcha() {
        ThreadPoolExecutor pool = ejecutor.getThreadPoolExecutor();
        esperarHasta(() -> pool.getCompletedTaskCount() == pool.getTaskCount());
    }

    /** Lo que hace la venta: decide y registra una Factura B a Consumidor Final por $ 121 al 21 %, en su transacción. */
    private long ventaFacturada() {
        long venta = venta(ferreteriaId);
        enLaVenta(() -> {
            assertThat(facturacion.decidir(ReceptorVenta.sinCliente()).factura()).isTrue();
            return facturacion.registrar(venta, ReceptorVenta.sinCliente(), renglones121(), AHORA);
        });
        return venta;
    }

    private static List<RenglonParaFacturar> renglones121() {
        return List.of(new RenglonParaFacturar(new BigDecimal("21.00"), new BigDecimal("121.00")));
    }

    private static ReceptorVenta receptor(CondicionIva condicion, TipoDocumento tipo, String domicilio) {
        String numero = tipo == null ? null : tipo == TipoDocumento.DNI ? "30123456" : CUIT_RECEPTOR;
        return new ReceptorVenta(condicion, tipo, numero, "Cliente", domicilio);
    }

    private <T> T enLaVenta(Supplier<T> trabajo) {
        return new TransactionTemplate(transacciones).execute(s -> trabajo.get());
    }

    /** En otro hilo, con su propia conexión (fuera de la transacción del test). */
    private static <T> T enOtroHilo(Supplier<T> trabajo) {
        return CompletableFuture.supplyAsync(trabajo).join();
    }

    private long registroDe(long venta) {
        return jdbc.queryForObject("select id from comprobante_fiscal where comprobante_id = ?", Long.class, venta);
    }

    /** La serie de las Facturas B, tomada por otra instancia hasta 2030 (el reloj está fijo en 2026). */
    private long tomarLaSerieDesdeOtraInstancia() {
        long serie = pasos.asegurarSerie(Ambiente.SIMULADOR, 1, FACTURA_B);
        jdbc.update("update serie_fiscal set lease_dueno = 'otra-instancia', lease_vence = '2030-01-01 00:00:00' "
                + "where id = ?", serie);
        return serie;
    }

    private String leaseDe() {
        List<String> l = jdbc.queryForList("select lease_dueno from serie_fiscal where ferreteria_id = ? and cbte_tipo = ?",
                String.class, ferreteriaId, FACTURA_B);
        return l.isEmpty() ? null : l.get(0);
    }

    private static void esperarHasta(BooleanSupplier condicion) {
        long tope = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condicion.getAsBoolean()) {
            if (System.nanoTime() > tope) {
                throw new AssertionError("No se cumplió la condición en 10 s");
            }
            dormir(Duration.ofMillis(25));
        }
    }

    private static void dormir(Duration duracion) {
        try {
            Thread.sleep(duracion.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrumpido", e);
        }
    }
}
