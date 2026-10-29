package com.ferreteria.facturacion.motor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.common.exception.ReglaDeNegocioException;
import com.ferreteria.facturacion.api.ConfigArcaParaPlataforma;
import com.ferreteria.facturacion.model.Alicuota;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.model.CondicionIvaReceptor;
import com.ferreteria.facturacion.model.ReceptorFiscal;
import com.ferreteria.facturacion.model.RenglonFiscal;
import com.ferreteria.facturacion.model.TipoDocumentoReceptor;
import com.ferreteria.facturacion.wsfe.Auth;
import com.ferreteria.facturacion.wsfe.FeCaeReq;
import com.ferreteria.facturacion.wsfe.FeCaeResponse;
import com.ferreteria.facturacion.wsfe.FeCompConsultarResponse;
import com.ferreteria.facturacion.wsfe.FeCompUltimoAutorizadoResponse;
import com.ferreteria.facturacion.wsfe.Obs;
import com.ferreteria.facturacion.wsfe.PedidoDePrueba;
import com.ferreteria.facturacion.wsfe.ServicioWsfe;
import com.ferreteria.facturacion.wsfe.WsfeConFallas.Falla;
import com.ferreteria.support.BarreraDeLocks;
import com.ferreteria.support.BarreraDeLocks.Hito;
import com.ferreteria.support.BarreraDeLocks.Largada;
import com.ferreteria.tenant.TenantContext;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Bordes del motor fiscal que encontró el verificador de T6: el lease frente al presupuesto, la recuperación sin conexión
 * tomada, los pasos descartados con rollback completo, la CbteFch del día de Buenos Aires, el aislamiento entre
 * ferreterías y el alta (FOR SHARE) contra el panel del super admin (FOR UPDATE).
 */
class MotorFiscalBordesIntegrationTest extends MotorFiscalTestBase {

    @Autowired PasosFiscales pasos;
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transacciones;
    @Autowired ConfigArcaParaPlataforma panel;
    @Value("${app.facturacion.motor.lease}") Duration lease;
    @Value("${app.facturacion.motor.presupuesto-llamada}") Duration presupuesto;

    BarreraDeLocks barrera;

    @BeforeEach
    void barrera() {
        barrera = new BarreraDeLocks(jdbcRoot());
    }

    // ---------- utilidades ----------

    static final class Freno {
        final Hito llego = new Hito();
        final Hito soltada = new Hito();
    }

    /** El puerto de prueba con frenos también en FECompUltimoAutorizado y FECompConsultar. */
    static final class PuertoConFrenos implements ServicioWsfe {
        final ServicioWsfe real;
        final Queue<Freno> ultimo = new ConcurrentLinkedQueue<>();
        final Queue<Freno> consultar = new ConcurrentLinkedQueue<>();

        PuertoConFrenos(ServicioWsfe real) { this.real = real; }

        Freno frenarUltimo() { Freno f = new Freno(); ultimo.add(f); return f; }
        Freno frenarConsultar() { Freno f = new Freno(); consultar.add(f); return f; }

        private static void frenar(Queue<Freno> q) {
            Freno f = q.poll();
            if (f != null) { f.llego.avisar(); f.soltada.esperar(); }
        }

        @Override public FeCompUltimoAutorizadoResponse feCompUltimoAutorizado(Auth a, int pv, int t) {
            frenar(ultimo); return real.feCompUltimoAutorizado(a, pv, t);
        }
        @Override public FeCaeResponse feCaeSolicitar(Auth a, FeCaeReq r) { return real.feCaeSolicitar(a, r); }
        @Override public FeCompConsultarResponse feCompConsultar(Auth a, int t, int pv, long n) {
            frenar(consultar); return real.feCompConsultar(a, t, pv, n);
        }
    }

    MotorFiscal instancia(ServicioWsfe puerto) {
        return new MotorFiscal(pasos, new WsfePorAmbiente(Map.of(Ambiente.SIMULADOR, puerto)), reloj, lease, presupuesto);
    }

    Future<Atencion> atenderEnOtroHilo(ExecutorService pool, MotorFiscal m, long ferreteria, int tipo) {
        return pool.submit(() -> {
            TenantContext.setCurrentFerreteriaId(ferreteria);
            try { return m.atender(Ambiente.SIMULADOR, 1, tipo); } finally { TenantContext.clear(); }
        });
    }

