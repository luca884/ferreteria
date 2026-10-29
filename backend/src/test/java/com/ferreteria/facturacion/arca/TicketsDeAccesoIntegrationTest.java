package com.ferreteria.facturacion.arca;

import static com.ferreteria.facturacion.arca.FixturesArca.sobre;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.facturacion.arca.ClienteWsfeTest.TransporteGrabador;
import com.ferreteria.facturacion.credencial.CasDeArcaDePrueba;
import com.ferreteria.facturacion.credencial.CredencialDePrueba;
import com.ferreteria.facturacion.cripto.ContextoDeCifrado;
import com.ferreteria.facturacion.cripto.DatoIlegibleException;
import com.ferreteria.facturacion.cripto.Proposito;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.motor.MotorFiscalTestBase;
import com.ferreteria.facturacion.wsfe.Auth;
import com.ferreteria.facturacion.wsfe.FeCaeResponse;
import com.ferreteria.facturacion.wsfe.PedidoDePrueba;
import com.ferreteria.facturacion.wsfe.PedidoNoLlegoException;
import com.ferreteria.facturacion.wsfe.Resultado;
import com.ferreteria.facturacion.wsfe.ResultadoInciertoException;
import com.ferreteria.tenant.TenantContext;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * S2: el ticket de acceso con la base real. El primer pedido saca el TA con un {@code loginCms} (el TRA firmado con la
 * credencial activa) y lo guarda cifrado; después se reusa, también desde una instancia nueva (un reinicio), salvo que sea de
 * otra credencial o que toque renovarlo (con margen y pasado el lapso). El 600 de WSFE lo descarta y saca uno solo más. Toda
 * falla sale como «no llegó». El TA de una ferretería no lo ve otra.
 */
class TicketsDeAccesoIntegrationTest extends TicketsDeAccesoTestBase {

    @Autowired
    PlatformTransactionManager transacciones;

    @Test
    @DisplayName("S2: el primer pedido hace un loginCms con el TRA firmado y guarda el TA cifrado con TICKET_DE_ACCESO y la "
            + "huella de la credencial en el AAD; el ticket lleva la CUIT de la credencial")
    void elPrimerPedidoSacaElTaYLoGuardaCifrado() {
        TicketDeAcceso ticket = instancia().ticket(Ambiente.HOMOLOGACION, WSFE);

        assertThat(wsaa.llamadas).hasValue(1);
        assertThat(ticket.token()).isEqualTo(wsaa.tokens.get(0));
        assertThat(ticket.cuit()).isEqualTo(cuit);
        Map<String, Object> fila = fila(ferreteriaId);
        assertThat(fila).containsEntry("huella_credencial", credencial.huella()).containsEntry("lease_dueno", null)
                .containsEntry("lease_vence", null).containsEntry("espera_hasta", null);
        assertThat(instante(fila.get("vence"))).isEqualTo(AHORA.plus(Duration.ofHours(12)));
        assertThat(instante(fila.get("generado"))).isEqualTo(AHORA);
        byte[] token = (byte[]) fila.get("token_cifrado");
        byte[] sign = (byte[]) fila.get("sign_cifrado");
        assertThat(new String(token, StandardCharsets.ISO_8859_1)).doesNotContain(ticket.token());
        assertThat(new String(sign, StandardCharsets.ISO_8859_1)).doesNotContain(ticket.sign());
        ContextoDeCifrado delTicket = new ContextoDeCifrado(Proposito.TICKET_DE_ACCESO, ferreteriaId, credencial.huella());
        assertThat(new String(cifrador.descifrar(token, delTicket), StandardCharsets.UTF_8)).isEqualTo(ticket.token());
        assertThat(new String(cifrador.descifrar(sign, delTicket), StandardCharsets.UTF_8)).isEqualTo(ticket.sign());
        assertThatThrownBy(() -> cifrador.descifrar(token,
                new ContextoDeCifrado(Proposito.CLAVE_DE_CREDENCIAL, ferreteriaId, credencial.huella())))
                .isInstanceOf(DatoIlegibleException.class);
        assertThatThrownBy(() -> cifrador.descifrar(token,
                new ContextoDeCifrado(Proposito.TICKET_DE_ACCESO, ferreteriaId, "AB".repeat(32))))
                .isInstanceOf(DatoIlegibleException.class);
    }

    @Test
    @DisplayName("S2: el TA vigente se reusa sin loginCms, en la misma instancia y en una nueva sobre la misma base "
            + "(reinicio)")
    void elTaVigenteSeReusaTambienDespuesDeUnReinicio() {
        TicketDeAcceso primero = instancia().ticket(Ambiente.HOMOLOGACION, WSFE);
        reloj.avanzar(Duration.ofHours(6));

        TicketsDeAcceso despuesDelReinicio = instancia();

        assertThat(despuesDelReinicio.ticket(Ambiente.HOMOLOGACION, WSFE)).isEqualTo(primero);
        assertThat(despuesDelReinicio.ticket(Ambiente.HOMOLOGACION, WSFE)).isEqualTo(primero);
        assertThat(wsaa.llamadas).hasValue(1);
    }

