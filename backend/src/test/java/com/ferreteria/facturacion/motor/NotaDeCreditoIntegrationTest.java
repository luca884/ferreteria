package com.ferreteria.facturacion.motor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.common.exception.DatosInvalidosException;
import com.ferreteria.facturacion.api.FacturaImpedidaException;
import com.ferreteria.facturacion.api.FacturaImpedidaException.Impedimento;
import com.ferreteria.facturacion.api.FacturacionParaVenta;
import com.ferreteria.facturacion.api.FacturacionParaVenta.CondicionIva;
import com.ferreteria.facturacion.api.FacturacionParaVenta.DocumentoFiscal;
import com.ferreteria.facturacion.api.FacturacionParaVenta.EstadoFiscalVenta;
import com.ferreteria.facturacion.api.FacturacionParaVenta.ReceptorVenta;
import com.ferreteria.facturacion.api.FacturacionParaVenta.RenglonParaFacturar;
import com.ferreteria.facturacion.api.FacturacionParaVenta.ResumenFiscal;
import com.ferreteria.facturacion.api.FacturacionParaVenta.TipoDocumento;
import com.ferreteria.facturacion.api.FacturacionParaVenta.ViaDeAnulacion;
import com.ferreteria.facturacion.documento.LectorQr;
import com.ferreteria.facturacion.model.Alicuota;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.model.ComprobanteFiscal;
import com.ferreteria.facturacion.model.CondicionIvaReceptor;
import com.ferreteria.facturacion.model.ReceptorFiscal;
import com.ferreteria.facturacion.model.RenglonFiscal;
import com.ferreteria.facturacion.model.TipoDocumentoReceptor;
import com.ferreteria.facturacion.repository.ConfigArcaRepository;
import com.ferreteria.facturacion.wsfe.CbteAsoc;
import com.ferreteria.facturacion.wsfe.FeCaeDetRequest;
import com.ferreteria.facturacion.wsfe.FeCaeReq;
import com.ferreteria.facturacion.wsfe.FeCaeResponse;
import com.ferreteria.facturacion.wsfe.Obs;
import com.ferreteria.facturacion.wsfe.Resultado;
import com.ferreteria.support.BarreraDeLocks;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * La nota de crédito fiscal y la anulación interna en el puerto de facturación (CU-08, rama 8; cu-08-nota-de-credito T3:
 * S3 y S8, C1), contra MySQL real y el simulador: {@code tomarParaAnular} decide la vía con el registro de la factura tomado,
 * {@code registrarNotaDeCredito} da de alta la NC copiada de la factura (letra, receptor, emisor, importes, alícuotas y el
 * asociado) en su propia serie, {@code autorizar} le pide el CAE con el motor de siempre, {@code cerrarSinCae} deja sin
 * factura lo que no tiene CAE con el mismo UPDATE condicional que el job, y «Reintentar» de una NC vuelve a copiar de la
 * factura. Las transacciones de la venta son un {@link TransactionTemplate}; la NC es un comprobante cualquiera de la
 * ferretería (facturación no mira su tipo). Misma combinación de contexto que {@code FacturacionParaVentaIntegrationTest}.
 */
@TestPropertySource(properties = "app.facturacion.venta.espera=2s")
class NotaDeCreditoIntegrationTest extends MotorFiscalTestBase {

    private static final List<RenglonFiscal> RENGLONES = List.of(new RenglonFiscal(Alicuota.IVA_21, new BigDecimal("121.00")),
            new RenglonFiscal(Alicuota.IVA_10_5, new BigDecimal("110.50")));
    private static final ReceptorFiscal CORRALON = new ReceptorFiscal(CondicionIvaReceptor.RESPONSABLE_INSCRIPTO,
            TipoDocumentoReceptor.CUIT, 30786013301L, "Corralón Sur SA", "Av. Colón 2000, Mar del Plata");
    /** El cliente «de hoy» que pasa ventas al reintentar: la NC no lo usa. */
    private static final ReceptorVenta OTRO_CLIENTE = new ReceptorVenta(CondicionIva.RESPONSABLE_INSCRIPTO,
            TipoDocumento.CUIT, "30786013301", "Otro Cliente SA", "Av. Luro 1");
    private static final String ESPERAR_CAE = "Esperá el CAE de la factura para anularla.";
    private static final String OTRO_AMBIENTE =
            "La factura es de otro ambiente de ARCA: no se puede anular con nota de crédito.";

    @Autowired
    FacturacionParaVenta facturacion;
    @Autowired
    PlatformTransactionManager transacciones;
    @Autowired
    PasosFiscales pasos;
    @Autowired
    ConfigArcaRepository configs;

