package com.ferreteria.facturacion.arca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ferreteria.facturacion.credencial.CredencialArcaService;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.motor.PasosFiscales;
import com.ferreteria.facturacion.wsfe.PedidoNoLlegoException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * C4 y S3, el cortacircuito por ferretería: cada fault de WSAA de la especificación técnica 1.2.2 (y uno desconocido) deja el
 * pedido sin enviar y su efecto. Con el {@code RelojControlable}, cuenta los {@code loginCms} siguientes antes y después de la
 * espera:
 * <ul>
 *   <li>CREDENCIAL: el bloqueo en {@code config_arca} (código, texto y hora), la venta sale sin factura
 *       ({@code puedeFacturar} da false) y no se llama a WSAA mientras dure, aunque pase un día; se levanta con
 *       {@code levantarBloqueo} (o activando otro certificado, en {@code BloqueoDeArcaIntegrationTest});</li>
 *   <li>SISTEMA: un log {@code ERROR} {@code ALERTA ARCA} con el código, la ferretería y el ambiente (sin el CMS), y sin
 *       llamar a WSAA durante {@code espera-error-sistema}; la venta sigue facturando;</li>
 *   <li>TRANSITORIO y TA_VIGENTE ({@code coe.alreadyAuthenticated}): sin llamar a WSAA durante {@code espera-wsaa} o
 *       {@code espera-ta-vigente}.</li>
 * </ul>
 */
class CortacircuitoWsaaIntegrationTest extends TicketsDeAccesoTestBase {