    @Test
    @DisplayName("S2: un TA de otra credencial (otra huella) no se usa: se saca otro, con la credencial nueva")
    void unTaDeOtraCredencialNoSeUsa() {
        TicketsDeAcceso tickets = instancia();
        TicketDeAcceso viejo = tickets.ticket(Ambiente.HOMOLOGACION, WSFE);
        CredencialDePrueba nueva = CredencialDePrueba.activa(jdbc, cifrador, ferreteriaId, Ambiente.HOMOLOGACION, cuit,
                AHORA);

        TicketDeAcceso nuevo = tickets.ticket(Ambiente.HOMOLOGACION, WSFE);

        assertThat(wsaa.llamadas).hasValue(2);
        assertThat(nuevo.token()).isNotEqualTo(viejo.token()).isEqualTo(wsaa.tokens.get(1));
        assertThat(fila(ferreteriaId)).containsEntry("huella_credencial", nueva.huella());
    }

    @Test
    @DisplayName("S2: un TA a menos del margen de vencer se renueva; si la renovación falla, sigue el viejo")
    void seRenuevaConMargenYSiFallaSigueElViejo() {
        TicketsDeAcceso tickets = instancia();
        TicketDeAcceso primero = tickets.ticket(Ambiente.HOMOLOGACION, WSFE);
        Instant vence = AHORA.plus(Duration.ofHours(12));

        reloj.fijar(vence.minus(Duration.ofMinutes(31)));
        assertThat(tickets.ticket(Ambiente.HOMOLOGACION, WSFE)).isEqualTo(primero);
        assertThat(wsaa.llamadas).hasValue(1);

        reloj.fijar(vence.minus(Duration.ofMinutes(29)));
        TicketDeAcceso renovado = tickets.ticket(Ambiente.HOMOLOGACION, WSFE);
        assertThat(wsaa.llamadas).hasValue(2);
        assertThat(renovado.token()).isNotEqualTo(primero.token()).isEqualTo(wsaa.tokens.get(1));

        reloj.fijar(Instant.now(reloj).plus(Duration.ofHours(12)).minus(Duration.ofMinutes(29)));
        wsaa.luegoFalla(new ResultadoInciertoException("ARCA no respondió a tiempo (timeout de lectura)."));
        assertThat(tickets.ticket(Ambiente.HOMOLOGACION, WSFE)).isEqualTo(renovado);
        assertThat(wsaa.llamadas).hasValue(3);
        assertThat(fila(ferreteriaId)).containsEntry("lease_dueno", null).containsEntry("espera_hasta", null);
    }

    @Test
    @DisplayName("S2: dentro del lapso del ambiente desde que se sacó, no se renueva aunque falte menos que el margen")
    void laRenovacionEsperaElLapso() {
        wsaa.vigencia = Duration.ofMinutes(20);
        TicketsDeAcceso tickets = instancia();
        TicketDeAcceso primero = tickets.ticket(Ambiente.HOMOLOGACION, WSFE);

        reloj.fijar(AHORA.plus(Duration.ofMinutes(9)));
        assertThat(tickets.ticket(Ambiente.HOMOLOGACION, WSFE)).isEqualTo(primero);
        assertThat(wsaa.llamadas).hasValue(1);

        reloj.fijar(AHORA.plus(Duration.ofMinutes(10)));
        assertThat(tickets.ticket(Ambiente.HOMOLOGACION, WSFE).token()).isNotEqualTo(primero.token());
        assertThat(wsaa.llamadas).hasValue(2);
    }

    @Test
    @DisplayName("descartar borra el TA guardado solo si es el rechazado: uno más nuevo queda")
    void descartarSoloElRechazado() {
        TicketsDeAcceso tickets = instancia();
        TicketDeAcceso guardado = tickets.ticket(Ambiente.HOMOLOGACION, WSFE);

        tickets.descartar(Ambiente.HOMOLOGACION, WSFE, new TicketDeAcceso("token-viejo", "sign-viejo", cuit));
        assertThat(fila(ferreteriaId)).containsEntry("huella_credencial", credencial.huella());
        assertThat(tickets.ticket(Ambiente.HOMOLOGACION, WSFE)).isEqualTo(guardado);

        tickets.descartar(Ambiente.HOMOLOGACION, WSFE, guardado);
        assertThat(fila(ferreteriaId)).containsEntry("huella_credencial", null).containsEntry("token_cifrado", null);
        assertThat(tickets.ticket(Ambiente.HOMOLOGACION, WSFE).token()).isNotEqualTo(guardado.token());
        assertThat(wsaa.llamadas).hasValue(2);
    }