    // ---- La NC de una factura autorizada (S3) ----

    @ParameterizedTest(name = "NC {0}")
    @CsvSource({"A, 1, 3", "B, 6, 8", "C, 11, 13"})
    @DisplayName("NC A, B y C: lleva la letra, el receptor, el emisor, los importes y las alícuotas de la factura, en su "
            + "propia serie (CbteTipo 3, 8 o 13) con el asociado (tipo, punto de venta, número, CUIT y CbteFch), y el "
            + "simulador la autoriza")
    void notaDeCreditoDeCadaLetra(String letra, int tipoFactura, int tipoNota) {
        Factura f = facturaAutorizada(letra);
        long nota = venta(ferreteriaId);
        reloj.avanzar(Duration.ofHours(3));
        jdbc.update("update ferreteria set razon_social = 'Ferretería Nueva SRL' where id = ?", ferreteriaId);

        long registroNota = enLaVenta(() -> {
            assertThat(facturacion.tomarParaAnular(f.venta())).isEqualTo(ViaDeAnulacion.NOTA_DE_CREDITO);
            return facturacion.registrarNotaDeCredito(nota, f.venta());
        });

        Map<String, Object> nc = registro(registroNota);
        Map<String, Object> factura = registro(f.registro());
        assertThat(nc).containsEntry("comprobante_id", nota).containsEntry("clase", "NOTA_CREDITO")
                .containsEntry("letra", letra).containsEntry("cbte_tipo", tipoNota).containsEntry("estado", "PENDIENTE")
                .containsEntry("ambiente", "SIMULADOR").containsEntry("punto_venta", 1)
                .containsEntry("asociado_id", f.registro()).containsEntry("asociado_cbte_tipo", tipoFactura)
                .containsEntry("asociado_punto_venta", 1).containsEntry("asociado_numero", 1L)
                .containsEntry("asociado_cuit", String.valueOf(cuit))
                .containsEntry("asociado_cbte_fch", Date.valueOf(HOY))
                .containsEntry("registrado_en", LocalDateTime.ofInstant(AHORA.plus(Duration.ofHours(3)), ZoneOffset.UTC));
        for (String copiada : List.of("receptor_doc_tipo", "receptor_doc_nro", "receptor_condicion_iva_id", "receptor_nombre",
                "receptor_domicilio", "emisor_cuit", "emisor_razon_social", "emisor_condicion_iva",
                "emisor_domicilio_comercial", "emisor_ingresos_brutos", "emisor_inicio_actividades", "imp_total",
                "imp_neto", "imp_iva", "imp_tot_conc", "imp_op_ex")) {
            assertThat(nc.get(copiada)).as(copiada).isEqualTo(factura.get(copiada));
        }
        assertThat(alicuotas(registroNota)).isEqualTo(alicuotas(f.registro()));

        assertThat(facturacion.autorizar(nota)).get()
                .extracting(EstadoFiscalVenta::estado, EstadoFiscalVenta::clase, EstadoFiscalVenta::letra,
                        EstadoFiscalVenta::numero)
                .containsExactly("AUTORIZADO", "NOTA_CREDITO", letra, 1L);
        var enArca = simulador.feCompConsultar(auth(), tipoNota, 1, 1).resultGet();
        assertThat(enArca.detalle().cbtesAsoc())
                .containsExactly(new CbteAsoc(tipoFactura, 1, 1, String.valueOf(cuit), HOY));
        assertThat(enArca.observaciones()).isEmpty();
        // La serie de la factura sigue en lo suyo.
        assertThat(ultimoEnArca(tipoFactura)).isOne();
    }

    @Test
    @DisplayName("el simulador rechaza el pedido de la NC sin su asociado (10197) o asociado a una factura de otra clase "
            + "(10040), y lo autoriza tal como lo arma el motor")
    void elSimuladorRechazaUnaNcSinAsociadoODeOtraClase() {
        Factura f = facturaAutorizada("B");
        long registroNota = notaDe(f);
        ComprobanteFiscal nc = pasos.registro(registroNota);
        FeCaeReq pedido = PedidoFiscal.armar(nc, pasos.alicuotas(registroNota), 1, HOY);
        FeCaeDetRequest det = pedido.feDetReq();

        assertThat(det.cbtesAsoc()).containsExactly(new CbteAsoc(6, 1, 1, String.valueOf(cuit), HOY));
        assertThat(codigos(simulador.feCaeSolicitar(auth(), conAsociados(pedido, List.of())))).containsExactly(10197);
        assertThat(codigos(simulador.feCaeSolicitar(auth(),
                conAsociados(pedido, List.of(new CbteAsoc(1, 1, 1, String.valueOf(cuit), HOY)))))).containsExactly(10040);
        assertThat(simulador.feCaeSolicitar(auth(), pedido).feDetResp().resultado()).isEqualTo(Resultado.A);
    }

