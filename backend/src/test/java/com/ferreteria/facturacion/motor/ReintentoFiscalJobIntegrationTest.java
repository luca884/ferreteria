package com.ferreteria.facturacion.motor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ferreteria.facturacion.api.FacturacionParaVenta;
import com.ferreteria.facturacion.api.FacturacionParaVenta.EstadoFiscalVenta;
import com.ferreteria.facturacion.model.Alicuota;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.model.CondicionIvaReceptor;
import com.ferreteria.facturacion.model.ReceptorFiscal;
import com.ferreteria.facturacion.model.RenglonFiscal;
import com.ferreteria.facturacion.model.TipoDocumentoReceptor;
import com.ferreteria.facturacion.reintento.ProgramacionDelReintento;
import com.ferreteria.facturacion.reintento.ReintentoFiscalJob;
import com.ferreteria.facturacion.wsfe.Auth;
import com.ferreteria.facturacion.wsfe.WsfeConFallas.Falla;
import com.ferreteria.ferreteria.repository.FerreteriaRepository;
import com.ferreteria.support.BarreraDeLocks;
import com.ferreteria.support.BarreraDeLocks.Largada;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.TestPropertySource;

/**
 * El job de reintento (cu-07-contingencias S2, S7, S8 y C5) contra MySQL real y el simulador, con el {@code Clock} fijo y
 * dos ferreterías con facturación (la del test y otra): la pasada por ferretería y por serie, la serie tomada, el corte en
 * el primer transitorio, el backoff, el tope de la pasada, el pendiente que se queda fuera de fecha, el job y la venta sobre
 * el mismo pendiente, dos instancias del job y un apagado con un envío en vuelo. Como en
 * {@code RecalculoDiarioJobIntegrationTest}, el job se arma a mano con los beans reales y una lista fija de ferreterías,
 * para no recorrer las demás que hay en la base de tests. Las llamadas se detienen con una pausa del puerto
 * ({@link WsfeDePrueba.Pausa}), sin tiempos. La espera de la venta es de 2 s (el contexto de
 * {@code FacturacionParaVentaIntegrationTest}).
 */
@TestPropertySource(properties = "app.facturacion.venta.espera=2s")
class ReintentoFiscalJobIntegrationTest extends MotorFiscalTestBase {

    private static final long TOPE_SEGUNDOS = 60;

    @Autowired
    PasosFiscales pasos;
    @Autowired
    WsfePorAmbiente wsfePorAmbiente;
    @Autowired
    PoliticaDeReintento politica;
    @Autowired
    FacturacionParaVenta facturacion;
    @Autowired
    ReintentoFiscalJob jobDelContexto;
    @Autowired
    ApplicationContext contexto;
    @Value("${app.facturacion.motor.lease}")
    Duration lease;
    @Value("${app.facturacion.motor.presupuesto-llamada}")
    Duration presupuesto;

    /** La otra ferretería, con su propia CUIT (su propia numeración en el simulador). */
    long otra;
    long cuitOtra;
    private ExecutorService pool;

    @BeforeEach
    void otraFerreteria() {
        cuitOtra = cuitNuevo();
        otra = ferreteriaConFacturacion(cuitOtra);
        pool = Executors.newFixedThreadPool(2);
    }

    @AfterEach
    void sinHilos() {
        pool.shutdownNow();
    }

    // ---- El bean ----

    @Test
    @DisplayName("el job es un bean y en los tests nadie lo dispara solo: sin app.programacion.habilitada no hay programación")
    void esUnBeanYEnLosTestsNadieLoDisparaSolo() {
        assertThat(jobDelContexto).isNotNull();
        assertThat(contexto.getBeansOfType(ProgramacionDelReintento.class)).isEmpty();
    }

    // ---- La pasada ----

