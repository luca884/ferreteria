package com.ferreteria.facturacion.arca;

import static com.ferreteria.facturacion.arca.FixturesArca.sobre;
import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.facturacion.arca.ClienteWsfeTest.TicketsDePrueba;
import com.ferreteria.facturacion.arca.ClienteWsfeTest.TransporteGrabador;
import com.ferreteria.facturacion.arca.PruebaDeConexion.PasoPrueba;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.model.CondicionIvaEmisor;
import com.ferreteria.facturacion.wsfe.PedidoNoLlegoException;
import com.ferreteria.facturacion.wsfe.ResultadoInciertoException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Los cuatro pasos de «Probar conexión» (rama 6, C1) con un {@link ClienteWsfe} real sobre un transporte que contesta los
 * fixtures de WSFEv1: cada paso, su detalle, y que el primero que falla corta los siguientes (no figuran y no se llama a
 * ARCA).
 */
class PruebaDeConexionTest {

    private static final URI URL = URI.create("https://wswhomo.afip.gov.ar/wsfev1/service.asmx");
    private static final long CUIT = 30999999995L;
    /** 2026-10-29 21:40 UTC: las 18:40 de Argentina. */
    private static final Instant VENCE = Instant.parse("2026-10-29T21:40:00Z");

    private final AtomicInteger autenticaciones = new AtomicInteger();

    private TicketsDeAcceso.Probado probado() {
        autenticaciones.incrementAndGet();
        return new TicketsDeAcceso.Probado(new TicketDeAcceso("TOKEN", "SIGN", CUIT), VENCE);
    }

    private PruebaConexion correr(TransporteGrabador transporte, PruebaDeConexion.Autenticador autenticador, int punto,
            CondicionIvaEmisor condicion) {
        ClienteWsfe cliente = new ClienteWsfe(Ambiente.HOMOLOGACION, transporte, URL, tickets());
        return new PruebaDeConexion(cliente, autenticador)
                .correr(new PruebaDeConexion.Datos(Ambiente.HOMOLOGACION, punto, CUIT, condicion));
    }

    private static TicketsDePrueba tickets() {
        TicketDeAcceso ticket = new TicketDeAcceso("TOKEN", "SIGN", CUIT);
        return new TicketsDePrueba(ticket, ticket, ticket, ticket);
    }

    private static Transporte.Respuesta ok(String fixture) {
        return new Transporte.Respuesta(200, sobre(fixture));
    }

    private static Transporte.Respuesta ultimo(int cbteTipo, long numero) {
        return new Transporte.Respuesta(200, sobre("fecompultimoautorizado")
                .replace("<PtoVta>1</PtoVta><CbteTipo>6</CbteTipo><CbteNro>41</CbteNro>",
                        "<PtoVta>3</PtoVta><CbteTipo>" + cbteTipo + "</CbteTipo><CbteNro>" + numero + "</CbteNro>"));
    }

    private static List<String> pasos(PruebaConexion prueba) {
        return prueba.pasos().stream().map(p -> p.paso() + (p.ok() ? " OK " : " FALLA ") + p.detalle()).toList();
    }

    @Test
    @DisplayName("RI en homologación: los cuatro pasos OK, con el último número de la Factura A y de la B, y sin aviso")
    void todoOkConResponsableInscripto() {
        TransporteGrabador transporte = new TransporteGrabador(ok("fedummy-homologacion"), ok("feparamgetptosventa"),
                ultimo(1, 12), ultimo(6, 345));

        PruebaConexion prueba = correr(transporte, a -> probado(), 3, CondicionIvaEmisor.RESPONSABLE_INSCRIPTO);

        assertThat(pasos(prueba)).containsExactly(
                "SERVIDORES OK AppServer OK, DbServer OK, AuthServer OK",
                "AUTENTICACION OK Ticket de acceso vigente hasta 29/10/2026 18:40",
                "PUNTO_DE_VENTA OK Punto de venta 3: CAE, habilitado",
                "ULTIMOS_NUMEROS OK Factura A y Factura B");
        assertThat(prueba.ok()).isTrue();
        assertThat(prueba.ambiente()).isEqualTo("HOMOLOGACION");
        assertThat(prueba.aviso()).isNull();
        assertThat(prueba.ultimos()).containsExactly(new PruebaConexion.Ultimo(1, "Factura A", 12),
                new PruebaConexion.Ultimo(6, "Factura B", 345));
        assertThat(transporte.pedidos).extracting(Transporte.Pedido::sobre)
                .anyMatch(s -> s.contains("PtoVta>3<") && s.contains("CbteTipo>1<"));
    }

