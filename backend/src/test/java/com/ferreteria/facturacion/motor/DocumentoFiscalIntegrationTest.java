package com.ferreteria.facturacion.motor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.ferreteria.facturacion.api.FacturacionParaVenta;
import com.ferreteria.facturacion.api.FacturacionParaVenta.AlicuotaDelDocumento;
import com.ferreteria.facturacion.api.FacturacionParaVenta.CondicionIva;
import com.ferreteria.facturacion.api.FacturacionParaVenta.DocumentoFiscal;
import com.ferreteria.facturacion.api.FacturacionParaVenta.EmisorDelDocumento;
import com.ferreteria.facturacion.api.FacturacionParaVenta.EstadoFiscalVenta;
import com.ferreteria.facturacion.api.FacturacionParaVenta.FacturacionDelMostrador;
import com.ferreteria.facturacion.api.FacturacionParaVenta.ReceptorDelDocumento;
import com.ferreteria.facturacion.api.FacturacionParaVenta.ReceptorVenta;
import com.ferreteria.facturacion.api.FacturacionParaVenta.RenglonParaFacturar;
import com.ferreteria.facturacion.api.FacturacionParaVenta.ResumenFiscal;
import com.ferreteria.facturacion.api.FacturacionParaVenta.Transparencia;
import com.ferreteria.facturacion.documento.LectorQr;
import com.ferreteria.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * El documento imprimible de la venta y lo que el cobro y el listado leen de {@code facturacion}
 * (cu-07-factura-mostrador S3, S5, S6, S7, S12, S14, S15 y C1 a C4), contra MySQL real. Los registros de producción y
 * homologación se insertan por SQL respetando los CHECK de V32: todavía no hay adaptador de producción (rama 6), y el
 * cerrojo de la apariencia se prueba igual. La copia del emisor de cada registro es distinta de los datos de hoy de la
 * ferretería, así se ve que la hoja lee solo la copia. Misma combinación de contexto que
 * {@code FacturacionParaVentaIntegrationTest}.
 */
@TestPropertySource(properties = "app.facturacion.venta.espera=2s")
class DocumentoFiscalIntegrationTest extends MotorFiscalTestBase {

    private static final String CUIT_RECEPTOR = "30786013301";
    private static final String CAE = "70417054367476";
    private static final LocalDate CBTE_FCH = LocalDate.of(2026, 10, 27);
    private static final LocalDate VENCIMIENTO = LocalDate.of(2026, 11, 6);
    private static final String LEYENDA_LEY_27618 = "El crédito fiscal discriminado en el presente comprobante, sólo "
            + "podrá ser computado a efectos del Régimen de Sostenimiento e Inclusión Fiscal para Pequeños Contribuyentes "
            + "de la Ley Nº 27.618";
    /** La copia del emisor en los registros insertados: no es la de la ferretería de hoy. */
    private static final EmisorDelDocumento EMISOR_RI = new EmisorDelDocumento("El Tornillo SRL", "30712345678",
            "IVA RESPONSABLE INSCRIPTO", "Av. Luro 1234, Mar del Plata", "901-765432-1", LocalDate.of(2015, 3, 1));

    @Autowired
    FacturacionParaVenta facturacion;
    @Autowired
    PlatformTransactionManager transacciones;

    // ---- S5: una hoja por letra, con la copia del registro ----