    Future<Atencion> atenderEnOtroHilo(ExecutorService pool, MotorFiscal m) {
        return atenderEnOtroHilo(pool, m, ferreteriaId, FACTURA_B);
    }

    <T> Future<T> enOtroHilo(ExecutorService pool, long ferreteria, Callable<T> c) {
        return pool.submit(() -> {
            TenantContext.setCurrentFerreteriaId(ferreteria);
            try { return c.call(); } finally { TenantContext.clear(); }
        });
    }

    String leaseDe(long f, int tipo) {
        List<String> l = jdbc.queryForList("select lease_dueno from serie_fiscal where ferreteria_id = ? and cbte_tipo = ?",
                String.class, f, tipo);
        return l.isEmpty() ? null : l.get(0);
    }

    Long ultimoDeLaSerie(long f, int tipo) {
        return jdbc.queryForObject("select ultimo_autorizado from serie_fiscal where ferreteria_id = ? and cbte_tipo = ?",
                Long.class, f, tipo);
    }

    int activas() throws Exception {
        return dataSource.unwrap(HikariDataSource.class).getHikariPoolMXBean().getActiveConnections();
    }

    int abiertosDeLaFerreteria() {
        return jdbc.queryForObject("select count(*) from intento_fiscal where ferreteria_id = ? and estado in "
                + "('ABIERTO','EN_VERIFICACION','ALERTA')", Integer.class, ferreteriaId);
    }

    static Instant ba(int y, int m, int d, int h, int min) {
        return LocalDateTime.of(y, m, d, h, min).atZone(ZONA).toInstant();
    }

    // ---------- P1: lease ----------

