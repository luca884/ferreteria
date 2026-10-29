package com.ferreteria.facturacion.arca;

import static com.ferreteria.facturacion.arca.FixturesArca.sobre;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.wsfe.Auth;
import com.ferreteria.facturacion.wsfe.Err;
import com.ferreteria.facturacion.wsfe.FeCaeReq;
import com.ferreteria.facturacion.wsfe.FeCaeResponse;
import com.ferreteria.facturacion.wsfe.PedidoDePrueba;
import com.ferreteria.facturacion.wsfe.PedidoNoLlegoException;
import com.ferreteria.facturacion.wsfe.Resultado;
import com.ferreteria.facturacion.wsfe.ResultadoInciertoException;
import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Element;

/**
 * S4, S17 y el 600 de C4: {@link ClienteWsfe} arma el pedido con el ticket del proveedor y la CUIT del registro, lo manda
 * por el transporte a la URL de su ambiente con la {@code SOAPAction} del método, y lee la respuesta. Lo que no es un 200
 * legible es incierto. Con transporte y proveedor falsos, que cuentan cada pedido.
 */
class ClienteWsfeTest {

    private static final URI URL = URI.create("https://wsfe.example/wsfev1/service.asmx");
    private static final long CUIT = 30999999995L;
    private static final long OTRA_CUIT = 20222222223L;
    private static final Auth HOMOLOGACION = new Auth(Ambiente.HOMOLOGACION, CUIT);
    private static final TicketDeAcceso PRIMERO = new TicketDeAcceso("token-uno", "sign-uno", CUIT);
    private static final TicketDeAcceso SEGUNDO = new TicketDeAcceso("token-dos", "sign-dos", CUIT);

    private static FeCaeReq facturaB() {
        return PedidoDePrueba.facturaB(LocalDate.of(2026, 10, 29)).numero(42).armar();
    }

    @Test
    @DisplayName("FEDummy va sin ticket, a la URL del ambiente, con su SOAPAction, y lee los servidores")
    void feDummySinTicket() {
        TransporteGrabador transporte = new TransporteGrabador(ok("fedummy-homologacion"));
        TicketsDePrueba tickets = new TicketsDePrueba(PRIMERO);

        FeDummyResponse dummy = new ClienteWsfe(Ambiente.HOMOLOGACION, transporte, URL, tickets).feDummy();

        assertThat(dummy.todoOk()).isTrue();
        assertThat(transporte.pedidos).containsExactly(
                new Transporte.Pedido(URL, "http://ar.gov.afip.dif.FEV1/FEDummy", PlantillasWsfe.feDummy()));
        assertThat(tickets.pedidos).isZero();
    }

    @Test
    @DisplayName("cada método va con su SOAPAction y el ticket del proveedor, con Auth.Cuit del registro, y lee la respuesta")
    void cadaMetodoConSuAccionYElTicket() {
        TransporteGrabador transporte = new TransporteGrabador(ok("feparamgetptosventa"), ok("fecompultimoautorizado"),
                ok("fecaesolicitar-aprobado"), ok("fecompconsultar"));
        TicketsDePrueba tickets = new TicketsDePrueba(PRIMERO, PRIMERO, PRIMERO, PRIMERO);
        ClienteWsfe cliente = new ClienteWsfe(Ambiente.HOMOLOGACION, transporte, URL, tickets);

        assertThat(cliente.feParamGetPtosVenta(HOMOLOGACION).resultGet()).hasSize(3);
        assertThat(cliente.feCompUltimoAutorizado(HOMOLOGACION, 1, 6).cbteNro()).isEqualTo(41);
        assertThat(cliente.feCaeSolicitar(HOMOLOGACION, facturaB()).feDetResp().cae()).isEqualTo("76543210987654");
        assertThat(cliente.feCompConsultar(HOMOLOGACION, 6, 1, 41).resultGet().codAutorizacion())
                .isEqualTo("76543210987650");

        assertThat(transporte.pedidos).extracting(Transporte.Pedido::soapAction).containsExactly(
                "http://ar.gov.afip.dif.FEV1/FEParamGetPtosVenta", "http://ar.gov.afip.dif.FEV1/FECompUltimoAutorizado",
                "http://ar.gov.afip.dif.FEV1/FECAESolicitar", "http://ar.gov.afip.dif.FEV1/FECompConsultar");
        assertThat(transporte.pedidos).extracting(Transporte.Pedido::destino).containsOnly(URL);
        assertThat(transporte.pedidos.get(2).sobre())
                .isEqualTo(PlantillasWsfe.feCaeSolicitar(PRIMERO, CUIT, facturaB()));
        assertThat(tickets.pedidosPor).containsOnly("HOMOLOGACION/wsfe");
    }