    @Test
    @DisplayName("Factura A a un Responsable Inscripto: emisor de la copia, letra y código, número, CbteFch, receptor, neto e IVA por alícuota, CAE y QR")
    void facturaAAUnResponsableInscripto() {
        long venta = venta(ferreteriaId);
        Receptor ri = new Receptor(80, Long.parseLong(CUIT_RECEPTOR), 1, "Corralón Sur SA", "Av. Colón 2000");
        insertar(venta, "PRODUCCION", "AUTORIZADO", "A", 3, 12L, ri, "RESPONSABLE_INSCRIPTO",
                new Alic(5, "1000.00", "210.00"), new Alic(4, "200.00", "21.00"));

        DocumentoFiscal d = facturacion.documento(venta).orElseThrow();

        assertThat(d.estadoFiscal()).isEqualTo(new EstadoFiscalVenta("AUTORIZADO", "FACTURA", "A", 3, 12L, CAE, VENCIMIENTO,
                "PRODUCCION"));
        assertThat(d.validoComoFactura()).isTrue();
        assertThat(d.letra()).isEqualTo("A");
        assertThat(d.codigo()).isEqualTo(1);
        assertThat(d.marca()).isNull();
        assertThat(d.puntoVenta()).isEqualTo(3);
        assertThat(d.numero()).isEqualTo(12L);
        assertThat(d.fecha()).isEqualTo(CBTE_FCH);
        assertThat(d.cae()).isEqualTo(CAE);
        assertThat(d.caeVencimiento()).isEqualTo(VENCIMIENTO);
        assertThat(d.emisor()).isEqualTo(EMISOR_RI);
        assertThat(d.receptor()).isEqualTo(new ReceptorDelDocumento("Corralón Sur SA", "IVA RESPONSABLE INSCRIPTO", "CUIT",
                CUIT_RECEPTOR, "Av. Colón 2000", false));
        assertImportes(d, "1431.00", "1200.00", "231.00");
        // El desglose guardado, en el orden de su Id (10,5 % es el 4 y 21 % el 5), con el porcentaje en escala 2.
        assertThat(d.alicuotas()).extracting(AlicuotaDelDocumento::porcentaje).map(BigDecimal::toPlainString)
                .containsExactly("10.50", "21.00");
        assertThat(d.alicuotas()).extracting(AlicuotaDelDocumento::base).map(BigDecimal::toPlainString)
                .containsExactly("200.00", "1000.00");
        assertThat(d.alicuotas()).extracting(AlicuotaDelDocumento::importe).map(BigDecimal::toPlainString)
                .containsExactly("21.00", "210.00");
        assertThat(d.transparencia()).isNull();
        assertThat(d.leyendaMonotributo()).isNull();
        // El QR, con el receptor tal como se envió a ARCA (S14), y la matriz que lo dibuja.
        assertThat(LectorQr.leer(d.qrModulos()).texto()).isEqualTo(d.qr());
        JsonNode qr = LectorQr.datos(d.qr());
        assertThat(qr.get("fecha").asText()).isEqualTo("2026-10-27");
        assertThat(qr.get("cuit").asLong()).isEqualTo(30712345678L);
        assertThat(qr.get("ptoVta").asInt()).isEqualTo(3);
        assertThat(qr.get("tipoCmp").asInt()).isEqualTo(1);
        assertThat(qr.get("nroCmp").asLong()).isEqualTo(12);
        assertThat(qr.get("importe").decimalValue()).isEqualByComparingTo("1431.00");
        assertThat(qr.get("tipoDocRec").asInt()).isEqualTo(80);
        assertThat(qr.get("nroDocRec").asLong()).isEqualTo(Long.parseLong(CUIT_RECEPTOR));
        assertThat(qr.get("codAut").asLong()).isEqualTo(Long.parseLong(CAE));
    }

    @Test
    @DisplayName("Factura A a un Monotributista: «RESPONSABLE MONOTRIBUTO» y la leyenda de la Ley 27.618, textual")
    void facturaAAUnMonotributista() {
        long venta = venta(ferreteriaId);
        insertar(venta, "PRODUCCION", "AUTORIZADO", "A", 3, 12L,
                new Receptor(80, Long.parseLong(CUIT_RECEPTOR), 6, "Pinturería Norte", "Av. Constitución 500"),
                "RESPONSABLE_INSCRIPTO", new Alic(5, "100.00", "21.00"));

        DocumentoFiscal d = facturacion.documento(venta).orElseThrow();

        assertThat(d.letra()).isEqualTo("A");
        assertThat(d.receptor().condicionIva()).isEqualTo("RESPONSABLE MONOTRIBUTO");
        assertThat(d.receptor().aConsumidorFinal()).isFalse();
        assertThat(d.leyendaMonotributo()).isEqualTo(LEYENDA_LEY_27618);
        assertThat(d.transparencia()).isNull();
        assertThat(d.alicuotas()).hasSize(1);
    }

