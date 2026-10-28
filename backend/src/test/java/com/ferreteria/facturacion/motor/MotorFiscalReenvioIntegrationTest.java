package com.ferreteria.facturacion.motor;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.wsfe.Auth;
import com.ferreteria.facturacion.wsfe.FeCaeReq;
import com.ferreteria.facturacion.wsfe.FeCaeResponse;
import com.ferreteria.facturacion.wsfe.FeCompConsultarResponse;
import com.ferreteria.facturacion.wsfe.FeCompUltimoAutorizadoResponse;
import com.ferreteria.facturacion.wsfe.ServicioWsfe;
import com.ferreteria.facturacion.wsfe.WsfeConFallas.Falla;
import com.ferreteria.support.BarreraDeLocks.Hito;
import com.ferreteria.tenant.TenantContext;
import java.sql.Date;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

/**
 * El reenvío por N−1 frente a la ventana de CbteFch (B1 de T6, corregido en 46bf0ec): fuera de la ventana termina en
 * FUERA_DE_FECHA sin reenviar, dentro sigue reenviando con la fecha original, y el paso nuevo se revierte entero si lo
 * descarta un cambio de estado o de lease entre la verificación y el UPDATE.
 */
class MotorFiscalReenvioIntegrationTest extends MotorFiscalTestBase {

    @Autowired PasosFiscales pasos;
    @Value("${app.facturacion.motor.lease}") Duration lease;
    @Value("${app.facturacion.motor.presupuesto-llamada}") Duration presupuesto;

    static final class Freno { final Hito llego = new Hito(); final Hito soltada = new Hito(); }

    static final class PuertoConFrenos implements ServicioWsfe {
        final ServicioWsfe real;
        final Queue<Freno> ultimo = new ConcurrentLinkedQueue<>();
        PuertoConFrenos(ServicioWsfe real) { this.real = real; }
        Freno frenarUltimo() { Freno f = new Freno(); ultimo.add(f); return f; }
        @Override public FeCompUltimoAutorizadoResponse feCompUltimoAutorizado(Auth a, int pv, int t) {
            Freno f = ultimo.poll();
            if (f != null) { f.llego.avisar(); f.soltada.esperar(); }
            return real.feCompUltimoAutorizado(a, pv, t);
        }
        @Override public FeCaeResponse feCaeSolicitar(Auth a, FeCaeReq r) { return real.feCaeSolicitar(a, r); }
        @Override public FeCompConsultarResponse feCompConsultar(Auth a, int t, int pv, long n) {
            return real.feCompConsultar(a, t, pv, n);
        }
    }

    MotorFiscal instancia(ServicioWsfe puerto) {
        return new MotorFiscal(pasos, new WsfePorAmbiente(puerto), reloj, lease, presupuesto);
    }

    Future<Atencion> atenderEnOtroHilo(ExecutorService pool, MotorFiscal m) {
        long f = ferreteriaId;
        return pool.submit(() -> {
            TenantContext.setCurrentFerreteriaId(f);
            try { return m.atender(Ambiente.SIMULADOR, 1, FACTURA_B); } finally { TenantContext.clear(); }
        });
    }

    static Instant ba(int y, int m, int d, int h, int min) {
        return LocalDateTime.of(y, m, d, h, min).atZone(ZONA).toInstant();
    }

    String leaseDe() {
        List<String> l = jdbc.queryForList("select lease_dueno from serie_fiscal where ferreteria_id = ? and cbte_tipo = 6",
                String.class, ferreteriaId);
        return l.isEmpty() ? null : l.get(0);
    }

    long serieId() {
        return jdbc.queryForObject("select id from serie_fiscal where ferreteria_id = ? and cbte_tipo = 6", Long.class,
                ferreteriaId);
    }

    // ---- B1: fuera de la ventana no se reenvía ----