    @Autowired
    PasosFiscales pasosFiscales;
    @Autowired
    CredencialArcaService credenciales;
    @Autowired
    PlatformTransactionManager transacciones;

    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void escuchar() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(TicketsDeAcceso.class)).addAppender(logs);
    }

    @AfterEach
    void dejarDeEscuchar() {
        ((Logger) LoggerFactory.getLogger(TicketsDeAcceso.class)).detachAppender(logs);
    }

    static Stream<Arguments> faults() {
        return Stream.of(
                Arguments.of("cms.cert.expired", FallaWsaa.CREDENCIAL),
                Arguments.of("cms.cert.untrusted", FallaWsaa.CREDENCIAL),
                Arguments.of("cms.cert.invalid", FallaWsaa.CREDENCIAL),
                Arguments.of("cms.cert.notFound", FallaWsaa.CREDENCIAL),
                Arguments.of("coe.notAuthorized", FallaWsaa.CREDENCIAL),
                Arguments.of("xml.bad", FallaWsaa.SISTEMA),
                Arguments.of("xml.generationTime.invalid", FallaWsaa.SISTEMA),
                Arguments.of("xml.expirationTime.expired", FallaWsaa.SISTEMA),
                Arguments.of("wsn.notFound", FallaWsaa.SISTEMA),
                Arguments.of("cms.bad", FallaWsaa.SISTEMA),
                Arguments.of("cms.bad.base64", FallaWsaa.SISTEMA),
                Arguments.of("cms.sign.invalid", FallaWsaa.SISTEMA),
                Arguments.of("un.codigo.desconocido", FallaWsaa.SISTEMA),
                Arguments.of("wsaa.unavailable", FallaWsaa.TRANSITORIO),
                Arguments.of("wsaa.internalError", FallaWsaa.TRANSITORIO),
                Arguments.of("wsn.unavailable", FallaWsaa.TRANSITORIO),
                Arguments.of("coe.alreadyAuthenticated", FallaWsaa.TA_VIGENTE));
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("faults")
    void cadaFaultConSuEfectoYLasLlamadasSiguientes(String codigo, FallaWsaa clase) {
        assertThat(FallaWsaa.de(codigo)).isEqualTo(clase);
        TicketsDeAcceso tickets = instancia();
        wsaa.luegoFault(codigo, "Texto de ARCA para " + codigo);

        assertThatThrownBy(() -> tickets.ticket(Ambiente.HOMOLOGACION, WSFE)).isInstanceOf(PedidoNoLlegoException.class);
        assertThat(wsaa.llamadas).hasValue(1);
        assertThat(fila(ferreteriaId)).containsEntry("ultima_falla", codigo).containsEntry("lease_dueno", null)
                .containsEntry("huella_credencial", null);
        assertThat(instante(fila(ferreteriaId).get("ultima_falla_en"))).isEqualTo(AHORA);

        if (clase == FallaWsaa.CREDENCIAL) {
            assertThat(bloqueo()).containsEntry("bloqueo_codigo", codigo)
                    .containsEntry("bloqueo_mensaje", "Texto de ARCA para " + codigo);
            assertThat(instante(bloqueo().get("bloqueado_en"))).isEqualTo(AHORA);
            assertThat(fila(ferreteriaId)).containsEntry("espera_hasta", null);
            assertThat(laVentaFactura()).as("la venta sale sin factura (D5)").isFalse();
            reloj.avanzar(Duration.ofDays(1));
            assertThatThrownBy(() -> tickets.ticket(Ambiente.HOMOLOGACION, WSFE))
                    .isInstanceOf(PedidoNoLlegoException.class)
                    .hasMessage("La facturación con ARCA está frenada: ARCA rechazó el certificado (" + codigo + ").");
            assertThat(wsaa.llamadas).as("con el bloqueo no se llama a WSAA").hasValue(1);

            credenciales.levantarBloqueo();

            assertThat(laVentaFactura()).isTrue();
        } else {
            Duration espera = switch (clase) {
                case SISTEMA -> propiedades.esperaErrorSistema();
                case TRANSITORIO -> propiedades.esperaWsaa();
                case TA_VIGENTE -> propiedades.esperaTaVigente();
                case CREDENCIAL -> throw new AssertionError();
            };
            assertThat(instante(fila(ferreteriaId).get("espera_hasta"))).isEqualTo(AHORA.plus(espera));
            assertThat(bloqueo()).containsEntry("bloqueo_codigo", null);
            assertThat(laVentaFactura()).as("no frena la facturación").isTrue();
            assertThat(alertas()).as("ALERTA ARCA solo para los errores del sistema")
                    .hasSize(clase == FallaWsaa.SISTEMA ? 1 : 0);
            if (clase == FallaWsaa.SISTEMA) {
                assertThat(alertas()).singleElement().satisfies(e -> {
                    assertThat(e.getMarkerList()).extracting(org.slf4j.Marker::getName).containsExactly("ALERTA ARCA");
                    assertThat(e.getFormattedMessage()).startsWith("ALERTA ARCA").contains(codigo)
                            .contains(String.valueOf(ferreteriaId)).contains("HOMOLOGACION")
                            .doesNotContain(wsaa.cms.get(0));
                });
            }
            reloj.fijar(AHORA.plus(espera).minusSeconds(1));
            assertThatThrownBy(() -> tickets.ticket(Ambiente.HOMOLOGACION, WSFE))
                    .isInstanceOf(PedidoNoLlegoException.class).hasMessageContaining(codigo);
            assertThat(wsaa.llamadas).as("durante la espera no se llama a WSAA").hasValue(1);
            reloj.fijar(AHORA.plus(espera));
        }

        assertThat(tickets.ticket(Ambiente.HOMOLOGACION, WSFE).token()).isEqualTo(wsaa.tokens.get(0));
        assertThat(wsaa.llamadas).as("después, sí").hasValue(2);
    }

    /** Si la venta de ahora saldría con factura: la regla de la venta ({@code PasosFiscales.puedeFacturar}), en su transacción. */
    private boolean laVentaFactura() {
        Instant ahora = Instant.now(reloj);
        return Boolean.TRUE.equals(new TransactionTemplate(transacciones).execute(t -> pasosFiscales.puedeFacturar(ahora)));
    }

    private Map<String, Object> bloqueo() {
        return jdbc.queryForMap("select bloqueo_codigo, bloqueo_mensaje, bloqueado_en from config_arca where ferreteria_id = ?",
                ferreteriaId);
    }

    private java.util.List<ILoggingEvent> alertas() {
        return logs.list.stream().filter(e -> e.getLevel() == Level.ERROR).toList();
    }
}