    @Test
    @DisplayName("Monotributista y Exento piden solo la Factura C (la tabla de la rama 2)")
    void monotributistaYExentoPidenSoloLaC() {
        for (CondicionIvaEmisor condicion : List.of(CondicionIvaEmisor.MONOTRIBUTISTA, CondicionIvaEmisor.EXENTO)) {
            TransporteGrabador transporte = new TransporteGrabador(ok("fedummy-homologacion"), ok("feparamgetptosventa"),
                    ultimo(11, 7));

            PruebaConexion prueba = correr(transporte, a -> probado(), 3, condicion);

            assertThat(prueba.ok()).isTrue();
            assertThat(prueba.ultimos()).containsExactly(new PruebaConexion.Ultimo(11, "Factura C", 7));
            assertThat(prueba.pasos().get(3).detalle()).isEqualTo("Factura C");
        }
    }

    @Test
    @DisplayName("SERVIDORES con un servidor caído corta todo: dice cuál, y no se pide el ticket")
    void servidoresCaidosCortan() {
        TransporteGrabador transporte = new TransporteGrabador(new Transporte.Respuesta(200,
                sobre("fedummy-homologacion").replace("<DbServer>OK</DbServer>", "<DbServer>ERROR</DbServer>")));

        PruebaConexion prueba = correr(transporte, a -> probado(), 3, CondicionIvaEmisor.RESPONSABLE_INSCRIPTO);

        assertThat(pasos(prueba)).containsExactly("SERVIDORES FALLA DbServer ERROR");
        assertThat(prueba.ok()).isFalse();
        assertThat(prueba.ultimos()).isEmpty();
        assertThat(autenticaciones).hasValue(0);
    }

    @Test
    @DisplayName("SERVIDORES sin conexión: el motivo legible del transporte")
    void servidoresSinConexion() {
        Transporte caido = pedido -> {
            throw new PedidoNoLlegoException("No se pudo conectar con ARCA (timeout de conexión).");
        };
        ClienteWsfe cliente = new ClienteWsfe(Ambiente.HOMOLOGACION, caido, URL, tickets());

        PruebaConexion prueba = new PruebaDeConexion(cliente, a -> probado())
                .correr(new PruebaDeConexion.Datos(Ambiente.HOMOLOGACION, 3, CUIT, CondicionIvaEmisor.MONOTRIBUTISTA));

        assertThat(pasos(prueba)).containsExactly("SERVIDORES FALLA No se pudo conectar con ARCA (timeout de conexión).");
        assertThat(autenticaciones).hasValue(0);
    }

    @Test
    @DisplayName("AUTENTICACION con un rechazo del certificado: el fault de ARCA con su código, y corta")
    void autenticacionConFaultCorta() {
        TransporteGrabador transporte = new TransporteGrabador(ok("fedummy-homologacion"));
        PruebaDeConexion.Autenticador rechazado = a -> {
            throw new PedidoNoLlegoException("WSAA rechazó el pedido del ticket de acceso (cms.cert.expired).",
                    new FaultWsaaException("cms.cert.expired", "Certificado expirado"));
        };

        PruebaConexion prueba = correr(transporte, rechazado, 3, CondicionIvaEmisor.RESPONSABLE_INSCRIPTO);

        assertThat(pasos(prueba)).containsExactly(
                "SERVIDORES OK AppServer OK, DbServer OK, AuthServer OK",
                "AUTENTICACION FALLA ARCA rechazó el certificado: Certificado expirado (cms.cert.expired)");
        assertThat(prueba.ok()).isFalse();
        assertThat(transporte.pedidos).hasSize(1);
    }

