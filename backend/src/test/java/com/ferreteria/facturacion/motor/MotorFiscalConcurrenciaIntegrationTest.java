package com.ferreteria.facturacion.motor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.facturacion.model.Alicuota;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.model.ReceptorFiscal;
import com.ferreteria.facturacion.model.RenglonFiscal;
import com.ferreteria.facturacion.repository.ComprobanteFiscalRepository;
import com.ferreteria.facturacion.wsfe.Obs;
import com.ferreteria.facturacion.wsfe.PedidoDePrueba;
import com.ferreteria.facturacion.wsfe.Resultado;
import com.ferreteria.facturacion.wsfe.WsfeConFallas.Falla;
import com.ferreteria.support.BarreraDeLocks;
import com.ferreteria.support.BarreraDeLocks.Largada;
import com.ferreteria.tenant.TenantContext;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * La batería de concurrencia del motor (aceptación de S5) contra MySQL real y con hilos de verdad, molde de
 * {@code VentaConcurrenciaIntegrationTest}: autorizaciones simultáneas, el corte después de guardar el intento, el CAE
 * huérfano, el rechazo, dos instancias con leases distintos, el pool de conexiones durante la llamada y el aislamiento entre
 * ferreterías. Sin tiempos: los hilos arrancan con una largada común y las llamadas se detienen con una pausa del puerto
 * ({@link WsfeDePrueba.Pausa}) hasta que el test las suelta. Todo test con hilos exige delta de deadlocks 0.
 */
class MotorFiscalConcurrenciaIntegrationTest extends MotorFiscalTestBase {

    @Autowired
    PasosFiscales pasos;
    @Autowired
    WsfePorAmbiente wsfePorAmbiente;
    @Autowired
    DataSource dataSource;
    @Autowired
    ComprobanteFiscalRepository registros;
    @Value("${app.facturacion.motor.lease}")
    Duration lease;
    @Value("${app.facturacion.motor.presupuesto-llamada}")
    Duration presupuesto;

    private BarreraDeLocks barrera;

    @BeforeEach
    void barrera() {
        barrera = new BarreraDeLocks(jdbcRoot());
    }

    /** Otra instancia del motor (otra JVM): los mismos pasos y el mismo puerto, con su propia toma de la serie. */
    private MotorFiscal otraInstancia() {
        return new MotorFiscal(pasos, wsfePorAmbiente, reloj, lease, presupuesto);
    }