    @Test
    @DisplayName("Factura B a Consumidor Final, sin identificar o con DNI: «A CONSUMIDOR FINAL» y la transparencia de la Ley 27.743 con el IVA contenido")
    void facturaBAConsumidorFinal() {
        long sinIdentificar = venta(ferreteriaId);
        long conDni = venta(ferreteriaId);
        insertar(sinIdentificar, "PRODUCCION", "AUTORIZADO", "B", 3, 12L, Receptor.SIN_IDENTIFICAR,
                "RESPONSABLE_INSCRIPTO", new Alic(5, "1000.00", "210.00"));
        insertar(conDni, "PRODUCCION", "AUTORIZADO", "B", 3, 13L, new Receptor(96, 30123456L, 5, "Juan Pérez", null),
                "RESPONSABLE_INSCRIPTO", new Alic(5, "1000.00", "210.00"));

        DocumentoFiscal anonima = facturacion.documento(sinIdentificar).orElseThrow();
        DocumentoFiscal identificada = facturacion.documento(conDni).orElseThrow();

        assertThat(anonima.letra()).isEqualTo("B");
        assertThat(anonima.codigo()).isEqualTo(6);
        assertThat(anonima.receptor()).isEqualTo(new ReceptorDelDocumento(null, "CONSUMIDOR FINAL", null, null, null, true));
        assertThat(identificada.receptor()).isEqualTo(new ReceptorDelDocumento("Juan Pérez", "CONSUMIDOR FINAL", "DNI",
                "30123456", null, true));
        for (DocumentoFiscal d : List.of(anonima, identificada)) {
            assertImportes(d, "1210.00", "1000.00", "210.00");
            assertThat(d.transparencia()).isEqualTo(new Transparencia(new BigDecimal("210.00"), new BigDecimal("0.00")));
            assertThat(d.transparencia().otrosImpuestosNacionalesIndirectos().scale()).isEqualTo(2);
            assertThat(d.leyendaMonotributo()).isNull();
            assertThat(d.alicuotas()).hasSize(1);
        }
        // Sin identificar va a ARCA con 99 y 0, y así va en el QR (S14).
        JsonNode qr = LectorQr.datos(anonima.qr());
        assertThat(qr.get("tipoDocRec").asInt()).isEqualTo(99);
        assertThat(qr.get("nroDocRec").asLong()).isZero();
        assertThat(LectorQr.datos(identificada.qr()).get("tipoDocRec").asInt()).isEqualTo(96);
    }

    @Test
    @DisplayName("Factura B a un Exento: «IVA EXENTO», sin transparencia (no es Consumidor Final)")
    void facturaBAUnExento() {
        long venta = venta(ferreteriaId);
        insertar(venta, "PRODUCCION", "AUTORIZADO", "B", 3, 12L,
                new Receptor(80, Long.parseLong(CUIT_RECEPTOR), 4, "Cooperativa Sur", "Av. Jara 100"),
                "RESPONSABLE_INSCRIPTO", new Alic(5, "100.00", "21.00"));

        DocumentoFiscal d = facturacion.documento(venta).orElseThrow();

        assertThat(d.letra()).isEqualTo("B");
        assertThat(d.receptor().condicionIva()).isEqualTo("IVA EXENTO");
        assertThat(d.receptor().aConsumidorFinal()).isFalse();
        assertThat(d.transparencia()).isNull();
        assertThat(d.leyendaMonotributo()).isNull();
    }