    @Test
    @DisplayName("C4, 600: con ClienteWsfe y el ticket real, un 600 de WSFE da exactamente un loginCms más y un reintento, "
            + "con el TA nuevo")
    void el600DaUnLoginCmsMasYUnReintento() {
        TicketsDeAcceso tickets = instancia();
        TicketDeAcceso rechazado = tickets.ticket(Ambiente.HOMOLOGACION, WSFE);
        TransporteGrabador wsfe = new TransporteGrabador(new Transporte.Respuesta(200, sobre("fecaesolicitar-600")),
                new Transporte.Respuesta(200, sobre("fecaesolicitar-aprobado")));
        ClienteWsfe cliente = new ClienteWsfe(Ambiente.HOMOLOGACION, wsfe, URI.create("https://wsfe.example/service.asmx"),
                tickets);

        FeCaeResponse respuesta = cliente.feCaeSolicitar(new Auth(Ambiente.HOMOLOGACION, cuit),
                PedidoDePrueba.facturaB(LocalDate.of(2026, 10, 28)).numero(42).armar());

        assertThat(respuesta.feCabResp().resultado()).isEqualTo(Resultado.A);
        assertThat(wsaa.llamadas).hasValue(2);
        assertThat(wsfe.pedidos).hasSize(2);
        assertThat(wsfe.pedidos.get(0).sobre()).contains(rechazado.token());
        assertThat(wsfe.pedidos.get(1).sobre()).contains(wsaa.tokens.get(1)).doesNotContain(rechazado.token());
    }

    @Test
    @DisplayName("S17: el ticket lleva la CUIT de la credencial (nuevo y guardado), y en PRODUCCION ClienteWsfe no manda "
            + "nada si no es la del registro")
    void elTicketLlevaLaCuitDeLaCredencial() {
        long deLaPersonaFisica = MotorFiscalTestBase.cuitNuevo();
        CredencialDePrueba.activa(jdbc, cifrador, ferreteriaId, Ambiente.HOMOLOGACION, deLaPersonaFisica, AHORA);
        TicketsDeAcceso tickets = instancia();

        assertThat(tickets.ticket(Ambiente.HOMOLOGACION, WSFE).cuit()).isEqualTo(deLaPersonaFisica);
        assertThat(tickets.ticket(Ambiente.HOMOLOGACION, WSFE).cuit()).isEqualTo(deLaPersonaFisica);
        assertThat(wsaa.llamadas).hasValue(1);

        WsaaDePrueba wsaaDeProduccion = new WsaaDePrueba(reloj, CasDeArcaDePrueba.PRODUCCION);
        CredencialDePrueba.activa(jdbc, cifrador, ferreteriaId, Ambiente.PRODUCCION, deLaPersonaFisica, AHORA);
        TicketsDeAcceso deProduccion = new TicketsDeAcceso(pasos, firma, List.of(
                new ClienteWsaa(Ambiente.PRODUCCION, wsaaDeProduccion, propiedades.wsaa())), propiedades, reloj);
        TransporteGrabador wsfe = new TransporteGrabador();
        ClienteWsfe cliente = new ClienteWsfe(Ambiente.PRODUCCION, wsfe, URI.create("https://wsfe.example/service.asmx"),
                deProduccion);

        assertThatThrownBy(() -> cliente.feCompUltimoAutorizado(new Auth(Ambiente.PRODUCCION, cuit), 3, 6))
                .isInstanceOf(PedidoNoLlegoException.class);
        assertThat(wsaaDeProduccion.llamadas).hasValue(1);
        assertThat(wsfe.pedidos).isEmpty();
    }

    @Test
    @DisplayName("el TA de una ferretería no lo ve otra: la otra saca el suyo, con su credencial")
    void elTaDeUnaFerreteriaNoLoVeOtra() {
        TicketDeAcceso deA = instancia().ticket(Ambiente.HOMOLOGACION, WSFE);
        long otraCuit = MotorFiscalTestBase.cuitNuevo();
        long otra = ferreteriaConCredencial(otraCuit);

        TenantContext.setCurrentFerreteriaId(otra);
        assertThat(tickets.deClaveScoped(WSFE, Ambiente.HOMOLOGACION)).isEmpty();
        TicketDeAcceso deB = instancia().ticket(Ambiente.HOMOLOGACION, WSFE);

        assertThat(wsaa.llamadas).hasValue(2);
        assertThat(deB.token()).isNotEqualTo(deA.token());
        assertThat(deB.cuit()).isEqualTo(otraCuit);
        assertThat(fila(otra)).containsEntry("huella_credencial", credencial.huella());
        TenantContext.setCurrentFerreteriaId(ferreteriaId);
        assertThat(instancia().ticket(Ambiente.HOMOLOGACION, WSFE)).isEqualTo(deA);
        assertThat(wsaa.llamadas).hasValue(2);
    }

