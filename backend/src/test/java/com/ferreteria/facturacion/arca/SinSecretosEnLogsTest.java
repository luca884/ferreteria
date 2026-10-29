package com.ferreteria.facturacion.arca;

import static com.ferreteria.facturacion.arca.FixturesArca.sobre;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.ferreteria.facturacion.model.Ambiente;
import com.ferreteria.facturacion.wsfe.Auth;
import com.ferreteria.facturacion.wsfe.FeCaeResponse;
import com.ferreteria.facturacion.wsfe.PedidoDePrueba;
import com.ferreteria.facturacion.wsfe.Resultado;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * S10, la parte de WSFE: nunca se loguea el sobre ni la respuesta, que llevan el token y el sign del TA, y lo que los lleva
 * enmascara su {@code toString}. Con el logger de {@code com.ferreteria.facturacion.arca} en DEBUG y un FECAESolicitar
 * completo (con un 600 y su reintento) contra el {@code HttpServer} del JDK.
 */
@ExtendWith(OutputCaptureExtension.class)
class SinSecretosEnLogsTest {

    private static final TicketDeAcceso PRIMERO =
            new TicketDeAcceso("TOKEN-SECRETO-UNO-PD94bWwg", "SIGN-SECRETO-UNO-c2lnbg==", 30999999995L);
    private static final TicketDeAcceso SEGUNDO =
            new TicketDeAcceso("TOKEN-SECRETO-DOS-PD94bWwg", "SIGN-SECRETO-DOS-c2lnbg==", 30999999995L);
    private static final List<String> SECRETOS = List.of(PRIMERO.token(), PRIMERO.sign(), SEGUNDO.token(), SEGUNDO.sign());

    @Test
    @DisplayName("el ticket, el pedido y la respuesta del transporte no muestran el token, el sign ni el sobre")
    void toStringEnmascarado() {
        String sobre = PlantillasWsfe.feParamGetPtosVenta(PRIMERO, 30999999995L);
        Transporte.Pedido pedido = new Transporte.Pedido(URI.create("https://wsfe.example/"),
                MetodoWsfe.FE_PARAM_GET_PTOS_VENTA.soapAction(), sobre);
        Transporte.Respuesta respuesta = new Transporte.Respuesta(200, sobre);

        assertThat(PRIMERO.toString()).contains("token=***").contains("sign=***").contains("30999999995");
        for (Object conSecretos : new Object[] {PRIMERO, pedido, respuesta}) {
            assertThat(conSecretos.toString()).as(conSecretos.getClass().getSimpleName())
                    .doesNotContain(PRIMERO.token()).doesNotContain(PRIMERO.sign()).doesNotContain("<Auth>");
        }
    }

    @Test
    @DisplayName("un FECAESolicitar completo en DEBUG no deja en la salida ni el token ni el sign: solo método, ambiente, "
            + "status y milisegundos")
    void feCaeSolicitarSinSecretosEnLaSalida(CapturedOutput salida) throws IOException {
        Logger arca = (Logger) LoggerFactory.getLogger("com.ferreteria.facturacion.arca");
        Level antes = arca.getLevel();
        arca.setLevel(Level.DEBUG);
        List<String> recibidos = new ArrayList<>();
        AtomicInteger llamadas = new AtomicInteger();
        HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", intercambio -> {
            recibidos.add(new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] cuerpo = sobre(llamadas.getAndIncrement() == 0 ? "fecaesolicitar-600" : "fecaesolicitar-aprobado")
                    .getBytes(StandardCharsets.UTF_8);
            intercambio.sendResponseHeaders(200, cuerpo.length);
            try (OutputStream respuesta = intercambio.getResponseBody()) {
                respuesta.write(cuerpo);
            }
        });
        servidor.start();
        try {
            ClienteWsfe cliente = new ClienteWsfe(Ambiente.HOMOLOGACION,
                    new TransporteHttp(Duration.ofSeconds(2), Duration.ofSeconds(2)),
                    URI.create("http://127.0.0.1:" + servidor.getAddress().getPort() + "/wsfev1/service.asmx"),
                    new ClienteWsfeTest.TicketsDePrueba(PRIMERO, SEGUNDO));

            FeCaeResponse respuesta = cliente.feCaeSolicitar(new Auth(Ambiente.HOMOLOGACION, 30999999995L),
                    PedidoDePrueba.facturaB(LocalDate.of(2026, 10, 29)).numero(42).armar());

            assertThat(respuesta.feCabResp().resultado()).isEqualTo(Resultado.A);
            // Los secretos viajaron (el servidor los recibió), y el DEBUG estaba prendido.
            assertThat(recibidos).hasSize(2);
            assertThat(recibidos.get(0)).contains(PRIMERO.token()).contains(PRIMERO.sign());
            assertThat(recibidos.get(1)).contains(SEGUNDO.token()).contains(SEGUNDO.sign());
            assertThat(salida.getAll()).contains("WSFE FECAESolicitar en HOMOLOGACION: status 200 en ");
            for (String secreto : SECRETOS) {
                assertThat(salida.getAll()).doesNotContain(secreto);
            }
            assertThat(salida.getAll()).doesNotContain("<Auth>").doesNotContain("FECAESolicitarResult")
                    .doesNotContain("76543210987654");
        } finally {
            servidor.stop(0);
            arca.setLevel(antes);
        }
    }
}