    @Test
    @DisplayName("AUTENTICACION con otro fault (no del certificado) o sin fault: el código, o el motivo")
    void autenticacionConOtroFaultOSinFault() {
        PruebaDeConexion.Autenticador transitorio = a -> {
            throw new PedidoNoLlegoException("WSAA rechazó el pedido del ticket de acceso (wsaa.unavailable).",
                    new FaultWsaaException("wsaa.unavailable", ""));
        };
        PruebaDeConexion.Autenticador enEspera = a -> {
            throw new PedidoNoLlegoException("ARCA pidió esperar (wsaa.unavailable): no se pide otro ticket de acceso "
                    + "hasta 2026-10-28T13:16:30Z.");
        };

        assertThat(correr(new TransporteGrabador(ok("fedummy-homologacion")), transitorio, 3,
                CondicionIvaEmisor.MONOTRIBUTISTA).pasos().get(1).detalle())
                .isEqualTo("ARCA no dio el ticket de acceso (wsaa.unavailable)");
        assertThat(correr(new TransporteGrabador(ok("fedummy-homologacion")), enEspera, 3,
                CondicionIvaEmisor.MONOTRIBUTISTA).pasos().get(1).detalle())
                .startsWith("ARCA pidió esperar (wsaa.unavailable)");
    }

    @ParameterizedTest(name = "punto {0}: {1}")
    @CsvSource(delimiter = '|', value = {
        "7 | El punto de venta 7 no está dado de alta en ARCA.",
        "5 | El punto de venta 5 es de tipo CAEA: tiene que ser CAE.",
        "4 | El punto de venta 4 está bloqueado en ARCA."
    })
    @DisplayName("PUNTO_DE_VENTA que no sirve corta, con el motivo")
    void puntoDeVentaQueNoSirveCorta(int punto, String detalle) {
        TransporteGrabador transporte = new TransporteGrabador(ok("fedummy-homologacion"), ok("feparamgetptosventa"));

        PruebaConexion prueba = correr(transporte, a -> probado(), punto, CondicionIvaEmisor.RESPONSABLE_INSCRIPTO);

        assertThat(prueba.pasos()).hasSize(3);
        assertThat(prueba.pasos().get(2)).isEqualTo(new PruebaConexion.Paso(PasoPrueba.PUNTO_DE_VENTA, false, detalle));
        assertThat(prueba.ok()).isFalse();
        assertThat(transporte.pedidos).hasSize(2);
    }

    @Test
    @DisplayName("PUNTO_DE_VENTA dado de baja (CAE, sin bloqueo): desde cuándo")
    void puntoDeVentaDadoDeBaja() {
        TransporteGrabador transporte = new TransporteGrabador(ok("fedummy-homologacion"), new Transporte.Respuesta(200,
                sobre("feparamgetptosventa").replace("<Nro>3</Nro><EmisionTipo>CAE</EmisionTipo><Bloqueado>N</Bloqueado>"
                        + "<FchBaja>NULL</FchBaja>", "<Nro>3</Nro><EmisionTipo>CAE</EmisionTipo><Bloqueado>N</Bloqueado>"
                        + "<FchBaja>20260115</FchBaja>")));

        PruebaConexion prueba = correr(transporte, a -> probado(), 3, CondicionIvaEmisor.RESPONSABLE_INSCRIPTO);

        assertThat(prueba.pasos().get(2).detalle()).isEqualTo("El punto de venta 3 está dado de baja en ARCA desde 15/01/2026.");
    }

    @Test
    @DisplayName("PUNTO_DE_VENTA sin puntos (602): no está dado de alta")
    void sinPuntosDeVenta() {
        TransporteGrabador transporte = new TransporteGrabador(ok("fedummy-homologacion"),
                ok("feparamgetptosventa-sin-puntos"));

        PruebaConexion prueba = correr(transporte, a -> probado(), 3, CondicionIvaEmisor.RESPONSABLE_INSCRIPTO);

        assertThat(prueba.pasos().get(2).detalle()).isEqualTo("El punto de venta 3 no está dado de alta en ARCA.");
    }