    @Test
    @DisplayName("recorre las ferreterías activas y, de cada una, sus series con trabajo en FIFO, y deja el TenantContext "
            + "limpio")
    void recorreLasFerreteriasYSusSeriesEnFifo() {
        long b1 = pendienteB(ferreteriaId);
        long b2 = pendienteB(ferreteriaId);
        long a1 = pendienteA(ferreteriaId);
        long b3 = pendienteB(ferreteriaId);
        long o1 = pendienteB(otra);
        long o2 = pendienteB(otra);

        job(ferreteriaId, otra).pasada();

        assertThat(TenantContext.currentFerreteriaId()).as("el job limpia el TenantContext de cada ferretería").isEmpty();
        assertThat(List.of(b1, b2, b3, a1, o1, o2)).allSatisfy(r -> assertThat(estado(r)).isEqualTo("AUTORIZADO"));
        assertThat(List.of(numero(b1), numero(b2), numero(b3))).containsExactly(1L, 2L, 3L);
        assertThat(numero(a1)).isEqualTo(1L);
        assertThat(List.of(numero(o1), numero(o2))).containsExactly(1L, 2L);
        assertThat(ordenDeEnvio()).as("la serie B de la primera (su pendiente es el más viejo), su serie A y la otra")
                .containsExactly(b1, b2, b3, a1, o1, o2);
        assertThat(wsfe.solicitudes()).isEqualTo(6);
        assertThat(ultimoEnArca(ferreteriaId, FACTURA_B)).isEqualTo(3);
        assertThat(ultimoEnArca(otra, FACTURA_B)).isEqualTo(2);
    }

    @Test
    @DisplayName("una serie tomada por otro (la venta u otra JVM) se saltea sin tocar su backoff, y las demás siguen")
    void unaSerieTomadaSeSalteaYLasDemasSiguen() throws Exception {
        long tomado = pendienteB(ferreteriaId);
        long a1 = pendienteA(ferreteriaId);
        long o1 = pendienteB(otra);
        WsfeDePrueba.Pausa pausa = wsfe.pausarElSiguienteFeCaeSolicitar();
        Future<Atencion> deOtro = pool.submit(() -> en(ferreteriaId, () -> motor.atender(Ambiente.SIMULADOR, 1, FACTURA_B)));
        pausa.esperarQueLlegue();

        job(ferreteriaId, otra).pasada();

        assertThat(estado(tomado)).as("sigue en manos del otro").isEqualTo("EN_CURSO");
        assertThat(estado(a1)).isEqualTo("AUTORIZADO");
        assertThat(estado(o1)).isEqualTo("AUTORIZADO");
        assertThat(backoff(ferreteriaId, FACTURA_B)).containsEntry("fallas_seguidas", 0)
                .containsEntry("proximo_reintento", null);
        assertThat(wsfe.solicitudes()).isEqualTo(3);

        pausa.soltar();
        assertThat(deOtro.get(TOPE_SEGUNDOS, TimeUnit.SECONDS)).isEqualTo(Atencion.TERMINADA);
        assertThat(estado(tomado)).isEqualTo("AUTORIZADO");
        assertThat(intentos(tomado)).hasSize(1);
    }

    @Test
    @DisplayName("corta la serie en el primer error transitorio y la pospone (backoff), y las demás series y ferreterías "
            + "siguen")
    void cortaLaSerieEnElPrimerTransitorioYLaPospone() {
        long b1 = pendienteB(ferreteriaId);
        long b2 = pendienteB(ferreteriaId);
        long a1 = pendienteA(ferreteriaId);
        long o1 = pendienteB(otra);
        wsfe.fallas().programar(Falla.NO_LLEGA);

        job(ferreteriaId, otra).pasada();

        assertThat(estado(b1)).isEqualTo("PENDIENTE");
        assertThat(intentos(b1)).singleElement().satisfies(i -> assertThat(i).containsEntry("estado", "NO_LLEGO"));
        assertThat(estado(b2)).isEqualTo("PENDIENTE");
        assertThat(intentos(b2)).as("la serie cortó en el primer transitorio").isEmpty();
        assertThat(backoff(ferreteriaId, FACTURA_B)).containsEntry("fallas_seguidas", 1);
        assertThat(proximoReintento(ferreteriaId, FACTURA_B)).isEqualTo(AHORA.plus(Duration.ofMinutes(1)));
        assertThat(estado(a1)).isEqualTo("AUTORIZADO");
        assertThat(backoff(ferreteriaId, FACTURA_A)).containsEntry("fallas_seguidas", 0);
        assertThat(estado(o1)).isEqualTo("AUTORIZADO");
        assertThat(backoff(otra, FACTURA_B)).containsEntry("fallas_seguidas", 0);
        assertThat(wsfe.solicitudes()).isEqualTo(3);
    }