    @Test
    @DisplayName("un Auth de otro ambiente se rechaza antes de pedir el ticket o mandar nada")
    void rechazaOtroAmbiente() {
        TransporteGrabador transporte = new TransporteGrabador();
        TicketsDePrueba tickets = new TicketsDePrueba(PRIMERO);
        ClienteWsfe cliente = new ClienteWsfe(Ambiente.HOMOLOGACION, transporte, URL, tickets);
        Auth deProduccion = new Auth(Ambiente.PRODUCCION, CUIT);

        assertThatThrownBy(() -> cliente.feCaeSolicitar(deProduccion, facturaB()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("PRODUCCION");
        assertThatThrownBy(() -> cliente.feParamGetPtosVenta(new Auth(Ambiente.SIMULADOR, CUIT)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SIMULADOR");
        assertThat(tickets.pedidos).isZero();
        assertThat(transporte.pedidos).isEmpty();
    }

    @Test
    @DisplayName("un status distinto de 200 es incierto, aunque traiga un sobre legible")
    void statusDistintoDe200() {
        TransporteGrabador transporte = new TransporteGrabador(
                new Transporte.Respuesta(500, sobre("fecaesolicitar-aprobado")), new Transporte.Respuesta(503, ""));
        ClienteWsfe cliente = new ClienteWsfe(Ambiente.HOMOLOGACION, transporte, URL, new TicketsDePrueba(PRIMERO, PRIMERO));

        assertThatThrownBy(() -> cliente.feCaeSolicitar(HOMOLOGACION, facturaB()))
                .isInstanceOf(ResultadoInciertoException.class).hasMessageContaining("500");
        assertThatThrownBy(() -> cliente.feCompUltimoAutorizado(HOMOLOGACION, 1, 6))
                .isInstanceOf(ResultadoInciertoException.class).hasMessageContaining("503");
    }

    @Test
    @DisplayName("un 200 con un cuerpo ilegible (HTML de un proxy, otra respuesta) es incierto")
    void cuerpoIlegible() {
        TransporteGrabador transporte = new TransporteGrabador(
                new Transporte.Respuesta(200, "<html><body>Service Unavailable</body></html>"), ok("fedummy-homologacion"));
        ClienteWsfe cliente = new ClienteWsfe(Ambiente.HOMOLOGACION, transporte, URL, new TicketsDePrueba(PRIMERO, PRIMERO));

        assertThatThrownBy(() -> cliente.feCaeSolicitar(HOMOLOGACION, facturaB()))
                .isInstanceOf(ResultadoInciertoException.class).hasCauseInstanceOf(XmlIlegibleException.class);
        assertThatThrownBy(() -> cliente.feCompConsultar(HOMOLOGACION, 6, 1, 41))
                .isInstanceOf(ResultadoInciertoException.class).hasCauseInstanceOf(XmlIlegibleException.class);
    }

    @Test
    @DisplayName("600: descarta el TA, pide otro una sola vez y repite la llamada una vez, con el ticket nuevo")
    void el600RenuevaElTicketUnaVez() {
        TransporteGrabador transporte = new TransporteGrabador(ok("fecaesolicitar-600"), ok("fecaesolicitar-aprobado"));
        TicketsDePrueba tickets = new TicketsDePrueba(PRIMERO, SEGUNDO);

        FeCaeResponse respuesta = new ClienteWsfe(Ambiente.HOMOLOGACION, transporte, URL, tickets)
                .feCaeSolicitar(HOMOLOGACION, facturaB());

        assertThat(respuesta.feCabResp().resultado()).isEqualTo(Resultado.A);
        assertThat(tickets.pedidos).isEqualTo(2);
        assertThat(tickets.descartados).containsExactly(PRIMERO);
        assertThat(transporte.pedidos).extracting(Transporte.Pedido::sobre).containsExactly(
                PlantillasWsfe.feCaeSolicitar(PRIMERO, CUIT, facturaB()),
                PlantillasWsfe.feCaeSolicitar(SEGUNDO, CUIT, facturaB()));
    }

    @Test
    @DisplayName("otro 600 después de renovar vuelve tal cual, como error de cabecera: sin un tercer intento")
    void otro600VuelveTalCual() {
        TransporteGrabador transporte = new TransporteGrabador(ok("fecaesolicitar-600"), ok("fecaesolicitar-600"));
        TicketsDePrueba tickets = new TicketsDePrueba(PRIMERO, SEGUNDO);

        FeCaeResponse respuesta = new ClienteWsfe(Ambiente.HOMOLOGACION, transporte, URL, tickets)
                .feCaeSolicitar(HOMOLOGACION, facturaB());

        assertThat(respuesta.errors()).containsExactly(new Err(600, "ValidacionDeToken: texto de prueba."));
        assertThat(respuesta.feCabResp().resultado()).isEqualTo(Resultado.R);
        assertThat(tickets.pedidos).isEqualTo(2);
        assertThat(tickets.descartados).containsExactly(PRIMERO);
        assertThat(transporte.pedidos).hasSize(2);
    }

    @Test
    @DisplayName("S17: en PRODUCCION, si la CUIT del registro no es la de la credencial, no manda nada, no llegó, y deja un "
            + "ERROR con el marcador ALERTA ARCA")
    void cuitDistintaEnProduccion() {
        TransporteGrabador transporte = new TransporteGrabador();
        TicketsDePrueba tickets = new TicketsDePrueba(new TicketDeAcceso("token-ajeno", "sign-ajeno", OTRA_CUIT));
        ClienteWsfe cliente = new ClienteWsfe(Ambiente.PRODUCCION, transporte, URL, tickets);
        ListAppender<ILoggingEvent> logs = escuchar();
        try {
            assertThatThrownBy(() -> cliente.feCaeSolicitar(new Auth(Ambiente.PRODUCCION, CUIT), facturaB()))
                    .isInstanceOf(PedidoNoLlegoException.class);

            assertThat(transporte.pedidos).isEmpty();
            assertThat(tickets.descartados).isEmpty();
            assertThat(logs.list).filteredOn(e -> e.getLevel() == Level.ERROR).singleElement().satisfies(e -> {
                assertThat(e.getMarkerList()).extracting(org.slf4j.Marker::getName).containsExactly("ALERTA ARCA");
                assertThat(e.getFormattedMessage()).startsWith("ALERTA ARCA").contains("30999999995")
                        .contains("20222222223").contains("FECAESolicitar").doesNotContain("token-ajeno")
                        .doesNotContain("sign-ajeno");
            });
        } finally {
            dejarDeEscuchar(logs);
        }
    }

    @Test
    @DisplayName("S17: en HOMOLOGACION, con un certificado de otra CUIT, manda Auth.Cuit con la del registro")
    void cuitDistintaEnHomologacion() {
        TransporteGrabador transporte = new TransporteGrabador(ok("fecompultimoautorizado"));
        TicketDeAcceso ajeno = new TicketDeAcceso("token-ajeno", "sign-ajeno", OTRA_CUIT);

        new ClienteWsfe(Ambiente.HOMOLOGACION, transporte, URL, new TicketsDePrueba(ajeno))
                .feCompUltimoAutorizado(HOMOLOGACION, 1, 6);

        Element auth = XmlSeguro.hijo(EsquemaWsfe.validarSobre(transporte.pedidos.get(0).sobre()), "Auth").orElseThrow();
        assertThat(XmlSeguro.texto(auth, "Cuit")).hasValue("30999999995");
        assertThat(XmlSeguro.texto(auth, "Token")).hasValue("token-ajeno");
    }

    private static Transporte.Respuesta ok(String fixture) {
        return new Transporte.Respuesta(200, sobre(fixture));
    }

    private static ListAppender<ILoggingEvent> escuchar() {
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(ClienteWsfe.class)).addAppender(logs);
        return logs;
    }

    private static void dejarDeEscuchar(ListAppender<ILoggingEvent> logs) {
        ((Logger) LoggerFactory.getLogger(ClienteWsfe.class)).detachAppender(logs);
    }

    /** Contesta en orden las respuestas que se le dieron, y guarda cada pedido. */
    static final class TransporteGrabador implements Transporte {

        final List<Pedido> pedidos = new ArrayList<>();
        private final Deque<Respuesta> respuestas;

        TransporteGrabador(Respuesta... respuestas) {
            this.respuestas = new ArrayDeque<>(Arrays.asList(respuestas));
        }

        @Override
        public Respuesta enviar(Pedido pedido) {
            pedidos.add(pedido);
            if (respuestas.isEmpty()) {
                throw new AssertionError("El cliente mandó un pedido de más: " + pedido);
            }
            return respuestas.removeFirst();
        }
    }

    /** Entrega en orden los tickets que se le dieron, y cuenta los pedidos y los descartes. */
    static final class TicketsDePrueba implements ProveedorDeTicket {

        int pedidos;
        final List<String> pedidosPor = new ArrayList<>();
        final List<TicketDeAcceso> descartados = new ArrayList<>();
        private final Deque<TicketDeAcceso> tickets;

        TicketsDePrueba(TicketDeAcceso... tickets) {
            this.tickets = new ArrayDeque<>(Arrays.asList(tickets));
        }

        @Override
        public TicketDeAcceso ticket(Ambiente ambiente, String servicio) {
            pedidos++;
            pedidosPor.add(ambiente + "/" + servicio);
            if (tickets.isEmpty()) {
                throw new AssertionError("El cliente pidió un ticket de más.");
            }
            return tickets.removeFirst();
        }

        @Override
        public void descartar(Ambiente ambiente, String servicio, TicketDeAcceso rechazado) {
            descartados.add(rechazado);
        }
    }
}
