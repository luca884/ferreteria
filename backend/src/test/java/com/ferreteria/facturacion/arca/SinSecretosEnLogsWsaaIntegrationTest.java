package com.ferreteria.facturacion.arca;

import static com.ferreteria.facturacion.arca.FixturesArca.sobre;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.ferreteria.facturacion.arca.ClienteWsfeTest.TransporteGrabador;
import com.ferreteria.facturacion.credencial.FirmaCms;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.wsfe.Auth;
import com.ferreteria.facturacion.wsfe.PedidoDePrueba;
import com.ferreteria.facturacion.wsfe.Resultado;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * S10, la parte de WSAA: con {@code com.ferreteria.facturacion} en DEBUG, un flujo completo (el ticket con {@code loginCms},
 * un FECAESolicitar con 600, el ticket nuevo y el reintento) no deja en la salida el token, el sign, el CMS ({@code in0}) ni
 * «PRIVATE KEY». Y lo que los lleva enmascara su {@code toString}. Con la base real, el WSAA falso y un WSFE falso.
 */
@ExtendWith(OutputCaptureExtension.class)
class SinSecretosEnLogsWsaaIntegrationTest extends TicketsDeAccesoTestBase {

    @Test
    @DisplayName("S10: ticket + FECAESolicitar en DEBUG sin token, sign, CMS ni clave privada en la salida")
    void unFlujoCompletoSinSecretosEnLaSalida(CapturedOutput salida) {
        Logger facturacion = (Logger) LoggerFactory.getLogger("com.ferreteria.facturacion");
        Level antes = facturacion.getLevel();
        facturacion.setLevel(Level.DEBUG);
        try {
            TicketsDeAcceso tickets = instancia();
            TransporteGrabador wsfe = new TransporteGrabador(new Transporte.Respuesta(200, sobre("fecaesolicitar-600")),
                    new Transporte.Respuesta(200, sobre("fecaesolicitar-aprobado")));
            ClienteWsfe cliente = new ClienteWsfe(Ambiente.HOMOLOGACION, wsfe,
                    URI.create("https://wsfe.example/service.asmx"), tickets);

            var respuesta = cliente.feCaeSolicitar(new Auth(Ambiente.HOMOLOGACION, cuit),
                    PedidoDePrueba.facturaB(LocalDate.of(2026, 10, 28)).numero(42).armar());

            assertThat(respuesta.feCabResp().resultado()).isEqualTo(Resultado.A);
            assertThat(wsaa.llamadas).hasValue(2);
            assertThat(salida.getAll()).contains("WSAA loginCms en HOMOLOGACION: status 200 en ")
                    .contains("WSFE FECAESolicitar en HOMOLOGACION: status 200 en ");
            List<String> secretos = new ArrayList<>(wsaa.tokens);
            secretos.addAll(wsaa.signs);
            secretos.addAll(wsaa.cms);
            assertThat(secretos).hasSize(6);
            for (String secreto : secretos) {
                assertThat(salida.getAll()).doesNotContain(secreto);
            }
            assertThat(salida.getAll()).doesNotContain("PRIVATE KEY").doesNotContain("<in0>").doesNotContain("<Auth>")
                    .doesNotContain("loginTicketResponse");
        } finally {
            facturacion.setLevel(antes);
        }
    }

    @Test
    @DisplayName("S10: el TA de WSAA, el guardado, el CMS firmado y la fila del ticket enmascaran su toString")
    void toStringEnmascarado() {
        TicketDeAcceso ticket = instancia().ticket(Ambiente.HOMOLOGACION, WSFE);
        RespuestaLoginCms ta = new RespuestaLoginCms(ticket.token(), ticket.sign(), AHORA, AHORA.plusSeconds(60));
        PasosDelTicket.Guardado guardado = pasos.leer(Ambiente.HOMOLOGACION, WSFE).guardado();
        FirmaCms.Firmado firmado = firma.firmar(Ambiente.HOMOLOGACION, Tra.armar(Instant.now(reloj), WSFE).getBytes());
        TicketAcceso fila = tickets.deClaveScoped(WSFE, Ambiente.HOMOLOGACION).orElseThrow();

        for (Object conSecretos : new Object[] {ticket, ta, guardado, fila}) {
            assertThat(conSecretos.toString()).as(conSecretos.getClass().getSimpleName())
                    .doesNotContain(ticket.token()).doesNotContain(ticket.sign());
        }
        assertThat(guardado.token()).isEqualTo(ticket.token());
        assertThat(firmado.toString()).doesNotContain(firmado.cmsBase64()).contains("cms=***");
        assertThat(fila.toString()).contains("ta=***");
    }
}