    @Test
    @DisplayName("respeta el backoff de cada serie: no la reintenta antes de su próximo reintento (la otra ferretería sigue), "
            + "lo duplica con otro error y vuelve a cero cuando la serie autoriza")
    void respetaElBackoffYVuelveACeroAlAutorizar() {
        long b1 = pendienteB(ferreteriaId);
        ReintentoFiscalJob job = job(ferreteriaId, otra);
        wsfe.fallas().programar(Falla.NO_LLEGA);
        job.pasada();
        assertThat(proximoReintento(ferreteriaId, FACTURA_B)).isEqualTo(AHORA.plus(Duration.ofMinutes(1)));

        reloj.avanzar(Duration.ofSeconds(59));
        long o1 = pendienteB(otra);
        job.pasada();
        assertThat(estado(b1)).as("antes del próximo reintento no se manda").isEqualTo("PENDIENTE");
        assertThat(intentos(b1)).hasSize(1);
        assertThat(estado(o1)).as("el backoff es de la serie, no del job").isEqualTo("AUTORIZADO");
        assertThat(wsfe.solicitudes()).isEqualTo(2);

        reloj.avanzar(Duration.ofSeconds(1));
        wsfe.fallas().programar(Falla.NO_LLEGA);
        job.pasada();
        assertThat(intentos(b1)).hasSize(2);
        assertThat(backoff(ferreteriaId, FACTURA_B)).containsEntry("fallas_seguidas", 2);
        Instant segundo = AHORA.plus(Duration.ofMinutes(1)).plus(Duration.ofMinutes(2));
        assertThat(proximoReintento(ferreteriaId, FACTURA_B)).isEqualTo(segundo);

        reloj.fijar(segundo.minusSeconds(1));
        job.pasada();
        assertThat(intentos(b1)).hasSize(2);

        reloj.fijar(segundo);
        job.pasada();
        assertThat(estado(b1)).isEqualTo("AUTORIZADO");
        assertThat(intentos(b1)).hasSize(3);
        assertThat(backoff(ferreteriaId, FACTURA_B)).containsEntry("fallas_seguidas", 0)
                .containsEntry("proximo_reintento", null);
        assertThat(wsfe.solicitudes()).isEqualTo(4);
    }

    @Test
    @DisplayName("la pasada respeta su tope: con una llamada en vuelo que lo pasa, termina esa y no empieza otro registro, "
            + "otra serie ni otra ferretería; la pasada siguiente sigue con lo que falta")
    void laPasadaRespetaSuTope() throws Exception {
        long b1 = pendienteB(ferreteriaId);
        long b2 = pendienteB(ferreteriaId);
        long a1 = pendienteA(ferreteriaId);
        long o1 = pendienteB(otra);
        ReintentoFiscalJob job = job(ferreteriaId, otra);
        WsfeDePrueba.Pausa pausa = wsfe.pausarElSiguienteFeCaeSolicitar();
        Future<?> pasada = pool.submit(() -> job.pasada());
        pausa.esperarQueLlegue();

        reloj.avanzar(politica.topePasada().plusSeconds(1));
        pausa.soltar();
        pasada.get(TOPE_SEGUNDOS, TimeUnit.SECONDS);

        assertThat(estado(b1)).isEqualTo("AUTORIZADO");
        assertThat(List.of(b2, a1, o1)).allSatisfy(r -> {
            assertThat(estado(r)).isEqualTo("PENDIENTE");
            assertThat(intentos(r)).isEmpty();
        });
        assertThat(backoff(ferreteriaId, FACTURA_B)).as("cortar por tiempo no es un error")
                .containsEntry("fallas_seguidas", 0);
        assertThat(wsfe.solicitudes()).isEqualTo(1);

        job.pasada();

        assertThat(List.of(b1, b2, a1, o1)).allSatisfy(r -> assertThat(estado(r)).isEqualTo("AUTORIZADO"));
        assertThat(wsfe.solicitudes()).isEqualTo(4);
    }

