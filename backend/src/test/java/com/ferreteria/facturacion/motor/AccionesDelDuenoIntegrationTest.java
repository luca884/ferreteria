package com.ferreteria.facturacion.motor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.facturacion.api.FacturacionParaVenta;
import com.ferreteria.facturacion.api.FacturacionParaVenta.EstadoFiscalVenta;
import com.ferreteria.facturacion.api.FacturacionParaVenta.ResumenFiscal;
import com.ferreteria.facturacion.model.Alicuota;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.model.ComprobanteFiscal;
import com.ferreteria.facturacion.model.CondicionIvaReceptor;
import com.ferreteria.facturacion.model.ReceptorFiscal;
import com.ferreteria.facturacion.model.RenglonFiscal;
import com.ferreteria.facturacion.model.TipoDocumentoReceptor;
import com.ferreteria.facturacion.motor.AccionFiscalImpedidaException.Impedimento;
import com.ferreteria.facturacion.repository.ComprobanteFiscalRepository;
import com.ferreteria.facturacion.repository.ConfigArcaRepository;
import com.ferreteria.facturacion.wsfe.Obs;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Las acciones del Dueño sobre la factura de una venta (rama 7, CU-16 punto 7; cu-07-contingencias S5, S12, C2 y C3) en el
 * motor, contra MySQL real y el simulador: «Dejar sin factura» (definitivo, con motivo, usuario y hora) y «Reintentar» (el
 * registro rechazado queda como historia y nace otro, vigente, con la copia de hoy; la fecha nunca cambia sola). Y que todo
 * lo que lee el registro de una venta lee el vigente.
 */
class AccionesDelDuenoIntegrationTest extends MotorFiscalTestBase {

    private static final List<RenglonFiscal> RENGLONES = List.of(new RenglonFiscal(Alicuota.IVA_21, new BigDecimal("121.00")));
    private static final ReceptorFiscal CORRALON = new ReceptorFiscal(CondicionIvaReceptor.RESPONSABLE_INSCRIPTO,
            TipoDocumentoReceptor.CUIT, 30786013301L, "Corralón Sur SA", "Av. Colón 2000, Mar del Plata");

    @Autowired
    PlatformTransactionManager transacciones;
    @Autowired
    PasosFiscales pasos;
    @Autowired
    ComprobanteFiscalRepository registros;
    @Autowired
    ConfigArcaRepository configs;
    @Autowired
    FacturacionParaVenta facturacion;