    @Test
    @DisplayName("ULTIMOS_NUMEROS con un error de ARCA o una respuesta incierta falla, sin números")
    void ultimosNumerosConError() {
        TransporteGrabador conError = new TransporteGrabador(ok("fedummy-homologacion"), ok("feparamgetptosventa"),
                new Transporte.Respuesta(200, sobre("fecompultimoautorizado").replace(
                        "<PtoVta>1</PtoVta><CbteTipo>6</CbteTipo><CbteNro>41</CbteNro>", "<PtoVta>3</PtoVta><CbteTipo>1"
                                + "</CbteTipo><CbteNro>0</CbteNro><Errors><Err><Code>10015</Code><Msg>Error de prueba</Msg>"
                                + "</Err></Errors>")));

        PruebaConexion prueba = correr(conError, a -> probado(), 3, CondicionIvaEmisor.RESPONSABLE_INSCRIPTO);

        assertThat(prueba.pasos().get(3))
                .isEqualTo(new PruebaConexion.Paso(PasoPrueba.ULTIMOS_NUMEROS, false,
                        "Factura A: ARCA respondió 10015: Error de prueba"));
        assertThat(prueba.ok()).isFalse();
        assertThat(prueba.ultimos()).isEmpty();
        assertThat(conError.pedidos).hasSize(3);

        TransporteGrabador incierto = new TransporteGrabador(ok("fedummy-homologacion"), ok("feparamgetptosventa"),
                new Transporte.Respuesta(500, "<html/>"));
        PruebaConexion conIncierto = correr(incierto, a -> probado(), 3, CondicionIvaEmisor.MONOTRIBUTISTA);
        assertThat(conIncierto.pasos().get(3).ok()).isFalse();
        assertThat(conIncierto.pasos().get(3).detalle()).contains("500");
    }

    @Test
    @DisplayName("en SIMULADOR trae el aviso «Simulador: no se probó contra ARCA»")
    void avisoEnSimulador() {
        TransporteGrabador transporte = new TransporteGrabador(new Transporte.Respuesta(200,
                sobre("fedummy-homologacion").replace("<AppServer>OK</AppServer>", "<AppServer></AppServer>")));
        ClienteWsfe cliente = new ClienteWsfe(Ambiente.SIMULADOR, transporte, URL, tickets());

        PruebaConexion prueba = new PruebaDeConexion(cliente, a -> probado())
                .correr(new PruebaDeConexion.Datos(Ambiente.SIMULADOR, 1, CUIT, CondicionIvaEmisor.MONOTRIBUTISTA));

        assertThat(prueba.aviso()).isEqualTo("Simulador: no se probó contra ARCA");
        assertThat(prueba.ambiente()).isEqualTo("SIMULADOR");
        assertThat(pasos(prueba)).containsExactly("SERVIDORES FALLA AppServer sin respuesta");
    }

    @Test
    @DisplayName("ninguna falla de ARCA sale como excepción: un incierto en FEDummy es el paso fallido")
    void inciertoEnFeDummy() {
        Transporte incierto = pedido -> {
            throw new ResultadoInciertoException("ARCA no respondió a tiempo (timeout de lectura).");
        };
        ClienteWsfe cliente = new ClienteWsfe(Ambiente.HOMOLOGACION, incierto, URL, tickets());

        PruebaConexion prueba = new PruebaDeConexion(cliente, a -> probado())
                .correr(new PruebaDeConexion.Datos(Ambiente.HOMOLOGACION, 3, CUIT, CondicionIvaEmisor.MONOTRIBUTISTA));

        assertThat(pasos(prueba)).containsExactly("SERVIDORES FALLA ARCA no respondió a tiempo (timeout de lectura).");
    }
}