    @ParameterizedTest(name = "emisor {0}")
    @ValueSource(strings = {"MONOTRIBUTISTA", "EXENTO"})
    @DisplayName("Factura C: sin IVA (sin alícuotas, neto = total), sin transparencia ni leyenda, con la condición del emisor")
    void facturaC(String condicionEmisor) {
        long venta = venta(ferreteriaId);
        insertar(venta, "PRODUCCION", "AUTORIZADO", "C", 3, 12L, Receptor.SIN_IDENTIFICAR, condicionEmisor);

        DocumentoFiscal d = facturacion.documento(venta).orElseThrow();

        assertThat(d.letra()).isEqualTo("C");
        assertThat(d.codigo()).isEqualTo(11);
        assertThat(d.emisor().condicionIva())
                .isEqualTo(condicionEmisor.equals("EXENTO") ? "IVA EXENTO" : "RESPONSABLE MONOTRIBUTO");
        assertImportes(d, "1210.00", "1210.00", "0.00");
        assertThat(d.alicuotas()).isEmpty();
        assertThat(d.transparencia()).isNull();
        assertThat(d.leyendaMonotributo()).isNull();
        assertThat(LectorQr.datos(d.qr()).get("tipoCmp").asInt()).isEqualTo(11);
    }

    // ---- S6 y S12: el cerrojo de la apariencia ----

    static Stream<Arguments> noValidos() {
        String simulado = "SIMULADO — sin validez fiscal";
        String homologacion = "HOMOLOGACIÓN — sin validez fiscal";
        String provisorio = "TICKET PROVISORIO — factura en trámite con ARCA";
        String sinFactura = "SIN FACTURA — ARCA no la autorizó";
        return Stream.of(
                Arguments.of("SIMULADOR", "AUTORIZADO", simulado),
                Arguments.of("SIMULADOR", "PENDIENTE", simulado),
                Arguments.of("HOMOLOGACION", "AUTORIZADO", homologacion),
                Arguments.of("HOMOLOGACION", "RECHAZADO", homologacion),
                Arguments.of("PRODUCCION", "PENDIENTE", provisorio),
                Arguments.of("PRODUCCION", "EN_CURSO", provisorio),
                Arguments.of("PRODUCCION", "EN_VERIFICACION", provisorio),
                Arguments.of("PRODUCCION", "RECHAZADO", sinFactura),
                Arguments.of("PRODUCCION", "FUERA_DE_FECHA", sinFactura),
                Arguments.of("PRODUCCION", "CAE_NO_COINCIDE", sinFactura),
                Arguments.of("PRODUCCION", "SERIE_DESINCRONIZADA", sinFactura));
    }

    @ParameterizedTest(name = "{0} {1} → «{2}»")
    @MethodSource("noValidos")
    @DisplayName("fuera de producción, o sin CAE, sale un X: sin letra A/B/C, código, número, CAE ni QR, con la marca del caso")
    void loQueNoEsValidoSaleX(String ambiente, String estado, String marca) {
        long venta = venta(ferreteriaId);
        insertar(venta, ambiente, estado, "B", 1, 12L, Receptor.SIN_IDENTIFICAR, "RESPONSABLE_INSCRIPTO",
                new Alic(5, "1000.00", "210.00"));

        DocumentoFiscal d = facturacion.documento(venta).orElseThrow();

        assertThat(d.validoComoFactura()).isFalse();
        assertThat(d.letra()).isEqualTo("X");
        assertThat(d.marca()).isEqualTo(marca);
        assertThat(d.codigo()).isNull();
        assertThat(d.puntoVenta()).isNull();
        assertThat(d.numero()).isNull();
        assertThat(d.cae()).isNull();
        assertThat(d.caeVencimiento()).isNull();
        assertThat(d.qr()).isNull();
        assertThat(d.qrModulos()).isNull();
        // Lo de la rama 3 queda igual (con el CAE simulado si lo hay); la hoja no lo usa.
        assertThat(d.estadoFiscal().estado()).isEqualTo(estado);
        assertThat(d.estadoFiscal().letra()).isEqualTo("B");
        assertThat(d.estadoFiscal().ambiente()).isEqualTo(ambiente);
        assertThat(d.estadoFiscal().cae()).isEqualTo(estado.equals("AUTORIZADO") ? CAE : null);
        // El resto de la hoja sale igual de la copia.
        assertThat(d.emisor()).isEqualTo(EMISOR_RI);
        assertImportes(d, "1210.00", "1000.00", "210.00");
        assertThat(facturacion.resumenes(List.of(venta)).get(venta))
                .satisfies(r -> assertThat(r.letra()).isEqualTo("X"))
                .satisfies(r -> assertThat(r.validoComoFactura()).isFalse());
    }