    /** Corre las tareas en hilos con la ferretería en su contexto, arrancando todas juntas, y devuelve sus resultados. */
    private <T> List<T> enParalelo(long ferreteria, List<Callable<T>> tareas) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tareas.size());
        Largada largada = new Largada(tareas.size());
        try {
            List<Future<T>> futuros = new ArrayList<>();
            for (Callable<T> tarea : tareas) {
                futuros.add(pool.submit(() -> {
                    TenantContext.setCurrentFerreteriaId(ferreteria);
                    try {
                        largada.listoYEsperar();
                        return tarea.call();
                    } finally {
                        TenantContext.clear();
                    }
                }));
            }
            largada.esperarListos(Duration.ofSeconds(30));
            largada.largar();
            List<T> resultados = new ArrayList<>();
            for (Future<T> futuro : futuros) {
                resultados.add(futuro.get(60, TimeUnit.SECONDS));
            }
            return resultados;
        } finally {
            pool.shutdownNow();
        }
    }

    /** Una atención en otro hilo, con la ferretería del test. */
    private Future<Atencion> atenderEnOtroHilo(ExecutorService pool, MotorFiscal instancia) {
        long ferreteria = ferreteriaId;
        return pool.submit(() -> {
            TenantContext.setCurrentFerreteriaId(ferreteria);
            try {
                return instancia.atender(Ambiente.SIMULADOR, 1, FACTURA_B);
            } finally {
                TenantContext.clear();
            }
        });
    }

    private List<Long> numerosEnOrden(List<Long> ids) {
        return ids.stream().map(this::numero).toList();
    }

    private int intentosConRechazo10016() {
        return jdbc.queryForObject("select count(*) from intento_fiscal where ferreteria_id = ? "
                + "and (estado = 'RECHAZADO' or respuesta like '%10016%')", Integer.class, ferreteriaId);
    }

    private String leaseDeLaSerie() {
        return jdbc.queryForObject("select lease_dueno from serie_fiscal where ferreteria_id = ? and cbte_tipo = 6",
                String.class, ferreteriaId);
    }

    @Test
    @DisplayName("autorizaciones simultáneas en una serie dan números consecutivos en orden FIFO, sin 10016")
    void autorizacionesSimultaneas() throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            ids.add(registrarFacturaB());
        }
        long deadlocks = barrera.deadlocksAntes();

        List<Callable<Atencion>> atenciones = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            atenciones.add(() -> motor.atender(Ambiente.SIMULADOR, 1, FACTURA_B));
        }
        List<Atencion> resultados = enParalelo(ferreteriaId, atenciones);

        assertThat(resultados).containsOnly(Atencion.TERMINADA, Atencion.OCUPADA).contains(Atencion.TERMINADA);
        assertThat(numerosEnOrden(ids)).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        assertThat(ids).allSatisfy(id -> assertThat(estado(id)).isEqualTo("AUTORIZADO"));
        assertThat(jdbc.queryForList("select estado from intento_fiscal where ferreteria_id = ?", String.class,
                ferreteriaId)).hasSize(8).containsOnly("AUTORIZADO");
        assertThat(intentosConRechazo10016()).isZero();
        assertThat(ultimoEnArca(FACTURA_B)).isEqualTo(8);
        assertThat(leaseDeLaSerie()).isNull();
        barrera.assertSinDeadlocks(deadlocks);
    }

    @Test
    @DisplayName("altas y atenciones simultáneas: cada registro recibe un número distinto, sin huecos ni 10016")
    void altasYAtencionesSimultaneas() throws Exception {
        List<Long> ventas = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            ventas.add(venta(ferreteriaId));
        }
        long deadlocks = barrera.deadlocksAntes();

        List<Callable<Long>> tareas = new ArrayList<>();
        for (long venta : ventas) {
            tareas.add(() -> {
                long id = motor.registrar(new AltaFiscal(venta, ReceptorFiscal.sinCliente(),
                        List.of(new RenglonFiscal(Alicuota.IVA_21, new BigDecimal("121.00")))));
                motor.atender(Ambiente.SIMULADOR, 1, FACTURA_B);
                return id;
            });
        }
        List<Long> ids = enParalelo(ferreteriaId, tareas);
        // Un alta que se confirmó mientras otro hilo soltaba la serie queda para la próxima atención (el job, rama 7).
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);

        assertThat(numerosEnOrden(ids)).containsExactlyInAnyOrder(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(ids).allSatisfy(id -> assertThat(estado(id)).isEqualTo("AUTORIZADO"));
        assertThat(intentosConRechazo10016()).isZero();
        assertThat(ultimoEnArca(FACTURA_B)).isEqualTo(6);
        barrera.assertSinDeadlocks(deadlocks);
    }

    @Test
    @DisplayName("un corte después de guardar el intento (ARCA ya autorizó) se recupera y adopta el CAE sin duplicar")
    void corteDespuesDeAutorizar() {
        long id = registrarFacturaB();
        wsfe.fallas().programar(Falla.TIMEOUT_DESPUES_DE_AUTORIZAR);

        assertThat(atenderB()).isEqualTo(Atencion.SIN_RESPUESTA);
        assertThat(estado(id)).isEqualTo("EN_VERIFICACION");
        assertThat(ultimoEnArca(FACTURA_B)).as("ARCA lo autorizó").isEqualTo(1);

        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);

        assertThat(estado(id)).isEqualTo("AUTORIZADO");
        assertThat(numero(id)).isEqualTo(1);
        assertThat(registro(id).get("cae")).isEqualTo(caeEnArca(FACTURA_B, 1));
        assertThat(intentos(id)).singleElement().satisfies(i -> {
            assertThat(i).containsEntry("estado", "AUTORIZADO").containsEntry("resultado", "A");
            assertThat((String) i.get("respuesta_verificacion")).contains("\"ultimoAutorizado\":1");
        });
        assertThat(wsfe.solicitudes()).as("no se reenvió").isEqualTo(1);
        assertThat(ultimoEnArca(FACTURA_B)).isEqualTo(1);
        assertThat(caeEnArca(FACTURA_B, 2)).isNull();
    }

    @Test
    @DisplayName("un CAE huérfano que no coincide con lo enviado deja CAE_NO_COINCIDE, sin renumerar, y la serie trabada")
    void caeHuerfanoQueNoCoincide() {
        long id = registrarFacturaB();
        wsfe.fallas().programar(Falla.TIMEOUT_ANTES_DE_AUTORIZAR);
        assertThat(atenderB()).isEqualTo(Atencion.SIN_RESPUESTA);
        // Otro sistema usó el número 1 con otro importe.
        assertThat(autorizarAfuera(PedidoDePrueba.facturaB(HOY).numero(1).importes("242.00", "200.00", "42.00")
                .alicuotas(PedidoDePrueba.alicuota(5, "200.00", "42.00"))).feCabResp().resultado())
                .isEqualTo(Resultado.A);
        long detras = registrarFacturaB();

        assertThat(atenderB()).isEqualTo(Atencion.TRABADA);

        assertThat(estado(id)).isEqualTo("CAE_NO_COINCIDE");
        assertThat(numero(id)).isEqualTo(1);
        assertThat(registro(id).get("cae")).isNull();
        assertThat(intentos(id)).singleElement().satisfies(i -> {
            assertThat(i).containsEntry("estado", "ALERTA").containsEntry("numero", 1L);
            assertThat((String) i.get("respuesta_verificacion")).contains("\"ultimoAutorizado\":1", "242.00");
        });
        assertThat(atenderB()).isEqualTo(Atencion.TRABADA);
        assertThat(estado(detras)).isEqualTo("PENDIENTE");
        assertThat(ultimoEnArca(FACTURA_B)).isEqualTo(1);
    }

    @Test
    @DisplayName("un rechazo libera el número, y el siguiente lo usa")
    void unRechazoLiberaElNumero() {
        long rechazado = registrarFacturaB();
        long siguiente = registrarFacturaB();
        Obs obs = new Obs(10063, "El receptor no es un contribuyente activo.");
        wsfe.fallas().rechazarConObservaciones(obs);

        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);

        assertThat(registro(rechazado)).containsEntry("estado", "RECHAZADO").containsEntry("numero", null)
                .containsEntry("resultado", "R").containsEntry("cae", null);
        assertThat((String) registro(rechazado).get("observaciones")).contains("10063",
                "El receptor no es un contribuyente activo.");
        assertThat(intentos(rechazado)).singleElement().satisfies(i -> assertThat(i)
                .containsEntry("estado", "RECHAZADO").containsEntry("numero", 1L));
        assertThat(estado(siguiente)).isEqualTo("AUTORIZADO");
        assertThat(numero(siguiente)).isEqualTo(1);
        assertThat(intentos(siguiente)).singleElement().satisfies(i -> assertThat(i).containsEntry("numero", 1L));
        assertThat(ultimoEnArca(FACTURA_B)).isEqualTo(1);
    }

    @Test
    @DisplayName("dos instancias con leases distintos nunca usan el mismo número: la segunda no toca la serie tomada")
    void dosInstanciasNuncaUsanElMismoNumero() throws Exception {
        long primero = registrarFacturaB();
        long segundo = registrarFacturaB();
        MotorFiscal b = otraInstancia();
        WsfeDePrueba.Pausa pausa = wsfe.pausarElSiguienteFeCaeSolicitar();
        long deadlocks = barrera.deadlocksAntes();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Atencion> a = atenderEnOtroHilo(pool, motor);
            pausa.esperarQueLlegue();
            String leaseDeA = leaseDeLaSerie();

            assertThat(atenderEnOtroHilo(pool, b).get(60, TimeUnit.SECONDS)).isEqualTo(Atencion.OCUPADA);
            assertThat(leaseDeLaSerie()).as("B no tocó la toma de A").isEqualTo(leaseDeA).isNotNull();
            assertThat(estado(primero)).isEqualTo("EN_CURSO");
            assertThat(estado(segundo)).isEqualTo("PENDIENTE");

            pausa.soltar();
            assertThat(a.get(60, TimeUnit.SECONDS)).isEqualTo(Atencion.TERMINADA);
        } finally {
            pool.shutdownNow();
        }

        assertThat(numerosEnOrden(List.of(primero, segundo))).containsExactly(1L, 2L);
        assertThat(intentosConRechazo10016()).isZero();
        assertThat(leaseDeLaSerie()).isNull();
        barrera.assertSinDeadlocks(deadlocks);
    }

    @Test
    @DisplayName("si la toma vence con la llamada en vuelo, la otra instancia verifica y reenvía, y el resultado tardío no pisa nada")
    void resultadoTardioDeUnaTomaVencida() throws Exception {
        long id = registrarFacturaB();
        MotorFiscal b = otraInstancia();
        WsfeDePrueba.Pausa pausa = wsfe.pausarElSiguienteFeCaeSolicitar();
        long deadlocks = barrera.deadlocksAntes();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Atencion> a = atenderEnOtroHilo(pool, motor);
            pausa.esperarQueLlegue();
            // La llamada de A pasó el presupuesto: su toma venció.
            reloj.avanzar(lease.plusSeconds(1));

            assertThat(atenderEnOtroHilo(pool, b).get(60, TimeUnit.SECONDS)).isEqualTo(Atencion.TERMINADA);
            assertThat(estado(id)).isEqualTo("AUTORIZADO");
            String cae = (String) registro(id).get("cae");

            pausa.soltar();
            assertThat(a.get(60, TimeUnit.SECONDS)).as("ARCA rechaza el pedido de A con 10016; no se guarda")
                    .isEqualTo(Atencion.SERIE_PERDIDA);
            assertThat(registro(id)).containsEntry("estado", "AUTORIZADO").containsEntry("numero", 1L)
                    .containsEntry("cae", cae);
        } finally {
            pool.shutdownNow();
        }

        assertThat(intentos(id)).extracting(i -> i.get("estado"), i -> i.get("numero"))
                .containsExactly(org.assertj.core.groups.Tuple.tuple("NO_LLEGO", 1L),
                        org.assertj.core.groups.Tuple.tuple("AUTORIZADO", 1L));
        assertThat(ultimoEnArca(FACTURA_B)).isEqualTo(1);
        assertThat(caeEnArca(FACTURA_B, 1)).isEqualTo(registro(id).get("cae"));
        barrera.assertSinDeadlocks(deadlocks);
    }

    @Test
    @DisplayName("ninguna conexión del pool queda tomada por el motor durante la llamada a ARCA")
    void ningunaConexionTomadaDuranteLaLlamada() throws Exception {
        long id = registrarFacturaB();
        HikariDataSource hikari = dataSource.unwrap(HikariDataSource.class);
        WsfeDePrueba.Pausa pausa = wsfe.pausarElSiguienteFeCaeSolicitar();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Atencion> atencion = atenderEnOtroHilo(pool, motor);
            pausa.esperarQueLlegue();

            assertThat(estado(id)).as("el intento ya está guardado").isEqualTo("EN_CURSO");
            assertThat(hikari.getHikariPoolMXBean().getActiveConnections()).as("conexiones activas durante la llamada")
                    .isZero();

            pausa.soltar();
            assertThat(atencion.get(60, TimeUnit.SECONDS)).isEqualTo(Atencion.TERMINADA);
        } finally {
            pool.shutdownNow();
        }
        assertThat(estado(id)).isEqualTo("AUTORIZADO");
    }

    @Test
    @DisplayName("una ferretería no ve ni toca los registros de otra, aunque las series tengan la misma clave")
    void unaFerreteriaNoVeNiTocaLosRegistrosDeOtra() {
        long deOtra;
        long ventaDeOtra;
        long otra = ferreteriaConFacturacion(cuitNuevo());
        TenantContext.setCurrentFerreteriaId(otra);
        try {
            ventaDeOtra = venta(otra);
            deOtra = motor.registrar(new AltaFiscal(ventaDeOtra, ReceptorFiscal.sinCliente(),
                    List.of(new RenglonFiscal(Alicuota.IVA_21, new BigDecimal("121.00")))));
        } finally {
            TenantContext.setCurrentFerreteriaId(ferreteriaId);
        }
        long propio = registrarFacturaB();

        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);

        assertThat(estado(propio)).isEqualTo("AUTORIZADO");
        assertThat(estado(deOtra)).isEqualTo("PENDIENTE");
        assertThat(intentos(deOtra)).isEmpty();
        assertThat(registros.findScoped(deOtra)).isEmpty();
        assertThatThrownBy(() -> motor.registrar(new AltaFiscal(ventaDeOtra, ReceptorFiscal.sinCliente(),
                List.of(new RenglonFiscal(Alicuota.IVA_21, new BigDecimal("121.00"))))))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("select count(*) from comprobante_fiscal where comprobante_id = ?", Integer.class,
                ventaDeOtra)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from serie_fiscal where ferreteria_id = ?", Integer.class, otra))
                .isZero();
    }
}