    @Test
    void p6g_reenvioAlDiaSiguienteDeUnEnvioEnElBordeDeLaVentana() {
        reloj.fijar(ba(2026, 10, 23, 10, 0));
        long r = registrarFacturaB();
        reloj.fijar(ba(2026, 10, 28, 10, 0));
        wsfe.fallas().programar(Falla.TIMEOUT_ANTES_DE_AUTORIZAR);
        assertThat(atenderB()).isEqualTo(Atencion.SIN_RESPUESTA);
        assertThat(registro(r)).containsEntry("estado", "EN_VERIFICACION").containsEntry("numero", 1L)
                .containsEntry("cbte_fch", Date.valueOf("2026-10-23"));
        reloj.fijar(ba(2026, 10, 29, 9, 0));
        long siguiente = registrarFacturaB();
        Atencion at = atenderB();
        assertThat(at).isEqualTo(Atencion.TERMINADA);
        assertThat(registro(r)).containsEntry("estado", "FUERA_DE_FECHA").containsEntry("numero", null)
                .containsEntry("cbte_fch", null).containsEntry("cae", null).containsEntry("resultado", null);
        assertThat(intentos(r)).singleElement().satisfies(i -> assertThat(i).containsEntry("estado", "NO_LLEGO")
                .containsEntry("numero", 1L));
        assertThat(registro(siguiente)).containsEntry("estado", "AUTORIZADO").containsEntry("numero", 1L)
                .containsEntry("cbte_fch", Date.valueOf("2026-10-29"));
        assertThat(wsfe.solicitudes()).isEqualTo(2);
        assertThat(ultimoEnArca(FACTURA_B)).isEqualTo(1);
        assertThat(leaseDe()).isNull();
    }

    // ---- Vecinos: el reenvío dentro de la ventana sigue ----

    @Test
    void r1_reenvioEnN5JustoTodaviaReenvia() {
        reloj.fijar(ba(2026, 10, 23, 10, 0));
        long r = registrarFacturaB();
        reloj.fijar(ba(2026, 10, 27, 10, 0)); // N-4
        wsfe.fallas().programar(Falla.TIMEOUT_ANTES_DE_AUTORIZAR);
        assertThat(atenderB()).isEqualTo(Atencion.SIN_RESPUESTA);
        reloj.fijar(ba(2026, 10, 28, 23, 59)); // N-5 en Buenos Aires
        Atencion at = atenderB();
        assertThat(at).isEqualTo(Atencion.TERMINADA);
        assertThat(registro(r)).containsEntry("estado", "AUTORIZADO").containsEntry("numero", 1L)
                .containsEntry("cbte_fch", Date.valueOf("2026-10-23"));
        assertThat(intentos(r)).extracting(i -> i.get("estado")).containsExactly("NO_LLEGO", "AUTORIZADO");
        assertThat(wsfe.solicitudes()).isEqualTo(2);
    }

    @Test
    void r2_reenvioDeUnMesAlOtroDentroDeN5Reenvia() {
        reloj.fijar(ba(2026, 10, 30, 10, 0));
        long r = registrarFacturaB();
        wsfe.fallas().programar(Falla.TIMEOUT_ANTES_DE_AUTORIZAR);
        assertThat(atenderB()).isEqualTo(Atencion.SIN_RESPUESTA);
        reloj.fijar(ba(2026, 11, 2, 10, 0));
        Atencion at = atenderB();
        assertThat(at).isEqualTo(Atencion.TERMINADA);
        assertThat(registro(r)).containsEntry("estado", "AUTORIZADO").containsEntry("numero", 1L)
                .containsEntry("cbte_fch", Date.valueOf("2026-10-30"));
        assertThat(wsfe.solicitudes()).isEqualTo(2);
    }

    /** Un intento ABIERTO de una toma muerta, y la atención siguiente cuando la CbteFch ya no entra. */
    @Test
    void r3_intentoAbiertoDeUnaTomaMuertaFueraDeVentana() {
        reloj.fijar(ba(2026, 10, 23, 10, 0));
        registrarFacturaB();
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        long r = registrarFacturaB();
        long serie = serieId();
        Instant ahora = reloj.instant();
        assertThat(pasos.tomar(serie, "muerta", ahora, ahora.plus(lease))).isTrue();
        pasos.abrirIntento(serie, "muerta", r, 2, LocalDate.of(2026, 10, 23), "{}", ahora, ahora.plus(lease));
        reloj.fijar(ba(2026, 10, 29, 10, 0));
        long siguiente = registrarFacturaB();
        Atencion at = atenderB();
        assertThat(at).isEqualTo(Atencion.TERMINADA);
        assertThat(registro(r)).containsEntry("estado", "FUERA_DE_FECHA").containsEntry("numero", null)
                .containsEntry("cbte_fch", null);
        assertThat(intentos(r)).singleElement().satisfies(i -> assertThat(i).containsEntry("estado", "NO_LLEGO"));
        assertThat(registro(siguiente)).containsEntry("estado", "AUTORIZADO").containsEntry("numero", 2L);
        assertThat(wsfe.solicitudes()).isEqualTo(2);
    }

    // ---- Paso nuevo descartado: rollback completo ----

