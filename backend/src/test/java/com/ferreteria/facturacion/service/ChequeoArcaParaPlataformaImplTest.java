package com.ferreteria.facturacion.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.facturacion.api.ChequeoArcaParaPlataforma.ChequeoFeDummy;
import com.ferreteria.facturacion.api.ChequeoArcaParaPlataforma.ResultadoFeDummy;
import com.ferreteria.facturacion.arca.ClienteWsfe;
import com.ferreteria.facturacion.arca.ClientesWsfe;
import com.ferreteria.facturacion.arca.ProveedorDeTicket;
import com.ferreteria.facturacion.arca.TicketDeAcceso;
import com.ferreteria.facturacion.arca.Transporte;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.wsfe.PedidoNoLlegoException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El chequeo FEDummy del super admin (rama 6, C2): homologación y producción con el {@link ClienteWsfe} real sobre un
 * transporte de prueba. Cada ambiente da su resultado (una falla no corta el otro), con el motivo legible del transporte, y
 * nunca pide un ticket.
 */
class ChequeoArcaParaPlataformaImplTest {

    private static final URI HOMO = URI.create("https://wswhomo.afip.gov.ar/wsfev1/service.asmx");
    private static final URI PROD = URI.create("https://servicios1.afip.gov.ar/wsfev1/service.asmx");

    /** Un proveedor que falla si se le pide un ticket: FEDummy no lleva {@code Auth}. */
    private static final ProveedorDeTicket SIN_TICKETS = new ProveedorDeTicket() {
        @Override
        public TicketDeAcceso ticket(Ambiente ambiente, String servicio) {
            throw new AssertionError("FEDummy no pide ticket");
        }

        @Override
        public void descartar(Ambiente ambiente, String servicio, TicketDeAcceso rechazado) {
            throw new AssertionError("FEDummy no descarta tickets");
        }
    };

    private static String fixture(String nombre) {
        try (InputStream entrada = ChequeoArcaParaPlataformaImplTest.class.getClassLoader()
                .getResourceAsStream("facturacion/arca/" + nombre + ".xml")) {
            return new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ChequeoFeDummy chequear(Transporte homologacion, Transporte produccion) {
        AtomicLong nanos = new AtomicLong();
        ClientesWsfe clientes = new ClientesWsfe(List.of(
                new ClienteWsfe(Ambiente.HOMOLOGACION, homologacion, HOMO, SIN_TICKETS),
                new ClienteWsfe(Ambiente.PRODUCCION, produccion, PROD, SIN_TICKETS)));
        // cada lectura del contador avanza 312 ms: la llamada «tarda» eso
        return new ChequeoArcaParaPlataformaImpl(clientes, () -> nanos.getAndAdd(312_000_000L)).feDummy();
    }

    @Test
    @DisplayName("los dos ambientes OK: los tres servidores, los milisegundos y sin error; FEDummy va a la URL de cada uno")
    void losDosOk() {
        List<URI> destinos = new java.util.ArrayList<>();
        Transporte homo = pedido -> {
            destinos.add(pedido.destino());
            return new Transporte.Respuesta(200, fixture("fedummy-homologacion"));
        };
        Transporte prod = pedido -> {
            destinos.add(pedido.destino());
            return new Transporte.Respuesta(200, fixture("fedummy-produccion"));
        };

        ChequeoFeDummy chequeo = chequear(homo, prod);

        assertThat(chequeo.homologacion()).isEqualTo(new ResultadoFeDummy(true, "OK", "OK", "OK", 312L, null));
        assertThat(chequeo.produccion()).isEqualTo(new ResultadoFeDummy(true, "OK", "OK", "OK", 312L, null));
        assertThat(destinos).containsExactly(HOMO, PROD);
    }

    @Test
    @DisplayName("una falla del transporte da el motivo legible, sin servidores ni milisegundos, y no corta el otro ambiente")
    void unaFallaNoCortaElOtro() {
        Transporte handshake = pedido -> {
            throw new PedidoNoLlegoException("Falló el handshake TLS.");
        };
        Transporte homo = pedido -> new Transporte.Respuesta(200, fixture("fedummy-homologacion"));

        ChequeoFeDummy chequeo = chequear(homo, handshake);

        assertThat(chequeo.homologacion().ok()).isTrue();
        assertThat(chequeo.produccion()).isEqualTo(new ResultadoFeDummy(false, null, null, null, null,
                "Falló el handshake TLS."));
    }

    @Test
    @DisplayName("un servidor que no dice OK: ok false, lo que dijo cada uno y el motivo")
    void servidorCaido() {
        Transporte homo = pedido -> new Transporte.Respuesta(200,
                fixture("fedummy-homologacion").replace("<AuthServer>OK</AuthServer>", "<AuthServer>ERROR</AuthServer>"));
        Transporte incierto = pedido -> new Transporte.Respuesta(503, "Service Unavailable");

        ChequeoFeDummy chequeo = chequear(homo, incierto);

        assertThat(chequeo.homologacion()).isEqualTo(new ResultadoFeDummy(false, "OK", "OK", "ERROR", 312L,
                "ARCA respondió, pero no todos sus servidores están OK."));
        assertThat(chequeo.produccion().ok()).isFalse();
        assertThat(chequeo.produccion().error()).contains("503");
    }
}