    @Test
    @DisplayName("una serie que falla con algo inesperado no corta a las demás ni a la otra ferretería, y se recupera en la "
            + "pasada siguiente")
    void unaSerieQueFallaNoCortaALasDemas() {
        long b1 = pendienteB(ferreteriaId);
        long a1 = pendienteA(ferreteriaId);
        long o1 = pendienteB(otra);
        wsfe.lanzarEnElSiguienteFeCaeSolicitar(new IllegalStateException("un adaptador que no traduce su falla"));

        job(ferreteriaId, otra).pasada();

        assertThat(TenantContext.currentFerreteriaId()).isEmpty();
        assertThat(estado(b1)).as("no se sabe si llegó").isEqualTo("EN_CURSO");
        assertThat(estado(a1)).isEqualTo("AUTORIZADO");
        assertThat(estado(o1)).isEqualTo("AUTORIZADO");

        job(ferreteriaId, otra).pasada();

        assertThat(estado(b1)).isEqualTo("AUTORIZADO");
        assertThat(numero(b1)).isEqualTo(1L);
        assertThat(ultimoEnArca(ferreteriaId, FACTURA_B)).isEqualTo(1);
    }

    // ---- S7: la ventana de fechas ----

    @Test
    @DisplayName("un pendiente que ya no entra en la ventana de fechas queda FUERA_DE_FECHA sin mandarse, y el job no lo "
            + "vuelve a tomar")
    void unPendienteFueraDeLaVentanaQuedaFueraDeFechaYNoSeVuelveATomar() {
        long viejo = pendienteB(ferreteriaId);
        wsfe.fallas().programar(Falla.NO_LLEGA);
        job(ferreteriaId).pasada();
        assertThat(estado(viejo)).as("ARCA caída: queda pendiente").isEqualTo("PENDIENTE");

        reloj.avanzar(Duration.ofDays(6));
        job(ferreteriaId).pasada();

        assertThat(registro(viejo)).containsEntry("estado", "FUERA_DE_FECHA").containsEntry("numero", null);
        assertThat(intentos(viejo)).as("no se mandó otra vez").hasSize(1);
        assertThat(en(ferreteriaId, () -> pasos.seriesConTrabajo(reloj.instant()))).isEmpty();

        job(ferreteriaId).pasada();

        assertThat(estado(viejo)).isEqualTo("FUERA_DE_FECHA");
        assertThat(intentos(viejo)).hasSize(1);
        assertThat(wsfe.solicitudes()).isEqualTo(1);
    }

    // ---- S8: la batería de concurrencia ----