    @Test
    @DisplayName("el ticket no se pide con una transacción abierta: la llamada a WSAA nunca va dentro de una")
    void sinTransaccionAbierta() {
        TicketsDeAcceso tickets = instancia();

        assertThatThrownBy(() -> new TransactionTemplate(transacciones).executeWithoutResult(
                estado -> tickets.ticket(Ambiente.HOMOLOGACION, WSFE)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("transacción");
        assertThat(wsaa.llamadas).hasValue(0);
    }

    @Test
    @DisplayName("sin credencial activa del ambiente no se pide nada: no llegó")
    void sinCredencialNoSePide() {
        long sinCredencial = ferreteriaSinCredencial(MotorFiscalTestBase.cuitNuevo());
        TenantContext.setCurrentFerreteriaId(sinCredencial);

        assertThatThrownBy(() -> instancia().ticket(Ambiente.HOMOLOGACION, WSFE))
                .isInstanceOf(PedidoNoLlegoException.class)
                .hasMessage("No hay un certificado de ARCA activo de HOMOLOGACION: no se pidió el ticket de acceso.");
        assertThatThrownBy(() -> instancia().ticket(Ambiente.PRODUCCION, WSFE)).isInstanceOf(PedidoNoLlegoException.class);
        assertThat(wsaa.llamadas).hasValue(0);
    }

    @Test
    @DisplayName("C4: una falla del transporte de WSAA (incierta o sin llegar) no es un fault: no llegó, no cambia la espera "
            + "y el pedido siguiente vuelve a probar")
    void unaFallaDelTransporteNoCambiaLaEspera() {
        TicketsDeAcceso tickets = instancia();
        wsaa.luegoFalla(new ResultadoInciertoException("ARCA no respondió a tiempo (timeout de lectura)."))
                .luegoFalla(new PedidoNoLlegoException("No se pudo conectar con ARCA (conexión rechazada)."));

        assertThatThrownBy(() -> tickets.ticket(Ambiente.HOMOLOGACION, WSFE)).isInstanceOf(PedidoNoLlegoException.class)
                .hasMessageContaining("timeout de lectura");
        assertThatThrownBy(() -> tickets.ticket(Ambiente.HOMOLOGACION, WSFE)).isInstanceOf(PedidoNoLlegoException.class)
                .hasMessageContaining("conexión rechazada");
        assertThat(fila(ferreteriaId)).containsEntry("espera_hasta", null).containsEntry("ultima_falla", null)
                .containsEntry("lease_dueno", null);
        assertThat(tickets.ticket(Ambiente.HOMOLOGACION, WSFE).token()).isEqualTo(wsaa.tokens.get(0));
        assertThat(wsaa.llamadas).hasValue(3);
    }

    @Test
    @DisplayName("S2: con el lease de otra JVM, espera con pausas y relee hasta espera-ticket, y no llegó; con el lease "
            + "vencido, lo toma")
    void conElLeaseDeOtroEsperaHastaEsperaTicket() {
        jdbc.update("insert into ticket_acceso (ferreteria_id, servicio, ambiente, lease_dueno, lease_vence) values (?, "
                + "'wsfe', 'HOMOLOGACION', 'otra-jvm', ?)", ferreteriaId, utc(AHORA.plusSeconds(90)));
        AtomicInteger pausas = new AtomicInteger();
        TicketsDeAcceso tickets = instancia(() -> {
            pausas.incrementAndGet();
            reloj.avanzar(Duration.ofSeconds(1));
        });

        assertThatThrownBy(() -> tickets.ticket(Ambiente.HOMOLOGACION, WSFE)).isInstanceOf(PedidoNoLlegoException.class)
                .hasMessage("Otro pedido está sacando el ticket de acceso de ARCA y no terminó a tiempo: no se mandó nada.");
        assertThat(pausas).hasValue((int) propiedades.esperaTicket().toSeconds());
        assertThat(wsaa.llamadas).hasValue(0);

        reloj.fijar(AHORA.plusSeconds(90));
        assertThat(tickets.ticket(Ambiente.HOMOLOGACION, WSFE).token()).isEqualTo(wsaa.tokens.get(0));
        assertThat(wsaa.llamadas).hasValue(1);
        assertThat(fila(ferreteriaId)).containsEntry("lease_dueno", null);
    }
}