    @Test
    void p1_leaseNoVenceConUnaLlamadaQueTardaMasQueElPresupuestoPeroMenosQueElLease() throws Exception {
        long id = registrarFacturaB();
        MotorFiscal b = instancia(wsfe);
        WsfeDePrueba.Pausa pausa = wsfe.pausarElSiguienteFeCaeSolicitar();
        long dl = barrera.deadlocksAntes();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Atencion> a = atenderEnOtroHilo(pool, motor);
            pausa.esperarQueLlegue();
            String leaseA = leaseDe(ferreteriaId, FACTURA_B);
            reloj.avanzar(lease.minusSeconds(1)); // 179 s: muy por encima del presupuesto (60 s)
            assertThat(atenderEnOtroHilo(pool, b).get(60, TimeUnit.SECONDS)).isEqualTo(Atencion.OCUPADA);
            assertThat(leaseDe(ferreteriaId, FACTURA_B)).isEqualTo(leaseA);
            assertThat(intentos(id)).hasSize(1);
            assertThat(estado(id)).isEqualTo("EN_CURSO");
            pausa.soltar();
            assertThat(a.get(60, TimeUnit.SECONDS)).isEqualTo(Atencion.TERMINADA);
        } finally {
            pool.shutdownNow();
        }
        assertThat(numero(id)).isEqualTo(1);
        assertThat(wsfe.solicitudes()).isEqualTo(1);
        assertThat(leaseDe(ferreteriaId, FACTURA_B)).isNull();
        barrera.assertSinDeadlocks(dl);
    }

    // ---------- P2: incierto con ARCA que autorizó ----------

    @Test
    void p2_inciertoAutorizadoSeAdoptaYLaColaSigueSinDuplicar() {
        long r1 = registrarFacturaB();
        long r2 = registrarFacturaB();
        long r3 = registrarFacturaB();
        wsfe.fallas().programar(Falla.TIMEOUT_DESPUES_DE_AUTORIZAR);
        assertThat(atenderB()).isEqualTo(Atencion.SIN_RESPUESTA);
        assertThat(estado(r1)).isEqualTo("EN_VERIFICACION");
        assertThat(estado(r2)).isEqualTo("PENDIENTE");
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        assertThat(numerosDe(r1, r2, r3)).containsExactly(1L, 2L, 3L);
        assertThat(registro(r1).get("cae")).isEqualTo(caeEnArca(FACTURA_B, 1));
        assertThat(wsfe.solicitudes()).isEqualTo(3);
        assertThat(ultimoEnArca(FACTURA_B)).isEqualTo(3);
        assertThat(intentos(r1)).hasSize(1);
        assertThat(abiertosDeLaFerreteria()).isZero();
    }

    @Test
    void p2b_facturaAConDosAlicuotasY502DespuesDeAutorizarSeAdopta() {
        ReceptorFiscal ri = new ReceptorFiscal(CondicionIvaReceptor.RESPONSABLE_INSCRIPTO, TipoDocumentoReceptor.CUIT,
                30786013301L, "Corralón Sur SA", null);
        long r = motor.registrar(new AltaFiscal(venta(ferreteriaId), ri, List.of(
                new RenglonFiscal(Alicuota.IVA_21, new BigDecimal("100.00")),
                new RenglonFiscal(Alicuota.IVA_10_5, new BigDecimal("50.00")),
                new RenglonFiscal(Alicuota.IVA_21, new BigDecimal("33.33")))));
        wsfe.fallas().programar(Falla.ERROR_502_DESPUES_DE_AUTORIZAR);
        assertThat(motor.atender(Ambiente.SIMULADOR, 1, FACTURA_A)).isEqualTo(Atencion.SIN_RESPUESTA);
        assertThat(motor.atender(Ambiente.SIMULADOR, 1, FACTURA_A)).isEqualTo(Atencion.TERMINADA);
        assertThat(estado(r)).isEqualTo("AUTORIZADO");
        assertThat(numero(r)).isEqualTo(1);
        assertThat(registro(r).get("cae")).isEqualTo(caeEnArca(FACTURA_A, 1));
        assertThat(wsfe.solicitudes()).isEqualTo(1);
    }

    List<Long> numerosDe(long... ids) {
        List<Long> l = new ArrayList<>();
        for (long id : ids) { l.add(numero(id)); }
        return l;
    }

    // ---------- P3: no llegó ----------

    @Test
    void p3_noLlegoLiberaElNumeroYLaColaSigueFifo() {
        long r1 = registrarFacturaB();
        long r2 = registrarFacturaB();
        wsfe.fallas().programar(Falla.NO_LLEGA);
        assertThat(atenderB()).isEqualTo(Atencion.SIN_RESPUESTA);
        assertThat(registro(r1)).containsEntry("estado", "PENDIENTE").containsEntry("numero", null);
        assertThat(intentos(r1)).singleElement().satisfies(i -> assertThat(i).containsEntry("estado", "NO_LLEGO")
                .containsEntry("numero", 1L));
        assertThat(ultimoEnArca(FACTURA_B)).isZero();
        assertThat(leaseDe(ferreteriaId, FACTURA_B)).isNull();
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        assertThat(numerosDe(r1, r2)).containsExactly(1L, 2L);
        assertThat(intentos(r1)).extracting(i -> i.get("estado")).containsExactly("NO_LLEGO", "AUTORIZADO");
    }

    // ---------- P4: rechazo ----------

    @Test
    void p4_rechazoLiberaFifoYNadaQuedaTrabado() {
        long r1 = registrarFacturaB();
        long r2 = registrarFacturaB();
        long r3 = registrarFacturaB();
        wsfe.fallas().rechazarConObservaciones(new Obs(10063, "x"));
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        assertThat(registro(r1)).containsEntry("estado", "RECHAZADO").containsEntry("numero", null);
        assertThat(numerosDe(r2, r3)).containsExactly(1L, 2L);
        assertThat(abiertosDeLaFerreteria()).isZero();
        assertThat(leaseDe(ferreteriaId, FACTURA_B)).isNull();
        long r4 = registrarFacturaB();
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        assertThat(numero(r4)).isEqualTo(3);
    }

    // ---------- P5: alertas ----------

    @Test
    void p5_caeNoCoincideTrabaSinRenumerarNiLlamar() {
        long r1 = registrarFacturaB();
        wsfe.fallas().programar(Falla.TIMEOUT_ANTES_DE_AUTORIZAR);
        assertThat(atenderB()).isEqualTo(Atencion.SIN_RESPUESTA);
        autorizarAfuera(PedidoDePrueba.facturaB(HOY).numero(1).importes("242.00", "200.00", "42.00")
                .alicuotas(PedidoDePrueba.alicuota(5, "200.00", "42.00")));
        long r2 = registrarFacturaB();
        for (int i = 0; i < 3; i++) {
            assertThat(atenderB()).isEqualTo(Atencion.TRABADA);
        }
        assertThat(wsfe.solicitudes()).isEqualTo(1);
        assertThat(registro(r1)).containsEntry("estado", "CAE_NO_COINCIDE").containsEntry("numero", 1L);
        assertThat(registro(r2)).containsEntry("estado", "PENDIENTE").containsEntry("numero", null);
        assertThat(ultimoDeLaSerie(ferreteriaId, FACTURA_B)).isZero();
        assertThat(leaseDe(ferreteriaId, FACTURA_B)).isNull();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void p5b_serieDesincronizadaTrabaSinRenumerar(CapturedOutput salida) {
        long r0 = registrarFacturaB();
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        // la caché de la serie va adelante de ARCA (ARCA en 1, la serie dice 5)
        jdbc.update("update serie_fiscal set ultimo_autorizado = 5 where ferreteria_id = ? and cbte_tipo = 6", ferreteriaId);
        long r1 = registrarFacturaB();
        wsfe.fallas().programar(Falla.TIMEOUT_ANTES_DE_AUTORIZAR);
        assertThat(atenderB()).isEqualTo(Atencion.SIN_RESPUESTA);
        assertThat(numero(r1)).isEqualTo(6);
        long r2 = registrarFacturaB();
        assertThat(atenderB()).isEqualTo(Atencion.TRABADA);
        assertThat(atenderB()).isEqualTo(Atencion.TRABADA);
        assertThat(registro(r1)).containsEntry("estado", "SERIE_DESINCRONIZADA").containsEntry("numero", 6L);
        assertThat(registro(r2)).containsEntry("estado", "PENDIENTE");
        assertThat(wsfe.solicitudes()).isEqualTo(2);
        assertThat(numero(r0)).isEqualTo(1);
        // S13 (rama 7): la alerta queda en el log una sola vez, al pasar el registro; las atenciones trabadas no la repiten.
        assertThat(salida.getOut().split("ALERTA ARCA", -1)).hasSize(2);
        assertThat(salida).contains("SERIE_DESINCRONIZADA");
    }

    // ---------- P6: CbteFch ----------

    @Test
    void p6a_ventaA2359DelUltimoDiaDelMesSeFacturaConEseDiaHastaN5() {
        reloj.fijar(ba(2026, 10, 31, 23, 59));
        long r = registrarFacturaB();
        reloj.fijar(ba(2026, 11, 5, 9, 0));
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        assertThat(registro(r)).containsEntry("estado", "AUTORIZADO");
        assertThat(registro(r).get("cbte_fch").toString()).isEqualTo("2026-10-31");
    }

    @Test
    void p6b_ventaA2359DelUltimoDiaDelMesEsFueraDeFechaEnN6() {
        reloj.fijar(ba(2026, 10, 31, 23, 59));
        long r = registrarFacturaB();
        reloj.fijar(ba(2026, 11, 6, 0, 1));
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        assertThat(registro(r)).containsEntry("estado", "FUERA_DE_FECHA").containsEntry("numero", null);
        assertThat(intentos(r)).isEmpty();
        assertThat(wsfe.solicitudes()).isZero();
    }

    @Test
    void p6c_ventaA2330EnBuenosAiresEsElDiaDeBuenosAiresNoElDeUtc() {
        reloj.fijar(ba(2026, 10, 22, 23, 30)); // 2026-10-23T02:30Z
        long r = registrarFacturaB();
        reloj.fijar(ba(2026, 10, 28, 12, 0));
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        assertThat(estado(r)).isEqualTo("FUERA_DE_FECHA");
    }

    @Test
    void p6d_cbteFchPosteriorDeOtroMesEsFueraDeFecha() {
        reloj.fijar(ba(2026, 11, 1, 0, 30));
        long r = registrarFacturaB();
        reloj.fijar(ba(2026, 10, 31, 22, 0));
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        assertThat(estado(r)).isEqualTo("FUERA_DE_FECHA");
        assertThat(wsfe.solicitudes()).isZero();
    }

    @Test
    void p6e_cbteFchPosteriorDelMismoMesSeAutoriza() {
        reloj.fijar(ba(2026, 10, 29, 10, 0));
        long r = registrarFacturaB();
        reloj.fijar(ba(2026, 10, 28, 10, 0));
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        assertThat(estado(r)).isEqualTo("AUTORIZADO");
    }

    /** S5: el reenvío por N−1 también pasa por la ventana; de un mes al otro tampoco se reenvía. */
    @Test
    void p6f_reenvioPorNMenosUnoConLaCbteFchFueraDeLaVentana() {
        long r = registrarFacturaB();
        wsfe.fallas().programar(Falla.TIMEOUT_ANTES_DE_AUTORIZAR);
        assertThat(atenderB()).isEqualTo(Atencion.SIN_RESPUESTA);
        reloj.fijar(ba(2026, 11, 3, 10, 0)); // 6 días después
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        assertThat(registro(r)).containsEntry("estado", "FUERA_DE_FECHA").containsEntry("numero", null);
        assertThat(wsfe.solicitudes()).as("no se reenvía una CbteFch que no entra").isEqualTo(1);
        assertThat(abiertosDeLaFerreteria()).isZero();
    }

    // ---------- P7: aislamiento ----------

    @Test
    void p7_laTomaDeUnaFerreteriaNoFrenaNiTocaLaDeOtra() throws Exception {
        long otra = ferreteriaConFacturacion(cuitNuevo());
        TenantContext.setCurrentFerreteriaId(otra);
        long deOtra = motor.registrar(new AltaFiscal(venta(otra), ReceptorFiscal.sinCliente(),
                List.of(new RenglonFiscal(Alicuota.IVA_21, new BigDecimal("121.00")))));
        TenantContext.setCurrentFerreteriaId(ferreteriaId);
        long propio = registrarFacturaB();
        WsfeDePrueba.Pausa pausa = wsfe.pausarElSiguienteFeCaeSolicitar();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Atencion> a = atenderEnOtroHilo(pool, motor);
            pausa.esperarQueLlegue();
            long serieA = jdbc.queryForObject("select id from serie_fiscal where ferreteria_id = ? and cbte_tipo = 6",
                    Long.class, ferreteriaId);
            String leaseA = leaseDe(ferreteriaId, FACTURA_B);
            long pendienteA = registrarFacturaB();
            assertThat(atenderEnOtroHilo(pool, motor, otra, FACTURA_B).get(60, TimeUnit.SECONDS))
                    .isEqualTo(Atencion.TERMINADA);
            assertThat(registro(deOtra)).containsEntry("estado", "AUTORIZADO").containsEntry("numero", 1L);
            // con la ferretería de otra en el contexto, nada de la serie de A
            Future<List<Object>> desdeOtra = enOtroHilo(pool, otra, () -> {
                List<Object> r = new ArrayList<>();
                r.add(pasos.intentoAbierto(serieA).isPresent());
                r.add(pasos.tomar(serieA, UUID.randomUUID().toString(), reloj.instant().plus(Duration.ofDays(1)),
                        reloj.instant().plus(Duration.ofDays(2))));
                try { pasos.registro(propio); r.add("visible"); } catch (NoSuchElementException e) { r.add("404"); }
                try { pasos.fueraDeFecha(pendienteA); r.add("tocó"); } catch (PasoDescartadoException e) { r.add("descartado"); }
                return r;
            });
            assertThat(desdeOtra.get(60, TimeUnit.SECONDS)).containsExactly(false, false, "404", "descartado");
            assertThat(leaseDe(ferreteriaId, FACTURA_B)).isEqualTo(leaseA);
            pausa.soltar();
            assertThat(a.get(60, TimeUnit.SECONDS)).isEqualTo(Atencion.TERMINADA);
        } finally {
            pool.shutdownNow();
        }
        assertThat(registro(propio)).containsEntry("estado", "AUTORIZADO").containsEntry("numero", 1L);
        assertThat(jdbc.queryForObject("select count(*) from comprobante_fiscal where ferreteria_id = ? and estado = 'AUTORIZADO'",
                Integer.class, ferreteriaId)).isEqualTo(2);
    }

    // ---------- P8: ninguna conexión en la recuperación ----------

    @Test
    void p8_ningunaConexionDuranteLaPrimeraSincronizacionNiLaVerificacion() throws Exception {
        // ARCA ya tiene el 1 (otro sistema): la primera sincronización llama a los dos.
        autorizarAfuera(PedidoDePrueba.facturaB(HOY).numero(1).importes("121.00", "100.00", "21.00")
                .alicuotas(PedidoDePrueba.alicuota(5, "100.00", "21.00")));
        long r1 = registrarFacturaB();
        PuertoConFrenos puerto = new PuertoConFrenos(wsfe);
        MotorFiscal m = instancia(puerto);
        Freno u1 = puerto.frenarUltimo();
        Freno c1 = puerto.frenarConsultar();
        List<Integer> activasVistas = new ArrayList<>();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Atencion> f = atenderEnOtroHilo(pool, m);
            u1.llego.esperar();
            activasVistas.add(activas());
            u1.soltada.avisar();
            c1.llego.esperar();
            activasVistas.add(activas());
            c1.soltada.avisar();
            assertThat(f.get(60, TimeUnit.SECONDS)).isEqualTo(Atencion.TERMINADA);
        } finally {
            pool.shutdownNow();
        }
        assertThat(numero(r1)).isEqualTo(2);

        long r2 = registrarFacturaB();
        wsfe.fallas().programar(Falla.TIMEOUT_DESPUES_DE_AUTORIZAR);
        assertThat(atenderB()).isEqualTo(Atencion.SIN_RESPUESTA);
        Freno u2 = puerto.frenarUltimo();
        Freno c2 = puerto.frenarConsultar();
        pool = Executors.newSingleThreadExecutor();
        try {
            Future<Atencion> f = atenderEnOtroHilo(pool, m);
            u2.llego.esperar();
            activasVistas.add(activas());
            u2.soltada.avisar();
            c2.llego.esperar();
            activasVistas.add(activas());
            c2.soltada.avisar();
            assertThat(f.get(60, TimeUnit.SECONDS)).isEqualTo(Atencion.TERMINADA);
        } finally {
            pool.shutdownNow();
        }
        assertThat(activasVistas).containsOnly(0);
        assertThat(registro(r2)).containsEntry("estado", "AUTORIZADO").containsEntry("numero", 3L);
    }

    // ---------- P9: transiciones condicionales ----------

    @Test
    void p9a_registroCambiadoPorSqlDuranteLaLlamadaDescartaElResultadoSinTocarNada() throws Exception {
        long r = registrarFacturaB();
        WsfeDePrueba.Pausa pausa = wsfe.pausarElSiguienteFeCaeSolicitar();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Atencion at;
        try {
            Future<Atencion> f = atenderEnOtroHilo(pool, motor);
            pausa.esperarQueLlegue();
            jdbc.update("update comprobante_fiscal set estado = 'EN_VERIFICACION' where id = ?", r);
            pausa.soltar();
            at = f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(at).isEqualTo(Atencion.SERIE_PERDIDA);
        assertThat(registro(r)).containsEntry("estado", "EN_VERIFICACION").containsEntry("cae", null)
                .containsEntry("numero", 1L);
        assertThat(intentos(r)).singleElement().satisfies(i -> assertThat(i).containsEntry("estado", "ABIERTO"));
        assertThat(ultimoDeLaSerie(ferreteriaId, FACTURA_B)).as("rollback del UPDATE de la serie").isZero();
        assertThat(leaseDe(ferreteriaId, FACTURA_B)).isNull();
    }

    @Test
    void p9b_intentoCambiadoPorSqlDuranteLaLlamadaRevierteTambienElRegistroYLaSerie() throws Exception {
        long r = registrarFacturaB();
        WsfeDePrueba.Pausa pausa = wsfe.pausarElSiguienteFeCaeSolicitar();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Atencion at;
        try {
            Future<Atencion> f = atenderEnOtroHilo(pool, motor);
            pausa.esperarQueLlegue();
            jdbc.update("update intento_fiscal set estado = 'NO_LLEGO', terminado_en = now(6), cerrado_en = now(6) "
                    + "where comprobante_fiscal_id = ?", r);
            pausa.soltar();
            at = f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(at).isEqualTo(Atencion.SERIE_PERDIDA);
        assertThat(registro(r)).containsEntry("estado", "EN_CURSO").containsEntry("cae", null);
        assertThat(ultimoDeLaSerie(ferreteriaId, FACTURA_B)).isZero();
    }

    @Test
    void p9c_registroEnAlertaDuranteLaConsultaDeLaVerificacionDescartaLaAdopcion() throws Exception {
        long r = registrarFacturaB();
        wsfe.fallas().programar(Falla.TIMEOUT_DESPUES_DE_AUTORIZAR);
        assertThat(atenderB()).isEqualTo(Atencion.SIN_RESPUESTA);
        PuertoConFrenos puerto = new PuertoConFrenos(wsfe);
        Freno c = puerto.frenarConsultar();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Atencion at;
        try {
            Future<Atencion> f = atenderEnOtroHilo(pool, instancia(puerto));
            c.llego.esperar();
            jdbc.update("update comprobante_fiscal set estado = 'CAE_NO_COINCIDE' where id = ?", r);
            c.soltada.avisar();
            at = f.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThat(at).isEqualTo(Atencion.SERIE_PERDIDA);
        assertThat(registro(r)).containsEntry("estado", "CAE_NO_COINCIDE").containsEntry("cae", null);
        assertThat(intentos(r)).singleElement().satisfies(i -> assertThat(i).containsEntry("estado", "EN_VERIFICACION"));
        assertThat(ultimoDeLaSerie(ferreteriaId, FACTURA_B)).isZero();
        assertThat(leaseDe(ferreteriaId, FACTURA_B)).isNull();
    }

    // ---------- P10: alta (FOR SHARE) contra el panel (FOR UPDATE) ----------

    @Test
    void p10a_altaPrimeroElPanelEsperaYDespuesVeElRegistro() throws Exception {
        long dl = barrera.deadlocksAntes();
        Hito altaHecha = new Hito();
        Hito commitear = new Hito();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            long venta = venta(ferreteriaId);
            Future<Long> alta = enOtroHilo(pool, ferreteriaId, () -> new TransactionTemplate(transacciones).execute(s -> {
                long id = motor.registrar(new AltaFiscal(venta, ReceptorFiscal.sinCliente(),
                        List.of(new RenglonFiscal(Alicuota.IVA_21, new BigDecimal("121.00")))));
                altaHecha.avisar();
                commitear.esperar();
                return id;
            }));
            altaHecha.esperar();
            Future<Object> p = pool.submit(() -> panel.cambiarFacturacionHabilitada(ferreteriaId, false));
            barrera.esperarBloqueados(1);
            assertThat(barrera.objetosEnEspera()).containsExactly("config_arca");
            commitear.avisar();
            alta.get(60, TimeUnit.SECONDS);
            p.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        assertThatThrownBy(() -> panel.cambiarSimulador(ferreteriaId, false)).isInstanceOf(ReglaDeNegocioException.class);
        assertThat(jdbc.queryForObject("select ambiente from config_arca where ferreteria_id = ?", String.class,
                ferreteriaId)).isEqualTo("SIMULADOR");
        barrera.assertSinDeadlocks(dl);
    }

    @Test
    void p10b_panelPrimeroElAltaEsperaYSeNiega() throws Exception {
        long dl = barrera.deadlocksAntes();
        Hito panelHecho = new Hito();
        Hito commitear = new Hito();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        Throwable error = null;
        try {
            Future<Object> p = pool.submit(() -> new TransactionTemplate(transacciones).execute(s -> {
                panel.cambiarFacturacionHabilitada(ferreteriaId, false);
                panelHecho.avisar();
                commitear.esperar();
                return null;
            }));
            panelHecho.esperar();
            long venta = venta(ferreteriaId);
            Future<Long> alta = enOtroHilo(pool, ferreteriaId, () -> motor.registrar(new AltaFiscal(venta,
                    ReceptorFiscal.sinCliente(), List.of(new RenglonFiscal(Alicuota.IVA_21, new BigDecimal("121.00"))))));
            barrera.esperarBloqueados(1);
            commitear.avisar();
            p.get(60, TimeUnit.SECONDS);
            try { alta.get(60, TimeUnit.SECONDS); } catch (java.util.concurrent.ExecutionException e) { error = e.getCause(); }
        } finally {
            pool.shutdownNow();
        }
        assertThat(error).isInstanceOf(ReglaDeNegocioException.class);
        assertThat(jdbc.queryForObject("select count(*) from comprobante_fiscal where ferreteria_id = ?", Integer.class,
                ferreteriaId)).isZero();
        barrera.assertSinDeadlocks(dl);
    }

    /** La transacción de quien llama ya leyó (foto de REPEATABLE READ) antes de que el panel apague: FOR SHARE ve lo último. */
    @Test
    void p10c_fotoViejaDeLaTransaccionDeQuienLlama() throws Exception {
        Throwable error = registrarConLecturaPrevia();
        assertThat(error).isInstanceOf(ReglaDeNegocioException.class);
    }

    private Throwable registrarConLecturaPrevia() throws Exception {
        long config = jdbc.queryForObject("select id from config_arca where ferreteria_id = ?", Long.class, ferreteriaId);
        long venta = venta(ferreteriaId);
        Hito leyo = new Hito();
        Hito seguir = new Hito();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Throwable error = null;
        try {
            Future<Long> alta = enOtroHilo(pool, ferreteriaId, () -> new TransactionTemplate(transacciones).execute(s -> {
                assertThat(jdbc.queryForObject("select facturacion_habilitada from config_arca where id = ?",
                        Boolean.class, config)).isTrue();
                leyo.avisar();
                seguir.esperar();
                return motor.registrar(new AltaFiscal(venta, ReceptorFiscal.sinCliente(),
                        List.of(new RenglonFiscal(Alicuota.IVA_21, new BigDecimal("121.00")))));
            }));
            leyo.esperar();
            panel.cambiarFacturacionHabilitada(ferreteriaId, false);
            seguir.avisar();
            try { alta.get(60, TimeUnit.SECONDS); } catch (java.util.concurrent.ExecutionException e) { error = e.getCause(); }
        } finally {
            pool.shutdownNow();
        }
        return error;
    }

    @Test
    void p10e_carreraLibreAltasYPanel20Rondas() throws Exception {
        long dl = barrera.deadlocksAntes();
        for (int ronda = 0; ronda < 20; ronda++) {
            panel.cambiarFacturacionHabilitada(ferreteriaId, true);
            long v1 = venta(ferreteriaId);
            long v2 = venta(ferreteriaId);
            int rr = ronda;
            List<Callable<Object>> tareas = List.of(
                    () -> motor.registrar(new AltaFiscal(v1, ReceptorFiscal.sinCliente(),
                            List.of(new RenglonFiscal(Alicuota.IVA_21, new BigDecimal("121.00"))))),
                    () -> motor.registrar(new AltaFiscal(v2, ReceptorFiscal.sinCliente(),
                            List.of(new RenglonFiscal(Alicuota.IVA_21, new BigDecimal("121.00"))))),
                    () -> rr % 2 == 0 ? panel.cambiarFacturacionHabilitada(ferreteriaId, false)
                            : panel.cambiarDePrueba(ferreteriaId, true));
            ExecutorService pool = Executors.newFixedThreadPool(3);
            Largada largada = new Largada(3);
            List<Future<Object>> fs = new ArrayList<>();
            for (Callable<Object> t : tareas) {
                fs.add(pool.submit(() -> {
                    TenantContext.setCurrentFerreteriaId(ferreteriaId);
                    try { largada.listoYEsperar(); return t.call(); } finally { TenantContext.clear(); }
                }));
            }
            largada.esperarListos(Duration.ofSeconds(30));
            largada.largar();
            for (Future<Object> f : fs) {
                try {
                    f.get(60, TimeUnit.SECONDS);
                } catch (java.util.concurrent.ExecutionException e) {
                    assertThat(e.getCause()).isInstanceOf(ReglaDeNegocioException.class);
                }
            }
            pool.shutdownNow();
        }
        barrera.assertSinDeadlocks(dl);
    }

    // ---------- P12: la batería solapa de verdad ----------

    @Test
    void p12_solapamientoDeAutorizacionesSimultaneas() throws Exception {
        long dl = barrera.deadlocksAntes();
        int ocupadas = 0;
        for (int ronda = 0; ronda < 15; ronda++) {
            long f = ferreteriaConFacturacion(cuitNuevo());
            TenantContext.setCurrentFerreteriaId(f);
            List<Long> ids = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                ids.add(motor.registrar(new AltaFiscal(venta(f), ReceptorFiscal.sinCliente(),
                        List.of(new RenglonFiscal(Alicuota.IVA_21, new BigDecimal("121.00"))))));
            }
            ExecutorService pool = Executors.newFixedThreadPool(6);
            Largada largada = new Largada(6);
            List<Future<Atencion>> fs = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                fs.add(pool.submit(() -> {
                    TenantContext.setCurrentFerreteriaId(f);
                    try { largada.listoYEsperar(); return motor.atender(Ambiente.SIMULADOR, 1, FACTURA_B); }
                    finally { TenantContext.clear(); }
                }));
            }
            largada.esperarListos(Duration.ofSeconds(30));
            largada.largar();
            for (Future<Atencion> x : fs) { if (x.get(60, TimeUnit.SECONDS) == Atencion.OCUPADA) { ocupadas++; } }
            pool.shutdownNow();
            List<Long> nums = new ArrayList<>();
            for (long id : ids) { nums.add(numero(id)); }
            assertThat(nums).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L);
        }
        TenantContext.setCurrentFerreteriaId(ferreteriaId);
        assertThat(ocupadas).as("las atenciones se pisaron de verdad").isPositive();
        barrera.assertSinDeadlocks(dl);
    }
}