    @Test
    @DisplayName("el job y la venta sobre el mismo pendiente hacen un solo FECAESolicitar, tome la serie quien la tome")
    void elJobYLaVentaHacenUnSoloFeCaeSolicitar() throws Exception {
        // La venta primero: el job encuentra la serie tomada y la saltea.
        long ventaPrimero = venta(ferreteriaId);
        long r1 = registrarB(ventaPrimero);
        WsfeDePrueba.Pausa pausaDeLaVenta = wsfe.pausarElSiguienteFeCaeSolicitar();
        Future<Optional<EstadoFiscalVenta>> cobro = pool.submit(() -> en(ferreteriaId,
                () -> facturacion.autorizar(ventaPrimero)));
        pausaDeLaVenta.esperarQueLlegue();

        job(ferreteriaId).pasada();

        assertThat(wsfe.solicitudes()).isEqualTo(1);
        pausaDeLaVenta.soltar();
        cobro.get(TOPE_SEGUNDOS, TimeUnit.SECONDS);
        hasta(() -> "AUTORIZADO".equals(estado(r1)), "la venta autoriza su pendiente");
        assertThat(intentos(r1)).hasSize(1);
        assertThat(wsfe.solicitudes()).isEqualTo(1);

        // El job primero: la venta espera lo suyo y sale con el ticket provisorio, sin mandar otro.
        long ventaDespues = venta(ferreteriaId);
        long r2 = registrarB(ventaDespues);
        WsfeDePrueba.Pausa pausaDelJob = wsfe.pausarElSiguienteFeCaeSolicitar();
        ReintentoFiscalJob job = job(ferreteriaId);
        Future<?> pasada = pool.submit(() -> job.pasada());
        pausaDelJob.esperarQueLlegue();

        Optional<EstadoFiscalVenta> estadoDelCobro = en(ferreteriaId, () -> facturacion.autorizar(ventaDespues));

        assertThat(estadoDelCobro).get().extracting(EstadoFiscalVenta::estado).isEqualTo("EN_CURSO");
        assertThat(wsfe.solicitudes()).isEqualTo(2);
        pausaDelJob.soltar();
        pasada.get(TOPE_SEGUNDOS, TimeUnit.SECONDS);
        assertThat(estado(r2)).isEqualTo("AUTORIZADO");
        assertThat(numero(r2)).isEqualTo(2L);
        assertThat(intentos(r2)).hasSize(1);
        assertThat(wsfe.solicitudes()).isEqualTo(2);
        assertThat(ultimoEnArca(ferreteriaId, FACTURA_B)).isEqualTo(2);
    }

    @Test
    @DisplayName("dos instancias del job a la vez no duplican: cada registro sale una vez, con números consecutivos en "
            + "FIFO, sin 10016 ni deadlocks")
    void dosInstanciasDelJobNoDuplican() throws Exception {
        List<Long> b = new ArrayList<>();
        List<Long> a = new ArrayList<>();
        List<Long> deOtra = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            b.add(pendienteB(ferreteriaId));
            deOtra.add(pendienteB(otra));
        }
        for (int i = 0; i < 2; i++) {
            a.add(pendienteA(ferreteriaId));
        }
        ReintentoFiscalJob unaJvm = job(motor, politica, ferreteriaId, otra);
        ReintentoFiscalJob otraJvm = job(new MotorFiscal(pasos, wsfePorAmbiente, reloj, lease, presupuesto), politica,
                ferreteriaId, otra);
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocks = barrera.deadlocksAntes();

        Largada largada = new Largada(2);
        List<Future<?>> pasadas = new ArrayList<>();
        for (ReintentoFiscalJob job : List.of(unaJvm, otraJvm)) {
            pasadas.add(pool.submit(() -> {
                largada.listoYEsperar();
                job.pasada();
            }));
        }
        largada.esperarListos(Duration.ofSeconds(30));
        largada.largar();
        for (Future<?> pasada : pasadas) {
            pasada.get(TOPE_SEGUNDOS, TimeUnit.SECONDS);
        }