    /** Prepara un dudoso EN_VERIFICACION del 23/10 (N=1) y frena la verificación del 29/10 en FECompUltimoAutorizado. */
    long dudosoDel23() {
        reloj.fijar(ba(2026, 10, 23, 10, 0));
        long r = registrarFacturaB();
        reloj.fijar(ba(2026, 10, 28, 10, 0));
        wsfe.fallas().programar(Falla.TIMEOUT_ANTES_DE_AUTORIZAR);
        assertThat(atenderB()).isEqualTo(Atencion.SIN_RESPUESTA);
        reloj.fijar(ba(2026, 10, 29, 9, 0));
        return r;
    }

    Atencion conFreno(Runnable entre) throws Exception {
        PuertoConFrenos puerto = new PuertoConFrenos(wsfe);
        Freno f = puerto.frenarUltimo();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Atencion> a = atenderEnOtroHilo(pool, instancia(puerto));
            f.llego.esperar();
            entre.run();
            f.soltada.avisar();
            return a.get(60, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void r4_intentoCambiadoPorSqlRevierteElRegistro() throws Exception {
        long r = dudosoDel23();
        Atencion at = conFreno(() -> jdbc.update("update intento_fiscal set estado = 'ALERTA', cerrado_en = null "
                + "where comprobante_fiscal_id = ?", r));
        assertThat(at).isEqualTo(Atencion.SERIE_PERDIDA);
        assertThat(registro(r)).containsEntry("estado", "EN_VERIFICACION").containsEntry("numero", 1L)
                .containsEntry("cbte_fch", Date.valueOf("2026-10-23"));
        assertThat(intentos(r)).singleElement().satisfies(i -> assertThat(i).containsEntry("estado", "ALERTA")
                .containsEntry("respuesta_verificacion", null));
        assertThat(wsfe.solicitudes()).isEqualTo(1);
        assertThat(leaseDe()).isNull();
    }

    @Test
    void r5_registroCambiadoPorSqlNoTocaElIntento() throws Exception {
        long r = dudosoDel23();
        Atencion at = conFreno(() -> jdbc.update("update comprobante_fiscal set estado = 'CAE_NO_COINCIDE' where id = ?", r));
        assertThat(at).isEqualTo(Atencion.SERIE_PERDIDA);
        assertThat(registro(r)).containsEntry("estado", "CAE_NO_COINCIDE").containsEntry("numero", 1L)
                .containsEntry("cbte_fch", Date.valueOf("2026-10-23"));
        assertThat(intentos(r)).singleElement().satisfies(i -> assertThat(i).containsEntry("estado", "EN_VERIFICACION")
                .containsEntry("cerrado_en", null));
        assertThat(wsfe.solicitudes()).isEqualTo(1);
    }

    @Test
    void r6_leasePerdidoNoTocaNada() throws Exception {
        long r = dudosoDel23();
        Atencion at = conFreno(() -> jdbc.update("update serie_fiscal set lease_dueno = 'otra', "
                + "lease_vence = '2030-01-01 00:00:00' where ferreteria_id = ? and cbte_tipo = 6", ferreteriaId));
        assertThat(at).isEqualTo(Atencion.SERIE_PERDIDA);
        assertThat(registro(r)).containsEntry("estado", "EN_VERIFICACION").containsEntry("numero", 1L)
                .containsEntry("cbte_fch", Date.valueOf("2026-10-23"));
        assertThat(intentos(r)).singleElement().satisfies(i -> assertThat(i).containsEntry("estado", "EN_VERIFICACION")
                .containsEntry("cerrado_en", null));
        assertThat(leaseDe()).isEqualTo("otra");
        assertThat(wsfe.solicitudes()).isEqualTo(1);
    }

    /** Toma vencida durante la verificación: otra instancia la resuelve; la primera no repite nada. */
    @Test
    void r7_leaseVencidoOtraInstanciaResuelveYLaPrimeraSeDescarta() throws Exception {
        long r = dudosoDel23();
        long siguiente = registrarFacturaB();
        Atencion at = conFreno(() -> {
            reloj.avanzar(lease.plusSeconds(1));
            assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        });
        assertThat(at).isEqualTo(Atencion.SERIE_PERDIDA);
        assertThat(registro(r)).containsEntry("estado", "FUERA_DE_FECHA").containsEntry("numero", null);
        assertThat(intentos(r)).singleElement().satisfies(i -> assertThat(i).containsEntry("estado", "NO_LLEGO"));
        assertThat(registro(siguiente)).containsEntry("estado", "AUTORIZADO").containsEntry("numero", 1L);
        assertThat(wsfe.solicitudes()).isEqualTo(2);
        assertThat(ultimoEnArca(FACTURA_B)).isEqualTo(1);
    }
}