    // ---- Dejar sin factura ----

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"RECHAZADO", "FUERA_DE_FECHA"})
    @DisplayName("una rechazada o fuera de fecha queda SIN_FACTURA con el motivo sin espacios de más, el usuario y la hora; "
            + "conserva lo que dijo ARCA, no traba el cambio de ambiente y no se reintenta")
    void dejarSinFactura(String estado) {
        long venta = estado.equals("RECHAZADO") ? ventaRechazada() : ventaFueraDeFecha();
        long registro = vigenteDe(venta);

        motor.dejarSinFactura(venta, "  El cliente no volvió  ", usuario());

        ComprobanteFiscal r = pasos.registro(registro);
        assertThat(r.getEstado().name()).isEqualTo("SIN_FACTURA");
        assertThat(r.getMotivoSinFactura()).isEqualTo("El cliente no volvió");
        assertThat(r.getUsuarioSinFacturaId()).isEqualTo(usuario());
        assertThat(r.getSinFacturaEn()).isEqualTo(AHORA);
        assertThat(r.getResultado()).isEqualTo(estado.equals("RECHAZADO") ? "R" : null);
        assertThat(r.getNumero()).isNull();
        assertThat(configs.registrosScoped().hayAbiertos()).isFalse();
        assertThatThrownBy(() -> enLaVenta(() -> motor.reintentar(new AltaFiscal(venta, ReceptorFiscal.sinCliente(),
                RENGLONES), true))).isInstanceOfSatisfying(AccionFiscalImpedidaException.class,
                        e -> assertThat(e.impedimento()).isEqualTo(Impedimento.SIN_FACTURA_RECHAZADA));
    }

    @Test
    @DisplayName("solo sobre una vigente RECHAZADO o FUERA_DE_FECHA de la ferretería: sin factura, pendiente, autorizada, "
            + "ya sin factura o de otra ferretería dan SIN_FACTURA_RECHAZADA en las dos acciones y no cambian nada")
    void soloSobreUnaRechazada() {
        long autorizada = venta(ferreteriaId);
        motor.registrar(new AltaFiscal(autorizada, ReceptorFiscal.sinCliente(), RENGLONES));
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        long sinFactura = ventaRechazada();
        motor.dejarSinFactura(sinFactura, "Se fue sin esperar", usuario());
        long ajena = ventaRechazada();
        // La última: ninguna atención la manda.
        long pendiente = venta(ferreteriaId);
        motor.registrar(new AltaFiscal(pendiente, ReceptorFiscal.sinCliente(), RENGLONES));
        long sinRegistro = venta(ferreteriaId);
        List<Map<String, Object>> antes = jdbc.queryForList("select * from comprobante_fiscal where ferreteria_id = ? "
                + "order by id", ferreteriaId);
        TenantContext.setCurrentFerreteriaId(ferreteriaConFacturacion(cuitNuevo()));
        long usuarioDeLaOtra = jdbc.queryForObject("select min(id) from usuario where ferreteria_id = ?", Long.class,
                TenantContext.requireFerreteriaId());

        impedida(ajena, usuarioDeLaOtra);
        TenantContext.setCurrentFerreteriaId(ferreteriaId);
        assertThat(List.of(autorizada, pendiente, sinFactura, sinRegistro)).allSatisfy(venta -> impedida(venta, usuario()));

        assertThat(jdbc.queryForList("select * from comprobante_fiscal where ferreteria_id = ? order by id", ferreteriaId))
                .isEqualTo(antes);
    }

    private void impedida(long venta, long usuario) {
        assertThatThrownBy(() -> motor.dejarSinFactura(venta, "Motivo", usuario))
                .isInstanceOfSatisfying(AccionFiscalImpedidaException.class,
                        e -> assertThat(e.impedimento()).isEqualTo(Impedimento.SIN_FACTURA_RECHAZADA))
                .hasMessage("Esta venta no tiene una factura rechazada para reintentar.");
        assertThatThrownBy(() -> enLaVenta(() -> motor.reintentar(new AltaFiscal(venta, ReceptorFiscal.sinCliente(),
                RENGLONES), true))).isInstanceOfSatisfying(AccionFiscalImpedidaException.class,
                        e -> assertThat(e.impedimento()).isEqualTo(Impedimento.SIN_FACTURA_RECHAZADA));
    }

    @Test
    @DisplayName("el motivo es obligatorio y de hasta 200 caracteres (contados como MySQL): 400 en «motivo», sin cambiar nada")
    void elMotivoEsObligatorioYCorto() {
        long venta = ventaRechazada();

        for (String vacio : new String[] {null, "", "   "}) {
            assertThatThrownBy(() -> motor.dejarSinFactura(venta, vacio, usuario()))
                    .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors())
                            .containsExactly(Map.entry("motivo", "Contá por qué la venta queda sin factura.")));
        }
        assertThatThrownBy(() -> motor.dejarSinFactura(venta, "ñ".repeat(201), usuario()))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e -> assertThat(e.errors())
                        .containsExactly(Map.entry("motivo", "El motivo tiene hasta 200 caracteres.")));
        assertThat(estado(vigenteDe(venta))).isEqualTo("RECHAZADO");
        assertThatCode(() -> motor.dejarSinFactura(venta, "ñ".repeat(199) + "😀", usuario())).doesNotThrowAnyException();
        assertThat(estado(vigenteDe(venta))).isEqualTo("SIN_FACTURA");
    }

    // ---- Reintentar ----

    @Test
    @DisplayName("reintentar una rechazada la deja como historia, con sus errores, y da de alta otra PENDIENTE con la copia "
            + "de hoy (el emisor de CU-01 actual, el cliente nuevo y la letra recalculada) y la fecha de la venta; el motor "
            + "la autoriza")
    void reintentarUnaRechazada() {
        long venta = ventaRechazada();
        long viejo = vigenteDe(venta);
        jdbc.update("update ferreteria set razon_social = 'Ferretería Nueva SRL' where id = ?", ferreteriaId);
        reloj.avanzar(Duration.ofHours(2));

        long nuevo = enLaVenta(() -> motor.reintentar(new AltaFiscal(venta, CORRALON, RENGLONES), false));

        ComprobanteFiscal historia = pasos.registro(viejo);
        assertThat(historia.getEstado().name()).isEqualTo("RECHAZADO");
        assertThat(historia.getReemplazadoEn()).isEqualTo(AHORA.plus(Duration.ofHours(2)));
        assertThat(historia.getObservaciones()).contains("10015");
        assertThat(historia.getLetra().name()).isEqualTo("B");
        ComprobanteFiscal r = pasos.registro(nuevo);
        assertThat(r.getComprobanteId()).isEqualTo(venta);
        assertThat(r.esVigente()).isTrue();
        assertThat(r.getEstado().name()).isEqualTo("PENDIENTE");
        assertThat(r.getLetra().name()).isEqualTo("A");
        assertThat(r.getCbteTipo()).isEqualTo(FACTURA_A);
        assertThat(r.getReceptorDocNro()).isEqualTo(30786013301L);
        assertThat(r.getEmisorRazonSocial()).isEqualTo("Ferretería Nueva SRL");
        assertThat(r.getRegistradoEn()).isEqualTo(AHORA);
        assertThat(r.isTicketProvisorio()).isFalse();
        assertThat(pasos.alicuotas(nuevo)).singleElement().satisfies(a -> assertThat(a.getIvaId()).isEqualTo(5));
        assertThat(pasos.alicuotas(viejo)).hasSize(1);

        assertThat(motor.atender(Ambiente.SIMULADOR, 1, FACTURA_A)).isEqualTo(Atencion.TERMINADA);
        assertThat(registro(nuevo)).containsEntry("estado", "AUTORIZADO").containsEntry("numero", 1L)
                .containsEntry("cbte_fch", java.sql.Date.valueOf(HOY));
    }

    @Test
    @DisplayName("lo que lee el registro de una venta lee el vigente: el estado, el resumen del listado, la hoja, la búsqueda "
            + "por número fiscal y el registro de la venta")
    void losLectoresLeenElVigente() {
        long venta = ventaRechazada();
        long nuevo = enLaVenta(() -> motor.reintentar(new AltaFiscal(venta, ReceptorFiscal.sinCliente(), RENGLONES),
                false));
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);

        assertThat(registros.deComprobanteScoped(venta)).get().extracting(ComprobanteFiscal::getId).isEqualTo(nuevo);
        assertThat(facturacion.estado(venta)).get().extracting(EstadoFiscalVenta::estado, EstadoFiscalVenta::numero)
                .containsExactly("AUTORIZADO", 1L);
        assertThat(facturacion.resumenes(List.of(venta))).containsOnlyKeys(venta).extractingByKey(venta)
                .extracting(ResumenFiscal::estado).isEqualTo("AUTORIZADO");
        assertThat(facturacion.documento(venta)).get().extracting(d -> d.estadoFiscal().estado()).isEqualTo("AUTORIZADO");
        assertThat(facturacion.ventasConNumeroFiscal(1, 1)).containsExactly(venta);
    }

    @Test
    @DisplayName("si la fecha de la venta ya no entra en ARCA, sin confirmar da FECHA_NUEVA_REQUERIDA y no cambia nada; "
            + "confirmada, el registro nuevo lleva la hora del reintento y sale con la fecha de hoy")
    void fueraDeLaVentanaPideConfirmarLaFechaDeHoy() {
        long venta = ventaFueraDeFecha();
        List<Map<String, Object>> antes = jdbc.queryForList("select * from comprobante_fiscal where comprobante_id = ?",
                venta);

        assertThatThrownBy(() -> enLaVenta(() -> motor.reintentar(new AltaFiscal(venta, ReceptorFiscal.sinCliente(),
                RENGLONES), false))).isInstanceOfSatisfying(AccionFiscalImpedidaException.class,
                        e -> assertThat(e.impedimento()).isEqualTo(Impedimento.FECHA_NUEVA_REQUERIDA))
                .hasMessage("La fecha de la venta ya no entra en ARCA. Confirmá que la factura salga con la fecha de hoy.");
        assertThat(jdbc.queryForList("select * from comprobante_fiscal where comprobante_id = ?", venta)).isEqualTo(antes);

        reloj.avanzar(Duration.ofMinutes(5));
        long nuevo = enLaVenta(() -> motor.reintentar(new AltaFiscal(venta, ReceptorFiscal.sinCliente(), RENGLONES), true));

        assertThat(pasos.registro(nuevo).getRegistradoEn()).isEqualTo(AHORA.plus(Duration.ofMinutes(5)));
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        assertThat(registro(nuevo)).containsEntry("estado", "AUTORIZADO").containsEntry("cbte_fch", java.sql.Date.valueOf(HOY));
    }

    @Test
    @DisplayName("la fecha de la venta tampoco entra si la serie ya autorizó una factura de un día posterior: pide confirmar")
    void laFechaNoEntraSiLaSerieYaAutorizoUnaPosterior() {
        long venta = venta(ferreteriaId);
        motor.registrar(new AltaFiscal(venta, ReceptorFiscal.sinCliente(), RENGLONES), AHORA.minus(Duration.ofDays(2)));
        wsfe.fallas().rechazarConObservaciones(new Obs(10015, "Receptor no identificado"));
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        registrarFacturaB();
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);

        assertThatThrownBy(() -> enLaVenta(() -> motor.reintentar(new AltaFiscal(venta, ReceptorFiscal.sinCliente(),
                RENGLONES), false))).isInstanceOfSatisfying(AccionFiscalImpedidaException.class,
                        e -> assertThat(e.impedimento()).isEqualTo(Impedimento.FECHA_NUEVA_REQUERIDA));
    }

    @Test
    @DisplayName("A1: con un pendiente de un día posterior en la serie, la fecha de la venta ya no entra (iría detrás y ARCA "
            + "la rechazaría): el detalle lo marca, sin confirmar da FECHA_NUEVA_REQUERIDA y no cambia nada; confirmada, el "
            + "motor autoriza las dos")
    void laFechaNoEntraSiLaSerieTieneUnPendientePosterior() {
        long venta = venta(ferreteriaId);
        motor.registrar(new AltaFiscal(venta, ReceptorFiscal.sinCliente(), RENGLONES), AHORA.minus(Duration.ofDays(2)));
        wsfe.fallas().rechazarConObservaciones(new Obs(10015, "Receptor no identificado"));
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        long pendiente = registrarFacturaB();
        List<Map<String, Object>> antes = jdbc.queryForList("select * from comprobante_fiscal where comprobante_id = ?",
                venta);

        assertThat(pasos.fechaEntraHoy(pasos.registro(vigenteDe(venta)))).isFalse();
        assertThatThrownBy(() -> enLaVenta(() -> motor.reintentar(new AltaFiscal(venta, ReceptorFiscal.sinCliente(),
                RENGLONES), false))).isInstanceOfSatisfying(AccionFiscalImpedidaException.class,
                        e -> assertThat(e.impedimento()).isEqualTo(Impedimento.FECHA_NUEVA_REQUERIDA));
        assertThat(jdbc.queryForList("select * from comprobante_fiscal where comprobante_id = ?", venta)).isEqualTo(antes);

        long nuevo = enLaVenta(() -> motor.reintentar(new AltaFiscal(venta, ReceptorFiscal.sinCliente(), RENGLONES), true));
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        assertThat(registro(pendiente)).containsEntry("estado", "AUTORIZADO").containsEntry("numero", 1L);
        assertThat(registro(nuevo)).containsEntry("estado", "AUTORIZADO").containsEntry("numero", 2L);
    }

    @Test
    @DisplayName("A1: un pendiente del mismo día (o de uno anterior) no impide reintentar con la fecha de la venta")
    void unPendienteDelMismoDiaNoImpide() {
        long venta = venta(ferreteriaId);
        Instant haceDosDias = AHORA.minus(Duration.ofDays(2));
        motor.registrar(new AltaFiscal(venta, ReceptorFiscal.sinCliente(), RENGLONES), haceDosDias);
        wsfe.fallas().rechazarConObservaciones(new Obs(10015, "Receptor no identificado"));
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        long pendiente = motor.registrar(new AltaFiscal(venta(ferreteriaId), ReceptorFiscal.sinCliente(), RENGLONES),
                haceDosDias.plus(Duration.ofHours(3)));

        assertThat(pasos.fechaEntraHoy(pasos.registro(vigenteDe(venta)))).isTrue();
        long nuevo = enLaVenta(() -> motor.reintentar(new AltaFiscal(venta, ReceptorFiscal.sinCliente(), RENGLONES),
                false));

        assertThat(pasos.registro(nuevo).getRegistradoEn()).isEqualTo(haceDosDias);
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        assertThat(registro(pendiente)).containsEntry("estado", "AUTORIZADO");
        assertThat(registro(nuevo)).containsEntry("estado", "AUTORIZADO");
    }

    @Test
    @DisplayName("con la facturación frenada da FACTURACION_FRENADA «La facturación está frenada: <motivo>.» (C2), con el "
            + "motivo de la regla del alta en corto, y no cambia nada")
    void conLaFacturacionFrenada() {
        long venta = ventaRechazada();
        jdbc.update("update config_arca set facturacion_habilitada = false, habilitada_en = null where ferreteria_id = ?",
                ferreteriaId);

        assertThatThrownBy(() -> enLaVenta(() -> motor.reintentar(new AltaFiscal(venta, ReceptorFiscal.sinCliente(),
                RENGLONES), true))).isInstanceOfSatisfying(AccionFiscalImpedidaException.class,
                        e -> assertThat(e.impedimento()).isEqualTo(Impedimento.FACTURACION_FRENADA))
                .hasMessage("La facturación está frenada: no está habilitada en esta ferretería (la habilita el super "
                        + "admin).");
        assertThat(jdbc.queryForList("select estado, reemplazado_en from comprobante_fiscal where comprobante_id = ?",
                venta)).singleElement().satisfies(fila -> assertThat(fila).containsEntry("estado", "RECHAZADO")
                        .containsEntry("reemplazado_en", null));
    }

    @Test
    @DisplayName("exige la transacción de quien llama (la de la venta, con el cliente tomado con lock)")
    void exigeLaTransaccionDeQuienLlama() {
        long venta = ventaRechazada();

        assertThatThrownBy(() -> motor.reintentar(new AltaFiscal(venta, ReceptorFiscal.sinCliente(), RENGLONES), false))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("dos reintentos a la vez de la misma rechazada: uno da de alta el registro nuevo y el otro "
            + "SIN_FACTURA_RECHAZADA; queda uno solo vigente")
    void dosReintentosALaVezDejanUnoSoloVigente() throws Exception {
        long venta = ventaRechazada();
        CountDownLatch largada = new CountDownLatch(1);
        Supplier<Object> reintento = () -> {
            TenantContext.setCurrentFerreteriaId(ferreteriaId);
            try {
                largada.await();
                return enLaVenta(() -> motor.reintentar(new AltaFiscal(venta, ReceptorFiscal.sinCliente(), RENGLONES),
                        false));
            } catch (AccionFiscalImpedidaException e) {
                return e.impedimento();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return e;
            } finally {
                TenantContext.clear();
            }
        };
        CompletableFuture<Object> a = CompletableFuture.supplyAsync(reintento);
        CompletableFuture<Object> b = CompletableFuture.supplyAsync(reintento);
        largada.countDown();

        List<Object> resultados = List.of(a.get(), b.get());
        assertThat(resultados).filteredOn(Long.class::isInstance).hasSize(1);
        assertThat(resultados).filteredOn(r -> r == Impedimento.SIN_FACTURA_RECHAZADA).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from comprobante_fiscal where comprobante_id = ? "
                + "and reemplazado_en is null", Integer.class, venta)).isOne();
        assertThat(jdbc.queryForObject("select count(*) from comprobante_fiscal where comprobante_id = ?", Integer.class,
                venta)).isEqualTo(2);
    }

    // ---- Ayudas ----

    /** Una venta con su Factura B a Consumidor Final rechazada por ARCA (10015). */
    private long ventaRechazada() {
        long venta = venta(ferreteriaId);
        motor.registrar(new AltaFiscal(venta, ReceptorFiscal.sinCliente(), RENGLONES));
        wsfe.fallas().rechazarConObservaciones(new Obs(10015, "Receptor no identificado"));
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        assertThat(estado(vigenteDe(venta))).isEqualTo("RECHAZADO");
        return venta;
    }

    /** Una venta de hace seis días: su CbteFch ya no entra en la ventana y el motor la deja FUERA_DE_FECHA. */
    private long ventaFueraDeFecha() {
        long venta = venta(ferreteriaId);
        Instant haceSeisDias = AHORA.minus(Duration.ofDays(6));
        motor.registrar(new AltaFiscal(venta, ReceptorFiscal.sinCliente(), RENGLONES), haceSeisDias);
        assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        assertThat(estado(vigenteDe(venta))).isEqualTo("FUERA_DE_FECHA");
        return venta;
    }

    private long vigenteDe(long venta) {
        return jdbc.queryForObject("select id from comprobante_fiscal where comprobante_id = ? and reemplazado_en is null",
                Long.class, venta);
    }

    private long usuario() {
        return jdbc.queryForObject("select min(id) from usuario where ferreteria_id = ?", Long.class, ferreteriaId);
    }

    private <T> T enLaVenta(Supplier<T> trabajo) {
        return new TransactionTemplate(transacciones).execute(s -> trabajo.get());
    }
}