    @Test
    @DisplayName("lo que autoriza el simulador sale X «SIMULADO», con el mismo estado que devolvió autorizar")
    void loQueAutorizaElSimuladorSaleX() {
        long venta = venta(ferreteriaId);
        enLaVenta(() -> {
            assertThat(facturacion.decidir(ReceptorVenta.sinCliente()).factura()).isTrue();
            return facturacion.registrar(venta, ReceptorVenta.sinCliente(),
                    List.of(new RenglonParaFacturar(new BigDecimal("21.00"), new BigDecimal("121.00"))), AHORA);
        });
        EstadoFiscalVenta autorizado = facturacion.autorizar(venta).orElseThrow();

        DocumentoFiscal d = facturacion.documento(venta).orElseThrow();

        assertThat(autorizado.estado()).isEqualTo("AUTORIZADO");
        assertThat(d.estadoFiscal()).isEqualTo(autorizado);
        assertThat(d.validoComoFactura()).isFalse();
        assertThat(d.letra()).isEqualTo("X");
        assertThat(d.marca()).isEqualTo("SIMULADO — sin validez fiscal");
        assertThat(d.qr()).isNull();
        assertThat(d.fecha()).isEqualTo(HOY);
        assertThat(d.emisor().cuit()).isEqualTo(String.valueOf(cuit));
        assertThat(d.emisor().ingresosBrutos()).isEqualTo("901-123456-7");
    }

    @Test
    @DisplayName("la fecha es la CbteFch; sin CbteFch, el día de la venta en Buenos Aires (no el de UTC)")
    void laFechaEsLaCbteFchOElDiaDeLaVenta() {
        long conNumero = venta(ferreteriaId);
        long pendiente = venta(ferreteriaId);
        insertar(conNumero, "PRODUCCION", "EN_CURSO", "B", 1, 12L, Receptor.SIN_IDENTIFICAR, "RESPONSABLE_INSCRIPTO",
                new Alic(5, "1000.00", "210.00"));
        insertar(pendiente, "PRODUCCION", "PENDIENTE", "B", 1, null, Receptor.SIN_IDENTIFICAR, "RESPONSABLE_INSCRIPTO",
                new Alic(5, "1000.00", "210.00"));
        // 22:30 del 28 en Buenos Aires: 01:30 del 29 en UTC.
        jdbc.update("update comprobante_fiscal set registrado_en = ? where comprobante_id = ?",
                LocalDateTime.ofInstant(Instant.parse("2026-10-29T01:30:00Z"), ZoneOffset.UTC),
                pendiente);

        assertThat(facturacion.documento(conNumero).orElseThrow().fecha()).isEqualTo(CBTE_FCH);
        assertThat(facturacion.documento(pendiente).orElseThrow().fecha()).isEqualTo(LocalDate.of(2026, 10, 28));
    }

    // ---- C2 y S15: el listado y la búsqueda por número fiscal ----