    // ---- tomarParaAnular, por estado (C1) ----

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"sin registro", "PENDIENTE", "RECHAZADO", "FUERA_DE_FECHA", "SIN_FACTURA"})
    @DisplayName("sin registro, o con uno PENDIENTE, RECHAZADO, FUERA_DE_FECHA o SIN_FACTURA, la vía es la anulación interna")
    void sinCaeEsLaAnulacionInterna(String caso) {
        long venta = ventaEn(caso);

        assertThat(enLaVenta(() -> facturacion.tomarParaAnular(venta))).isEqualTo(ViaDeAnulacion.ANULACION_INTERNA);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"EN_CURSO", "EN_VERIFICACION", "CAE_NO_COINCIDE", "SERIE_DESINCRONIZADA"})
    @DisplayName("con un envío en curso, en verificación o en revisión da ESPERAR_CAE «Esperá el CAE de la factura para "
            + "anularla.», y no cambia nada")
    void conUnEnvioAbiertoHayQueEsperarElCae(String estado) {
        long venta = venta(ferreteriaId);
        long registro = motor.registrar(new AltaFiscal(venta, ReceptorFiscal.sinCliente(), RENGLONES));
        jdbc.update("update comprobante_fiscal set estado = ?, numero = 1, cbte_fch = ? where id = ?", estado, HOY,
                registro);
        Map<String, Object> antes = registro(registro);

        impedida(() -> facturacion.tomarParaAnular(venta), Impedimento.ESPERAR_CAE, ESPERAR_CAE);
        assertThat(registro(registro)).isEqualTo(antes);
    }

    @Test
    @DisplayName("una factura de otro ambiente no admite NC: OTRO_AMBIENTE (el cerrojo de ambiente), antes que la "
            + "facturación frenada")
    void unaFacturaDeOtroAmbienteNoAdmiteNc() {
        Factura f = facturaAutorizada("B");
        jdbc.update("update config_arca set ambiente = 'HOMOLOGACION', de_prueba = false where ferreteria_id = ?",
                ferreteriaId);

        impedida(() -> facturacion.tomarParaAnular(f.venta()), Impedimento.OTRO_AMBIENTE, OTRO_AMBIENTE);
    }

    @Test
    @DisplayName("con la factura autorizada y la facturación frenada da FACTURACION_FRENADA, con el motivo de «Reintentar»")
    void conLaFacturacionFrenadaNoHayNc() {
        Factura f = facturaAutorizada("B");
        jdbc.update("update config_arca set facturacion_habilitada = false, habilitada_en = null where ferreteria_id = ?",
                ferreteriaId);

        impedida(() -> facturacion.tomarParaAnular(f.venta()), Impedimento.FACTURACION_FRENADA,
                "La facturación está frenada: no está habilitada en esta ferretería (la habilita el super admin).");
    }

    @Test
    @DisplayName("las tres exigen la transacción de quien llama (la de la anulación)")
    void exigenLaTransaccionDeQuienLlama() {
        Factura f = facturaAutorizada("B");
        long nota = venta(ferreteriaId);

        assertThatThrownBy(() -> facturacion.tomarParaAnular(f.venta())).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> facturacion.registrarNotaDeCredito(nota, f.venta()))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> facturacion.cerrarSinCae(f.venta(), "x", usuario()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ---- cerrarSinCae (C1, S9 del lado fiscal) ----

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"PENDIENTE", "RECHAZADO", "FUERA_DE_FECHA"})
    @DisplayName("con un vigente PENDIENTE, RECHAZADO o FUERA_DE_FECHA lo pasa a SIN_FACTURA con el motivo, el usuario y la "
            + "hora, y ya no está en ninguna bandeja ni traba el ambiente")
    void cerrarSinCaeLoDejaSinFactura(String estado) {
        long venta = ventaEn(estado);
        long registro = vigenteDe(venta);
        reloj.avanzar(Duration.ofMinutes(7));

        enLaVenta(() -> {
            assertThat(facturacion.tomarParaAnular(venta)).isEqualTo(ViaDeAnulacion.ANULACION_INTERNA);
            facturacion.cerrarSinCae(venta, "  El cliente devolvió todo  ", usuario());
            return null;
        });

        assertThat(registro(registro)).containsEntry("estado", "SIN_FACTURA")
                .containsEntry("motivo_sin_factura", "El cliente devolvió todo")
                .containsEntry("usuario_sin_factura_id", usuario())
                .containsEntry("sin_factura_en", LocalDateTime.ofInstant(AHORA.plus(Duration.ofMinutes(7)), ZoneOffset.UTC))
                .containsEntry("reemplazado_en", null);
        assertThat(facturacion.ventasEnBandeja(FacturacionParaVenta.Bandeja.PENDIENTES)).doesNotContain(venta);
        assertThat(facturacion.ventasEnBandeja(FacturacionParaVenta.Bandeja.RECHAZADAS)).doesNotContain(venta);
        assertThat(configs.registrosScoped().hayAbiertos()).isFalse();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"sin registro", "SIN_FACTURA"})
    @DisplayName("sin registro, o ya SIN_FACTURA, no hace nada")
    void cerrarSinCaeSinNadaQueCerrar(String caso) {
        long venta = ventaEn(caso);
        List<Map<String, Object>> antes = jdbc.queryForList("select * from comprobante_fiscal where ferreteria_id = ?",
                ferreteriaId);

        assertThatCode(() -> enLaVenta(() -> {
            facturacion.tomarParaAnular(venta);
            facturacion.cerrarSinCae(venta, "Se anula", usuario());
            return null;
        })).doesNotThrowAnyException();
        assertThat(jdbc.queryForList("select * from comprobante_fiscal where ferreteria_id = ?", ferreteriaId))
                .isEqualTo(antes);
    }

    @Test
    @DisplayName("el motivo es obligatorio y de hasta 200 caracteres: 400 en «motivo», sin cambiar nada")
    void elMotivoEsObligatorioYCorto() {
        long venta = ventaEn("PENDIENTE");

        assertThatThrownBy(() -> enLaVenta(() -> {
            facturacion.cerrarSinCae(venta, "   ", usuario());
            return null;
        })).isInstanceOfSatisfying(DatosInvalidosException.class,
                e -> assertThat(e.errors()).containsEntry("motivo", "Contá por qué se anula la venta."));
        assertThatThrownBy(() -> enLaVenta(() -> {
            facturacion.cerrarSinCae(venta, "x".repeat(201), usuario());
            return null;
        })).isInstanceOfSatisfying(DatosInvalidosException.class,
                e -> assertThat(e.errors()).containsEntry("motivo", "El motivo tiene hasta 200 caracteres."));
        assertThat(estado(vigenteDe(venta))).isEqualTo("PENDIENTE");
    }

    @Test
    @DisplayName("un pendiente que el job toma a la vez: si la anulación lo tomó primero, el job queda esperando su lock, "
            + "lo encuentra SIN_FACTURA y nunca lo manda a ARCA")
    void laAnulacionGanaYElJobNoLoManda() throws Exception {
        long venta = ventaEn("PENDIENTE");
        long registro = vigenteDe(venta);
        BarreraDeLocks barrera = new BarreraDeLocks(jdbcRoot());
        long deadlocks = barrera.deadlocksAntes();
        CountDownLatch tomado = new CountDownLatch(1);
        CountDownLatch cerrar = new CountDownLatch(1);

        CompletableFuture<ViaDeAnulacion> anulacion = CompletableFuture.supplyAsync(() -> deLaFerreteria(() ->
                enLaVenta(() -> {
                    ViaDeAnulacion via = facturacion.tomarParaAnular(venta);
                    tomado.countDown();
                    esperar(cerrar);
                    facturacion.cerrarSinCae(venta, "Se anula", usuario());
                    return via;
                })));
        esperar(tomado);
        CompletableFuture<Atencion> job = CompletableFuture.supplyAsync(() -> deLaFerreteria(this::atenderB));
        barrera.esperarBloqueados(1);
        cerrar.countDown();

        assertThat(anulacion.get(30, TimeUnit.SECONDS)).isEqualTo(ViaDeAnulacion.ANULACION_INTERNA);
        assertThat(job.get(30, TimeUnit.SECONDS)).isEqualTo(Atencion.SERIE_PERDIDA);
        assertThat(estado(registro)).isEqualTo("SIN_FACTURA");
        assertThat(intentos(registro)).isEmpty();
        assertThat(wsfe.solicitudes()).isZero();
        assertThat(ultimoEnArca(FACTURA_B)).isZero();
        barrera.assertSinDeadlocks(deadlocks);
    }

    @Test
    @DisplayName("un pendiente que el job ya mandó: la anulación ve ESPERAR_CAE mientras ARCA no contesta, y la NC cuando "
            + "llega el CAE")
    void elJobGanaYLaAnulacionEspera() throws Exception {
        long venta = ventaEn("PENDIENTE");
        long registro = vigenteDe(venta);
        WsfeDePrueba.Pausa pausa = wsfe.pausarElSiguienteFeCaeSolicitar();
        CompletableFuture<Atencion> job = CompletableFuture.supplyAsync(() -> deLaFerreteria(this::atenderB));
        pausa.esperarQueLlegue();

        impedida(() -> facturacion.tomarParaAnular(venta), Impedimento.ESPERAR_CAE, ESPERAR_CAE);
        assertThat(estado(registro)).isEqualTo("EN_CURSO");

        pausa.soltar();
        assertThat(job.get(30, TimeUnit.SECONDS)).isEqualTo(Atencion.TERMINADA);
        assertThat(enLaVenta(() -> facturacion.tomarParaAnular(venta))).isEqualTo(ViaDeAnulacion.NOTA_DE_CREDITO);
    }

    // ---- «Reintentar» de una NC rechazada (S3, L3) ----

    @Test
    @DisplayName("una NC rechazada está en la bandeja y «Reintentar» la reemplaza por otra PENDIENTE copiada de la factura, no "
            + "del cliente ni del emisor de hoy, sin crear otra NC; el motor la autoriza")
    void unaNcRechazadaSeReintentaCopiandoDeLaFactura() {
        Factura f = facturaAutorizada("B");
        long nota = venta(ferreteriaId);
        long rechazada = enLaVenta(() -> {
            facturacion.tomarParaAnular(f.venta());
            return facturacion.registrarNotaDeCredito(nota, f.venta());
        });
        wsfe.fallas().rechazarConObservaciones(new Obs(10237, "Importe mayor que el del comprobante asociado"));
        assertThat(motor.atender(Ambiente.SIMULADOR, 1, 8)).isEqualTo(Atencion.TERMINADA);
        assertThat(estado(rechazada)).isEqualTo("RECHAZADO");
        assertThat(facturacion.ventasEnBandeja(FacturacionParaVenta.Bandeja.RECHAZADAS)).containsExactly(nota);
        jdbc.update("update ferreteria set razon_social = 'Ferretería Nueva SRL' where id = ?", ferreteriaId);
        reloj.avanzar(Duration.ofHours(1));

        long nueva = enLaVenta(() -> facturacion.reintentar(nota, OTRO_CLIENTE,
                List.of(new RenglonParaFacturar(new BigDecimal("21.00"), new BigDecimal("999.00"))), false));

        assertThat(registro(rechazada)).containsEntry("estado", "RECHAZADO")
                .containsEntry("reemplazado_en", LocalDateTime.ofInstant(AHORA.plus(Duration.ofHours(1)), ZoneOffset.UTC));
        Map<String, Object> nc = registro(nueva);
        Map<String, Object> factura = registro(f.registro());
        assertThat(nc).containsEntry("comprobante_id", nota).containsEntry("clase", "NOTA_CREDITO")
                .containsEntry("cbte_tipo", 8).containsEntry("estado", "PENDIENTE").containsEntry("reemplazado_en", null)
                .containsEntry("asociado_id", f.registro()).containsEntry("asociado_numero", 1L)
                .containsEntry("receptor_doc_tipo", 99).containsEntry("emisor_razon_social",
                        factura.get("emisor_razon_social"))
                .containsEntry("imp_total", factura.get("imp_total"))
                .containsEntry("registrado_en", registro(rechazada).get("registrado_en"));
        assertThat(alicuotas(nueva)).isEqualTo(alicuotas(f.registro()));
        assertThat(jdbc.queryForObject("select count(distinct comprobante_id) from comprobante_fiscal where asociado_id = ?",
                Integer.class, f.registro())).isOne();

        assertThat(facturacion.autorizar(nota)).get().extracting(EstadoFiscalVenta::estado).isEqualTo("AUTORIZADO");
    }

    @Test
    @DisplayName("«Reintentar» de una NC de otro ambiente da OTRO_AMBIENTE y no cambia nada")
    void reintentarUnaNcDeOtroAmbiente() {
        Factura f = facturaAutorizada("B");
        long nota = venta(ferreteriaId);
        long rechazada = enLaVenta(() -> {
            facturacion.tomarParaAnular(f.venta());
            return facturacion.registrarNotaDeCredito(nota, f.venta());
        });
        wsfe.fallas().rechazarConObservaciones(new Obs(10237, "Importe mayor que el del comprobante asociado"));
        motor.atender(Ambiente.SIMULADOR, 1, 8);
        jdbc.update("update config_arca set ambiente = 'HOMOLOGACION', de_prueba = false where ferreteria_id = ?",
                ferreteriaId);

        impedida(() -> facturacion.reintentar(nota, OTRO_CLIENTE, List.of(new RenglonParaFacturar(new BigDecimal("21.00"),
                new BigDecimal("121.00"))), true), Impedimento.OTRO_AMBIENTE, OTRO_AMBIENTE);
        assertThat(registro(rechazada)).containsEntry("estado", "RECHAZADO").containsEntry("reemplazado_en", null);
    }

    // ---- La hoja de la NC (S8) ----

    @Test
    @DisplayName("en SIMULADOR la hoja de la NC sale con la marca de siempre, sin QR, como NOTA_CREDITO, y el asociado sin "
            + "parecer fiscal; el resumen y la consulta dicen lo mismo")
    void laHojaDeLaNcEnElSimulador() {
        Factura f = facturaAutorizada("B");
        long nota = venta(ferreteriaId);
        notaDe(f, nota);
        facturacion.autorizar(nota);

        DocumentoFiscal d = facturacion.documento(nota).orElseThrow();
        assertThat(d.estadoFiscal().clase()).isEqualTo("NOTA_CREDITO");
        assertThat(d.estadoFiscal().estado()).isEqualTo("AUTORIZADO");
        assertThat(d.validoComoFactura()).isFalse();
        assertThat(d.letra()).isEqualTo("X");
        assertThat(d.codigo()).isNull();
        assertThat(d.numero()).isNull();
        assertThat(d.cae()).isNull();
        assertThat(d.qr()).isNull();
        assertThat(d.marca()).isEqualTo("SIMULADO — sin validez fiscal");
        assertThat(d.comprobanteAsociado()).isEqualTo("X · SIMULADO — sin validez fiscal");
        assertThat(d.total()).isEqualByComparingTo("231.50");
        assertThat(facturacion.documento(f.venta()).orElseThrow().comprobanteAsociado()).isNull();

        ResumenFiscal resumen = facturacion.resumenes(List.of(nota)).get(nota);
        assertThat(resumen.clase()).isEqualTo("NOTA_CREDITO");
        assertThat(resumen.codigo()).isNull();
        assertThat(resumen.comprobanteAsociado()).isEqualTo("X · SIMULADO — sin validez fiscal");
        assertThat(facturacion.resumenes(List.of(f.venta())).get(f.venta()))
                .extracting(ResumenFiscal::clase, ResumenFiscal::comprobanteAsociado).containsExactly("FACTURA", null);
        assertThat(facturacion.consulta(nota).orElseThrow().documento().comprobanteAsociado())
                .isEqualTo("X · SIMULADO — sin validez fiscal");
    }

    @Test
    @DisplayName("en PRODUCCION la hoja de la NC autorizada tiene la letra, el código 8, el número, el CAE, el QR con su "
            + "tipo y la factura asociada «Factura B 00001-00000012»")
    void laHojaDeLaNcEnProduccion() {
        long venta = venta(ferreteriaId);
        long nota = venta(ferreteriaId);
        long factura = insertarEnProduccion(venta, "FACTURA", 6, 12L, null);
        insertarEnProduccion(nota, "NOTA_CREDITO", 8, 3L, factura);

        DocumentoFiscal d = facturacion.documento(nota).orElseThrow();
        assertThat(d.validoComoFactura()).isTrue();
        assertThat(d.estadoFiscal().clase()).isEqualTo("NOTA_CREDITO");
        assertThat(d.letra()).isEqualTo("B");
        assertThat(d.codigo()).isEqualTo(8);
        assertThat(d.numero()).isEqualTo(3L);
        assertThat(d.cae()).isEqualTo("70417054367476");
        assertThat(d.comprobanteAsociado()).isEqualTo("Factura B 00001-00000012");
        assertThat(LectorQr.datos(d.qr()).path("tipoCmp").asInt()).isEqualTo(8);
        assertThat(LectorQr.datos(d.qr()).path("nroCmp").asLong()).isEqualTo(3L);
        assertThat(facturacion.resumenes(List.of(nota)).get(nota))
                .extracting(ResumenFiscal::clase, ResumenFiscal::codigo, ResumenFiscal::comprobanteAsociado)
                .containsExactly("NOTA_CREDITO", 8, "Factura B 00001-00000012");
        assertThat(facturacion.provisoriasAutorizadas(List.of(nota))).containsEntry(nota, "Nota de crédito B 00001-00000003");
    }

    // ---- Otra ferretería (regla 7 del repo) ----

    @Test
    @DisplayName("otra ferretería no ve la factura ni la NC: para ella la venta no tiene registro, no puede darle NC ni "
            + "cerrarla, y no ve la hoja ni el resumen")
    void otraFerreteriaNoVeNada() {
        Factura f = facturaAutorizada("B");
        long nota = venta(ferreteriaId);
        notaDe(f, nota);
        long otra = ferreteriaConFacturacion(cuitNuevo());
        TenantContext.setCurrentFerreteriaId(otra);
        long notaDeLaOtra = venta(otra);

        assertThat(enLaVenta(() -> facturacion.tomarParaAnular(f.venta()))).isEqualTo(ViaDeAnulacion.ANULACION_INTERNA);
        assertThatThrownBy(() -> enLaVenta(() -> facturacion.registrarNotaDeCredito(notaDeLaOtra, f.venta())))
                .isInstanceOf(IllegalStateException.class);
        enLaVenta(() -> {
            facturacion.cerrarSinCae(nota, "No es mía", usuarioDe(otra));
            return null;
        });
        assertThat(facturacion.documento(nota)).isEmpty();
        assertThat(facturacion.consulta(nota)).isEmpty();
        assertThat(facturacion.resumenes(List.of(nota, f.venta()))).isEmpty();
        TenantContext.setCurrentFerreteriaId(ferreteriaId);
        assertThat(estado(vigenteDe(nota))).isEqualTo("PENDIENTE");
    }

    // ---- Ayudas ----

    /** La venta y el registro de su factura AUTORIZADO, de la letra pedida (C: la ferretería pasa a monotributista). */
    private record Factura(long venta, long registro) {}

    private Factura facturaAutorizada(String letra) {
        long venta = venta(ferreteriaId);
        ReceptorFiscal receptor = letra.equals("A") ? CORRALON : ReceptorFiscal.sinCliente();
        if (letra.equals("C")) {
            jdbc.update("update ferreteria set condicion_iva = 'MONOTRIBUTISTA' where id = ?", ferreteriaId);
        }
        long registro = motor.registrar(new AltaFiscal(venta, receptor, RENGLONES));
        int tipo = switch (letra) {
            case "A" -> 1;
            case "B" -> 6;
            default -> 11;
        };
        assertThat(motor.atender(Ambiente.SIMULADOR, 1, tipo)).isEqualTo(Atencion.TERMINADA);
        assertThat(estado(registro)).isEqualTo("AUTORIZADO");
        return new Factura(venta, registro);
    }

    /** La NC PENDIENTE de la factura, en un comprobante nuevo. */
    private long notaDe(Factura f) {
        return notaDe(f, venta(ferreteriaId));
    }

    private long notaDe(Factura f, long nota) {
        return enLaVenta(() -> {
            facturacion.tomarParaAnular(f.venta());
            return facturacion.registrarNotaDeCredito(nota, f.venta());
        });
    }

    /** Una venta sin registro fiscal o con su Factura B en ese estado. */
    private long ventaEn(String caso) {
        long venta = venta(ferreteriaId);
        if (caso.equals("sin registro")) {
            return venta;
        }
        if (caso.equals("FUERA_DE_FECHA")) {
            motor.registrar(new AltaFiscal(venta, ReceptorFiscal.sinCliente(), RENGLONES), AHORA.minus(Duration.ofDays(6)));
            assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        } else {
            motor.registrar(new AltaFiscal(venta, ReceptorFiscal.sinCliente(), RENGLONES));
        }
        if (caso.equals("RECHAZADO") || caso.equals("SIN_FACTURA")) {
            wsfe.fallas().rechazarConObservaciones(new Obs(10015, "Receptor no identificado"));
            assertThat(atenderB()).isEqualTo(Atencion.TERMINADA);
        }
        if (caso.equals("SIN_FACTURA")) {
            motor.dejarSinFactura(venta, "El cliente no volvió", usuario());
        }
        assertThat(estado(vigenteDe(venta))).isEqualTo(caso);
        return venta;
    }

    /** Un registro de producción AUTORIZADO de una B a Consumidor Final, insertado por SQL (no hay adaptador de producción). */
    private long insertarEnProduccion(long comprobante, String clase, int cbteTipo, long numero, Long asociado) {
        boolean nota = asociado != null;
        jdbc.update("insert into comprobante_fiscal (ferreteria_id, comprobante_id, ambiente, clase, letra, cbte_tipo, "
                + "punto_venta, numero, cbte_fch, estado, cae, cae_vencimiento, resultado, ticket_provisorio, "
                + "receptor_doc_tipo, receptor_doc_nro, receptor_condicion_iva_id, emisor_cuit, emisor_razon_social, "
                + "emisor_condicion_iva, emisor_domicilio_comercial, emisor_ingresos_brutos, emisor_inicio_actividades, "
                + "imp_total, imp_neto, imp_iva, imp_tot_conc, imp_op_ex, registrado_en, asociado_id, asociado_cbte_tipo, "
                + "asociado_punto_venta, asociado_numero, asociado_cuit, asociado_cbte_fch) values (?, ?, 'PRODUCCION', ?, "
                + "'B', ?, 1, ?, ?, 'AUTORIZADO', '70417054367476', ?, 'A', ?, 99, 0, 5, '30-71234567-8', 'El Tornillo SRL', "
                + "'RESPONSABLE_INSCRIPTO', 'Av. Luro 1234, Mar del Plata', '901-765432-1', '2015-03-01', 121.00, 100.00, "
                + "21.00, 0, 0, ?, ?, ?, ?, ?, ?, ?)",
                ferreteriaId, comprobante, clase, cbteTipo, numero, HOY, HOY.plusDays(10), nota,
                LocalDateTime.ofInstant(AHORA, ZoneOffset.UTC), asociado, nota ? 6 : null, nota ? 1 : null,
                nota ? 12L : null, nota ? "30712345678" : null, nota ? HOY : null);
        long id = jdbc.queryForObject("select id from comprobante_fiscal where comprobante_id = ?", Long.class, comprobante);
        jdbc.update("insert into comprobante_fiscal_alicuota (ferreteria_id, comprobante_fiscal_id, iva_id, base_imp, "
                + "importe) values (?, ?, 5, 100.00, 21.00)", ferreteriaId, id);
        return id;
    }

    /** El desglose del registro, sin sus ids: lo que se copia. */
    private List<Map<String, Object>> alicuotas(long registro) {
        return jdbc.queryForList("select iva_id, base_imp, importe from comprobante_fiscal_alicuota "
                + "where comprobante_fiscal_id = ? order by iva_id", registro);
    }

    private static FeCaeReq conAsociados(FeCaeReq pedido, List<CbteAsoc> asociados) {
        FeCaeDetRequest d = pedido.feDetReq();
        return new FeCaeReq(pedido.feCabReq(), new FeCaeDetRequest(d.concepto(), d.docTipo(), d.docNro(), d.cbteDesde(),
                d.cbteHasta(), d.cbteFch(), d.impTotal(), d.impTotConc(), d.impNeto(), d.impOpEx(), d.impTrib(),
                d.impIva(), d.monId(), d.monCotiz(), d.condicionIvaReceptorId(), d.iva(), asociados, d.periodoAsoc()));
    }

    /** Los códigos con que el simulador rechazó el comprobante (las Obs del detalle). */
    private static List<Integer> codigos(FeCaeResponse respuesta) {
        assertThat(respuesta.feDetResp().resultado()).isEqualTo(Resultado.R);
        return respuesta.feDetResp().observaciones().stream().map(Obs::code).toList();
    }

    private void impedida(Supplier<?> accion, Impedimento impedimento, String mensaje) {
        assertThatThrownBy(() -> enLaVenta(accion)).isInstanceOfSatisfying(FacturaImpedidaException.class,
                e -> assertThat(e.impedimento()).isEqualTo(impedimento)).hasMessage(mensaje);
    }

    private long vigenteDe(long comprobante) {
        return jdbc.queryForObject("select id from comprobante_fiscal where comprobante_id = ? and reemplazado_en is null",
                Long.class, comprobante);
    }

    private long usuario() {
        return usuarioDe(ferreteriaId);
    }

    private long usuarioDe(long ferreteria) {
        return jdbc.queryForObject("select min(id) from usuario where ferreteria_id = ?", Long.class, ferreteria);
    }

    private <T> T deLaFerreteria(Supplier<T> trabajo) {
        TenantContext.setCurrentFerreteriaId(ferreteriaId);
        try {
            return trabajo.get();
        } finally {
            TenantContext.clear();
        }
    }

    private static void esperar(CountDownLatch latch) {
        try {
            assertThat(latch.await(30, TimeUnit.SECONDS)).as("el otro hilo llegó a tiempo").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private <T> T enLaVenta(Supplier<T> trabajo) {
        return new TransactionTemplate(transacciones).execute(s -> trabajo.get());
    }
}
