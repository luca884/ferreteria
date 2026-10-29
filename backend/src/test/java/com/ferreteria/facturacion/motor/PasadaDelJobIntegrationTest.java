package com.ferreteria.facturacion.motor;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.facturacion.model.Alicuota;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.model.ClaveDeSerie;
import com.ferreteria.facturacion.model.CondicionIvaReceptor;
import com.ferreteria.facturacion.model.ReceptorFiscal;
import com.ferreteria.facturacion.model.RenglonFiscal;
import com.ferreteria.facturacion.model.TipoDocumentoReceptor;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Lo que el motor le deja listo al job de reintento (rama 7, cu-07-contingencias S2, S13 y C5) contra MySQL real y el
 * simulador, con el {@code Clock} fijo: la atención con un límite de tiempo, el backoff de la serie en la base y las series
 * con trabajo de la ferretería.
 */
class PasadaDelJobIntegrationTest extends MotorFiscalTestBase {

    private static final ClaveDeSerie SERIE_B = new ClaveDeSerie(Ambiente.SIMULADOR, 1, FACTURA_B);
    private static final ClaveDeSerie SERIE_A = new ClaveDeSerie(Ambiente.SIMULADOR, 1, FACTURA_A);

    @Autowired
    PasosFiscales pasos;

    // ---- La atención con límite ----