    @Test
    @DisplayName("resumenes: la letra de la hoja, el punto de venta, el número, el estado y el ambiente de cada venta con registro")
    void resumenes() {
        long valida = venta(ferreteriaId);
        long simulada = venta(ferreteriaId);
        long pendiente = venta(ferreteriaId);
        long sinRegistro = venta(ferreteriaId);
        insertar(valida, "PRODUCCION", "AUTORIZADO", "B", 3, 12L, Receptor.SIN_IDENTIFICAR, "RESPONSABLE_INSCRIPTO",
                new Alic(5, "1000.00", "210.00"));
        insertar(simulada, "SIMULADOR", "AUTORIZADO", "A", 1, 12L, Receptor.SIN_IDENTIFICAR, "RESPONSABLE_INSCRIPTO",
                new Alic(5, "1000.00", "210.00"));
        insertar(pendiente, "PRODUCCION", "PENDIENTE", "B", 3, null, Receptor.SIN_IDENTIFICAR, "RESPONSABLE_INSCRIPTO",
                new Alic(5, "1000.00", "210.00"));

        Map<Long, ResumenFiscal> resumenes = facturacion.resumenes(List.of(valida, simulada, pendiente, sinRegistro));

        assertThat(resumenes).containsOnlyKeys(valida, simulada, pendiente);
        assertThat(resumenes.get(valida)).isEqualTo(new ResumenFiscal("B", 3, 12L, "AUTORIZADO", "PRODUCCION", true));
        assertThat(resumenes.get(simulada)).isEqualTo(new ResumenFiscal("X", 1, 12L, "AUTORIZADO", "SIMULADOR", false));
        assertThat(resumenes.get(pendiente)).isEqualTo(new ResumenFiscal("X", 3, null, "PENDIENTE", "PRODUCCION", false));
        assertThat(facturacion.resumenes(List.of())).isEmpty();
    }

    @Test
    @DisplayName("ventasConNumeroFiscal: ese número en cualquier letra y ambiente; con punto de venta, solo en ese")
    void ventasConNumeroFiscal() {
        long produccionA = venta(ferreteriaId);
        long simuladorB = venta(ferreteriaId);
        long otroNumero = venta(ferreteriaId);
        long pendiente = venta(ferreteriaId);
        insertar(produccionA, "PRODUCCION", "AUTORIZADO", "A", 3, 12L, Receptor.SIN_IDENTIFICAR, "RESPONSABLE_INSCRIPTO",
                new Alic(5, "1000.00", "210.00"));
        insertar(simuladorB, "SIMULADOR", "AUTORIZADO", "B", 1, 12L, Receptor.SIN_IDENTIFICAR, "RESPONSABLE_INSCRIPTO",
                new Alic(5, "1000.00", "210.00"));
        insertar(otroNumero, "PRODUCCION", "AUTORIZADO", "B", 3, 13L, Receptor.SIN_IDENTIFICAR, "RESPONSABLE_INSCRIPTO",
                new Alic(5, "1000.00", "210.00"));
        insertar(pendiente, "PRODUCCION", "PENDIENTE", "B", 3, null, Receptor.SIN_IDENTIFICAR, "RESPONSABLE_INSCRIPTO",
                new Alic(5, "1000.00", "210.00"));

        assertThat(facturacion.ventasConNumeroFiscal(null, 12)).containsExactlyInAnyOrder(produccionA, simuladorB);
        assertThat(facturacion.ventasConNumeroFiscal(3, 12)).containsExactly(produccionA);
        assertThat(facturacion.ventasConNumeroFiscal(1, 12)).containsExactly(simuladorB);
        assertThat(facturacion.ventasConNumeroFiscal(null, 13)).containsExactly(otroNumero);
        assertThat(facturacion.ventasConNumeroFiscal(3, 99)).isEmpty();
    }

    @Test
    @DisplayName("lo de otra ferretería no se ve: ni su documento, ni su resumen, ni se encuentra por su número fiscal")
    void loDeOtraFerreteriaNoSeVe() {
        long ajena = venta(ferreteriaId);
        insertar(ajena, "PRODUCCION", "AUTORIZADO", "B", 3, 12L, Receptor.SIN_IDENTIFICAR, "RESPONSABLE_INSCRIPTO",
                new Alic(5, "1000.00", "210.00"));
        assertThat(facturacion.documento(ajena)).isPresent();

        TenantContext.setCurrentFerreteriaId(ferreteriaConFacturacion(cuitNuevo()));

        assertThat(facturacion.documento(ajena)).isEmpty();
        assertThat(facturacion.resumenes(List.of(ajena))).isEmpty();
        assertThat(facturacion.ventasConNumeroFiscal(null, 12)).isEmpty();
        assertThat(facturacion.ventasConNumeroFiscal(3, 12)).isEmpty();
    }

    @Test
    @DisplayName("una venta sin registro fiscal no tiene documento")
    void sinRegistroNoHayDocumento() {
        assertThat(facturacion.documento(venta(ferreteriaId))).isEmpty();
    }