        List<Long> todos = new ArrayList<>(b);
        todos.addAll(a);
        todos.addAll(deOtra);
        assertThat(todos).allSatisfy(r -> {
            assertThat(estado(r)).isEqualTo("AUTORIZADO");
            assertThat(intentos(r)).singleElement().satisfies(i -> assertThat(i).containsEntry("estado", "AUTORIZADO"));
        });
        assertThat(b.stream().map(this::numero).toList()).containsExactly(1L, 2L, 3L);
        assertThat(a.stream().map(this::numero).toList()).containsExactly(1L, 2L);
        assertThat(deOtra.stream().map(this::numero).toList()).containsExactly(1L, 2L, 3L);
        assertThat(wsfe.solicitudes()).isEqualTo(todos.size());
        assertThat(ultimoEnArca(ferreteriaId, FACTURA_B)).isEqualTo(3);
        assertThat(ultimoEnArca(ferreteriaId, FACTURA_A)).isEqualTo(2);
        assertThat(ultimoEnArca(otra, FACTURA_B)).isEqualTo(3);
        assertThat(jdbc.queryForObject("select count(*) from serie_fiscal where ferreteria_id in (?, ?) "
                + "and (lease_dueno is not null or fallas_seguidas <> 0)", Integer.class, ferreteriaId, otra)).isZero();
        barrera.assertSinDeadlocks(deadlocks);
    }

    @Test
    @DisplayName("un apagado con un envío en vuelo: el job no toma otra serie, el apagado no lo espera, y lo que quedó en "
            + "vuelo lo recupera la pasada siguiente (en la otra JVM) sin duplicar")
    void unApagadoConUnEnvioEnVueloSeRecuperaEnLaPasadaSiguiente() throws Exception {
        long b1 = pendienteB(ferreteriaId);
        long b2 = pendienteB(ferreteriaId);
        long o1 = pendienteB(otra);
        // Con un tope de una hora: lo que corta a esta pasada es el apagado, no el tiempo.
        PoliticaDeReintento sinApuro = new PoliticaDeReintento(Duration.ofSeconds(60), Duration.ofHours(1),
                Duration.ofSeconds(60), Duration.ofMinutes(30), presupuesto);
        ProgramacionDelReintento laQueSeApaga = new ProgramacionDelReintento(job(motor, sinApuro, ferreteriaId, otra),
                Duration.ofMillis(10));
        WsfeDePrueba.Pausa enVuelo = wsfe.pausarElSiguienteFeCaeSolicitar();
        laQueSeApaga.start();
        enVuelo.esperarQueLlegue();

        CountDownLatch apagada = new CountDownLatch(1);
        laQueSeApaga.stop(apagada::countDown);
        assertThat(apagada.getCount()).as("la pasada sigue en vuelo").isEqualTo(1);
        assertThat(estado(b1)).isEqualTo("EN_CURSO");

        // La JVM vieja muere con la llamada en vuelo: su toma de la serie vence, y la pasada siguiente es de la nueva.
        reloj.avanzar(lease.plusSeconds(1));
        job(ferreteriaId).pasada();

        assertThat(registro(b1)).containsEntry("estado", "AUTORIZADO").containsEntry("numero", 1L);
        assertThat(registro(b1).get("cae")).isEqualTo(en(ferreteriaId, () -> caeEnArca(FACTURA_B, 1)));
        assertThat(registro(b2)).containsEntry("estado", "AUTORIZADO").containsEntry("numero", 2L);
        assertThat(ultimoEnArca(ferreteriaId, FACTURA_B)).isEqualTo(2);
        Object cae = registro(b1).get("cae");

        // El envío de la vieja llega tarde: ARCA lo rechaza (10016) y no pisa nada; y la vieja ya no toma otra serie.
        enVuelo.soltar();
        assertThat(apagada.await(TOPE_SEGUNDOS, TimeUnit.SECONDS)).as("terminó la pasada de la vieja").isTrue();
        assertThat(registro(b1)).containsEntry("estado", "AUTORIZADO").containsEntry("numero", 1L)
                .containsEntry("cae", cae);
        assertThat(ultimoEnArca(ferreteriaId, FACTURA_B)).isEqualTo(2);
        assertThat(estado(o1)).as("después del apagado, la vieja no tomó la otra ferretería").isEqualTo("PENDIENTE");
        assertThat(intentos(o1)).isEmpty();
        assertThat(wsfe.solicitudes()).isEqualTo(3);

        job(ferreteriaId, otra).pasada();
        assertThat(estado(o1)).isEqualTo("AUTORIZADO");
    }

    // ---- Ayudas ----

    /** El job armado a mano con los beans reales y esas ferreterías como las activas. */
    private ReintentoFiscalJob job(long... ferreterias) {
        return job(motor, politica, ferreterias);
    }

    private ReintentoFiscalJob job(MotorFiscal instancia, PoliticaDeReintento conPolitica, long... ferreterias) {
        FerreteriaRepository activas = mock(FerreteriaRepository.class);
        when(activas.idsActivas()).thenReturn(Arrays.stream(ferreterias).boxed().toList());
        return new ReintentoFiscalJob(activas, pasos, instancia, conPolitica, reloj);
    }

    /** Con esa ferretería en el {@code TenantContext} del hilo, y después sin ninguna. */
    private <T> T en(long ferreteria, Supplier<T> accion) {
        TenantContext.setCurrentFerreteriaId(ferreteria);
        try {
            return accion.get();
        } finally {
            TenantContext.clear();
        }
    }

    /** Una Factura B a Consumidor Final de una venta nueva de esa ferretería. */
    private long pendienteB(long ferreteria) {
        return en(ferreteria, () -> motor.registrar(new AltaFiscal(venta(ferreteria), ReceptorFiscal.sinCliente(),
                renglones())));
    }

    /** Una Factura A a un Responsable Inscripto, de una venta nueva de esa ferretería. */
    private long pendienteA(long ferreteria) {
        return en(ferreteria, () -> motor.registrar(new AltaFiscal(venta(ferreteria), new ReceptorFiscal(
                CondicionIvaReceptor.RESPONSABLE_INSCRIPTO, TipoDocumentoReceptor.CUIT, 30786013301L, "Corralón Sur SA",
                "Av. Colón 2000"), renglones())));
    }

    /** La Factura B de esa venta de la ferretería del test. */
    private long registrarB(long venta) {
        return en(ferreteriaId, () -> motor.registrar(new AltaFiscal(venta, ReceptorFiscal.sinCliente(), renglones())));
    }

    private static List<RenglonFiscal> renglones() {
        return List.of(new RenglonFiscal(Alicuota.IVA_21, new BigDecimal("121.00")));
    }

    private Map<String, Object> backoff(long ferreteria, int cbteTipo) {
        return jdbc.queryForMap("select fallas_seguidas, proximo_reintento from serie_fiscal where ferreteria_id = ? "
                + "and cbte_tipo = ?", ferreteria, cbteTipo);
    }

    private Instant proximoReintento(long ferreteria, int cbteTipo) {
        return en(ferreteria, () -> pasos.serie(pasos.asegurarSerie(Ambiente.SIMULADOR, 1, cbteTipo))
                .getProximoReintento());
    }

    /** El último autorizado de esa serie de la ferretería del lado del simulador (que también es de la ferretería). */
    private long ultimoEnArca(long ferreteria, int cbteTipo) {
        long cuitEmisor = ferreteria == ferreteriaId ? cuit : cuitOtra;
        return en(ferreteria, () -> simulador.feCompUltimoAutorizado(new Auth(Ambiente.SIMULADOR, cuitEmisor), 1, cbteTipo)
                .cbteNro());
    }

    /** Los registros de las dos ferreterías en el orden en que se mandaron a ARCA (el de sus intentos). */
    private List<Long> ordenDeEnvio() {
        return jdbc.queryForList("select comprobante_fiscal_id from intento_fiscal where ferreteria_id in (?, ?) "
                + "order by id", Long.class, ferreteriaId, otra).stream().distinct().collect(Collectors.toList());
    }

    private static void hasta(BooleanSupplier condicion, String que) throws InterruptedException {
        long tope = System.nanoTime() + TimeUnit.SECONDS.toNanos(TOPE_SEGUNDOS);
        while (!condicion.getAsBoolean()) {
            assertThat(System.nanoTime()).as("venció la espera: " + que).isLessThan(tope);
            Thread.sleep(50);
        }
    }
}