    @Test
    @DisplayName("con el límite alcanzado no empieza nada; con una llamada en vuelo que lo pasa, termina esa y deja los "
            + "demás pendientes para la próxima (SIN_TIEMPO); sin límite, como la venta, atiende todo")
    void laAtencionRespetaElLimite() throws Exception {
        long r1 = registrarFacturaB();
        long r2 = registrarFacturaB();
        long r3 = registrarFacturaB();

        assertThat(motor.atender(Ambiente.SIMULADOR, 1, FACTURA_B, AHORA)).isEqualTo(Atencion.SIN_TIEMPO);
        assertThat(wsfe.solicitudes()).isZero();
        assertThat(leaseDeB()).isNull();

        WsfeDePrueba.Pausa pausa = wsfe.pausarElSiguienteFeCaeSolicitar();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Atencion> atencion = pool.submit(() -> {
                TenantContext.setCurrentFerreteriaId(ferreteriaId);
                try {
                    return motor.atender(Ambiente.SIMULADOR, 1, FACTURA_B, AHORA.plusSeconds(120));
                } finally {
                    TenantContext.clear();
                }
            });
            pausa.esperarQueLlegue();
            reloj.avanzar(Duration.ofSeconds(121));
            pausa.soltar();
            assertThat(atencion.get(60, TimeUnit.SECONDS)).isEqualTo(Atencion.SIN_TIEMPO);
        } finally {
            pausa.soltar();
            pool.shutdownNow();
        }
        assertThat(estado(r1)).isEqualTo("AUTORIZADO");
        assertThat(estado(r2)).isEqualTo("PENDIENTE");
        assertThat(estado(r3)).isEqualTo("PENDIENTE");
        assertThat(leaseDeB()).isNull();

        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        assertThat(List.of(estado(r2), estado(r3))).containsOnly("AUTORIZADO");
    }

    // ---- El backoff de la serie ----

    @Test
    @DisplayName("cada pasada con error transitorio suma una falla y pospone la serie 1, 2, 4… minutos hasta 30; las que "
            + "no son de esta serie (OCUPADA, SERIE_PERDIDA), la trabada y la cortada por tiempo no lo tocan; TERMINADA lo "
            + "vuelve a cero")
    void elBackoffSeDuplicaYVuelveACero() {
        List<Duration> esperas = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            pasos.registrarPasada(SERIE_B, Atencion.SIN_RESPUESTA, AHORA);
            assertThat(backoffDeB()).containsEntry("fallas_seguidas", i);
            esperas.add(Duration.between(AHORA, pasos.serie(serieB()).getProximoReintento()));
        }
        assertThat(esperas).containsExactly(Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(4),
                Duration.ofMinutes(8), Duration.ofMinutes(16), Duration.ofMinutes(30), Duration.ofMinutes(30));

        Map<String, Object> antes = backoffDeB();
        for (Atencion neutra : List.of(Atencion.OCUPADA, Atencion.SERIE_PERDIDA, Atencion.TRABADA, Atencion.SIN_TIEMPO)) {
            pasos.registrarPasada(SERIE_B, neutra, AHORA.plusSeconds(3600));
        }
        assertThat(backoffDeB()).isEqualTo(antes);

        pasos.registrarPasada(SERIE_B, Atencion.TERMINADA, AHORA);
        assertThat(backoffDeB()).containsEntry("fallas_seguidas", 0).containsEntry("proximo_reintento", null);
    }

    @Test
    @DisplayName("la serie que autoriza vuelve el backoff a cero, aunque autorice la venta y no el job")
    void autorizarVuelveElBackoffACero() {
        pasos.registrarPasada(SERIE_B, Atencion.SIN_RESPUESTA, AHORA);
        pasos.registrarPasada(SERIE_B, Atencion.SIN_RESPUESTA, AHORA);
        assertThat(backoffDeB()).containsEntry("fallas_seguidas", 2);

        long r = registrarFacturaB();
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);

        assertThat(estado(r)).isEqualTo("AUTORIZADO");
        assertThat(backoffDeB()).containsEntry("fallas_seguidas", 0).containsEntry("proximo_reintento", null);
    }

    // ---- Las series con trabajo ----

    @Test
    @DisplayName("las series con trabajo: con un registro en trámite (aunque la serie no tenga fila todavía), en orden FIFO, "
            + "sin las pospuestas hasta que vence su reintento")
    void lasSeriesConTrabajoRespetanElBackoff() {
        motor.registrar(new AltaFiscal(venta(ferreteriaId), new ReceptorFiscal(CondicionIvaReceptor.RESPONSABLE_INSCRIPTO,
                TipoDocumentoReceptor.CUIT, 30786013301L, "Corralón Sur SA", "Av. Colón 2000"), renglones()));
        registrarFacturaB();
        assertThat(jdbc.queryForObject("select count(*) from serie_fiscal where ferreteria_id = ?", Integer.class,
                ferreteriaId)).isZero();

        assertThat(pasos.seriesConTrabajo(AHORA)).containsExactly(SERIE_A, SERIE_B);

        pasos.registrarPasada(SERIE_B, Atencion.SIN_RESPUESTA, AHORA);
        assertThat(pasos.seriesConTrabajo(AHORA)).containsExactly(SERIE_A);
        assertThat(pasos.seriesConTrabajo(AHORA.plusSeconds(59))).containsExactly(SERIE_A);
        assertThat(pasos.seriesConTrabajo(AHORA.plusSeconds(60))).containsExactly(SERIE_A, SERIE_B);

        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        assertThat(pasos.seriesConTrabajo(AHORA.plusSeconds(60))).containsExactly(SERIE_A);
    }

    @Test
    @DisplayName("una serie con una alerta (CAE_NO_COINCIDE o SERIE_DESINCRONIZADA) queda afuera aunque tenga pendientes "
            + "(S13), y otra ferretería no ve las series de esta")
    void lasSeriesConAlertaYLasDeOtraFerreteriaQuedanAfuera() {
        long alerta = registrarFacturaB();
        registrarFacturaB();
        motor.registrar(new AltaFiscal(venta(ferreteriaId), new ReceptorFiscal(CondicionIvaReceptor.RESPONSABLE_INSCRIPTO,
                TipoDocumentoReceptor.CUIT, 30786013301L, "Corralón Sur SA", "Av. Colón 2000"), renglones()));
        assertThat(pasos.seriesConTrabajo(AHORA)).containsExactly(SERIE_B, SERIE_A);

        jdbc.update("update comprobante_fiscal set estado = 'SERIE_DESINCRONIZADA', numero = 7, cbte_fch = ? where id = ?",
                Date.valueOf(HOY), alerta);
        assertThat(pasos.seriesConTrabajo(AHORA)).containsExactly(SERIE_A);
        jdbc.update("update comprobante_fiscal set estado = 'CAE_NO_COINCIDE' where id = ?", alerta);
        assertThat(pasos.seriesConTrabajo(AHORA)).containsExactly(SERIE_A);

        TenantContext.setCurrentFerreteriaId(ferreteriaConFacturacion(cuitNuevo()));
        assertThat(pasos.seriesConTrabajo(AHORA)).isEmpty();
    }

    // ---- Ayudas ----

    private static List<RenglonFiscal> renglones() {
        return List.of(new RenglonFiscal(Alicuota.IVA_21, new BigDecimal("121.00")));
    }

    private long serieB() {
        return pasos.asegurarSerie(Ambiente.SIMULADOR, 1, FACTURA_B);
    }

    private Map<String, Object> backoffDeB() {
        return jdbc.queryForMap("select fallas_seguidas, proximo_reintento from serie_fiscal where ferreteria_id = ? "
                + "and cbte_tipo = ?", ferreteriaId, FACTURA_B);
    }

    private String leaseDeB() {
        List<String> lease = jdbc.queryForList("select lease_dueno from serie_fiscal where ferreteria_id = ? "
                + "and cbte_tipo = ?", String.class, ferreteriaId, FACTURA_B);
        return lease.isEmpty() ? null : lease.get(0);
    }
}