    // ---- S3 y C3: lo que anticipa el cobro ----

    @Test
    @DisplayName("paraElMostrador: con la facturación habilitada, el ambiente y la letra de cada condición del cliente (la función del alta)")
    void paraElMostradorConFacturacion() {
        Optional<FacturacionDelMostrador> inscripto = facturacion.paraElMostrador();
        jdbc.update("update ferreteria set condicion_iva = 'MONOTRIBUTISTA' where id = ?", ferreteriaId);
        Optional<FacturacionDelMostrador> monotributista = facturacion.paraElMostrador();

        assertThat(inscripto).contains(new FacturacionDelMostrador("SIMULADOR", Map.of(
                CondicionIva.CONSUMIDOR_FINAL, "B", CondicionIva.RESPONSABLE_INSCRIPTO, "A",
                CondicionIva.MONOTRIBUTISTA, "A", CondicionIva.EXENTO, "B")));
        assertThat(monotributista).contains(new FacturacionDelMostrador("SIMULADOR", Map.of(
                CondicionIva.CONSUMIDOR_FINAL, "C", CondicionIva.RESPONSABLE_INSCRIPTO, "C",
                CondicionIva.MONOTRIBUTISTA, "C", CondicionIva.EXENTO, "C")));
    }

    static Stream<Arguments> sinFacturacion() {
        return Stream.of(
                Arguments.of("sin fila de config_arca", "delete from config_arca where ferreteria_id = ?"),
                Arguments.of("con la facturación apagada",
                        "update config_arca set facturacion_habilitada = false, habilitada_en = null where ferreteria_id = ?"),
                Arguments.of("sin Ingresos Brutos (CU-01 incompleto)",
                        "update ferreteria set ingresos_brutos = null where id = ?"),
                Arguments.of("sin domicilio comercial (CU-01 incompleto)",
                        "update ferreteria set domicilio_comercial = null where id = ?"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sinFacturacion")
    @DisplayName("paraElMostrador: vacío cuando la venta saldría sin factura, con la misma regla que decidir")
    void paraElMostradorSinFacturacion(String caso, String sql) {
        jdbc.update(sql, ferreteriaId);

        assertThat(facturacion.paraElMostrador()).isEmpty();
        assertThat(enLaVenta(() -> facturacion.decidir(ReceptorVenta.sinCliente()).factura())).isFalse();
    }

    @Test
    @DisplayName("paraElMostrador no toma lock: el panel puede cambiar la configuración mientras tanto")
    void paraElMostradorNoTomaLock() {
        String paraCambiarla = "select id from config_arca where ferreteria_id = ? for update nowait";

        enLaVenta(() -> {
            assertThat(facturacion.paraElMostrador()).isPresent();
            assertThat(enOtroHilo(() -> jdbc.queryForList(paraCambiarla, Long.class, ferreteriaId))).hasSize(1);
            return null;
        });
        // Contraprueba: decidir, en cambio, sí lo toma.
        enLaVenta(() -> {
            facturacion.decidir(ReceptorVenta.sinCliente());
            assertThatThrownBy(() -> enOtroHilo(() -> jdbc.queryForList(paraCambiarla, Long.class, ferreteriaId)))
                    .rootCause().hasMessageContaining("NOWAIT");
            return null;
        });
    }

    // ---- Ayudas ----

    /** El receptor como lo guarda el registro: DocTipo, DocNro, CondicionIVAReceptorId, nombre y domicilio. */
    record Receptor(int docTipo, long docNro, int condicionIvaId, String nombre, String domicilio) {
        static final Receptor SIN_IDENTIFICAR = new Receptor(99, 0, 5, null, null);
    }

    /** Una fila del desglose: el Id de la alícuota, la base y el IVA. */
    record Alic(int ivaId, String base, String importe) {}

    /**
     * Un registro fiscal de la venta, por SQL y respetando los CHECK de V32: el número y la CbteFch solo en los estados que
     * los tienen, y el CAE (con su vencimiento y el resultado A) solo en AUTORIZADO. Sin alícuotas es una C: neto = total.
     * El total es la suma de bases e IVA (en la C, $ 1.210).
     */
    private void insertar(long venta, String ambiente, String estado, String letra, int puntoVenta, Long numero,
            Receptor receptor, String condicionEmisor, Alic... alicuotas) {
        boolean conNumero = List.of("EN_CURSO", "EN_VERIFICACION", "AUTORIZADO", "CAE_NO_COINCIDE", "SERIE_DESINCRONIZADA")
                .contains(estado);
        boolean autorizado = estado.equals("AUTORIZADO");
        String resultado = autorizado ? "A" : estado.equals("RECHAZADO") ? "R" : null;
        int cbteTipo = switch (letra) {
            case "A" -> 1;
            case "B" -> 6;
            default -> 11;
        };
        BigDecimal neto = Stream.of(alicuotas).map(a -> new BigDecimal(a.base())).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal iva = Stream.of(alicuotas).map(a -> new BigDecimal(a.importe())).reduce(BigDecimal.ZERO,
                BigDecimal::add);
        if (alicuotas.length == 0) {
            neto = new BigDecimal("1210.00");
        }
        BigDecimal total = neto.add(iva);
        jdbc.update("insert into comprobante_fiscal (ferreteria_id, comprobante_id, ambiente, clase, letra, cbte_tipo, "
                + "punto_venta, numero, cbte_fch, estado, cae, cae_vencimiento, resultado, receptor_doc_tipo, "
                + "receptor_doc_nro, receptor_condicion_iva_id, receptor_nombre, receptor_domicilio, emisor_cuit, "
                + "emisor_razon_social, emisor_condicion_iva, emisor_domicilio_comercial, emisor_ingresos_brutos, "
                + "emisor_inicio_actividades, imp_total, imp_neto, imp_iva, imp_tot_conc, imp_op_ex, registrado_en) "
                + "values (?, ?, ?, 'FACTURA', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '30-71234567-8', 'El Tornillo SRL', "
                + "?, 'Av. Luro 1234, Mar del Plata', '901-765432-1', '2015-03-01', ?, ?, ?, 0, 0, ?)",
                ferreteriaId, venta, ambiente, letra, cbteTipo, puntoVenta, conNumero ? numero : null,
                conNumero ? CBTE_FCH : null, estado, autorizado ? CAE : null, autorizado ? VENCIMIENTO : null, resultado,
                receptor.docTipo(), receptor.docNro(), receptor.condicionIvaId(), receptor.nombre(), receptor.domicilio(),
                condicionEmisor, total, neto, iva,
                LocalDateTime.ofInstant(AHORA, ZoneOffset.UTC));
        long registro = jdbc.queryForObject("select id from comprobante_fiscal where comprobante_id = ?", Long.class, venta);
        for (Alic a : alicuotas) {
            jdbc.update("insert into comprobante_fiscal_alicuota (ferreteria_id, comprobante_fiscal_id, iva_id, base_imp, "
                    + "importe) values (?, ?, ?, ?, ?)", ferreteriaId, registro, a.ivaId(), new BigDecimal(a.base()),
                    new BigDecimal(a.importe()));
        }
    }

    private static void assertImportes(DocumentoFiscal d, String total, String neto, String iva) {
        assertThat(d.total().toPlainString()).isEqualTo(total);
        assertThat(d.neto().toPlainString()).isEqualTo(neto);
        assertThat(d.iva().toPlainString()).isEqualTo(iva);
        assertThat(d.noGravado().toPlainString()).isEqualTo("0.00");
        assertThat(d.exento().toPlainString()).isEqualTo("0.00");
    }

    private <T> T enLaVenta(Supplier<T> trabajo) {
        return new TransactionTemplate(transacciones).execute(s -> trabajo.get());
    }

    /** En otro hilo, con su propia conexión (fuera de la transacción del test). */
    private static <T> T enOtroHilo(Supplier<T> trabajo) {
        return CompletableFuture.supplyAsync(trabajo).join();
    }
}
