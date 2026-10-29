package com.ferreteria.facturacion.arca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.facturacion.wsfe.PedidoNoLlegoException;
import com.ferreteria.facturacion.wsfe.ResultadoInciertoException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S5: el transporte HTTP de verdad, contra el {@code HttpServer} y el {@code HttpsServer} del JDK (sin red). Cada falla dice
 * si el pedido pudo haber llegado: lo que pasa antes de mandar (conexión, DNS, TLS) es {@link PedidoNoLlegoException}, y lo
 * que pasa después (lectura, una respuesta que no termina), {@link ResultadoInciertoException}. En TLS 1.2 solo se ofrecen
 * suites ECDHE: servicios1 elige DHE de 1024 bits si se le ofrece.
 */
class TransporteHttpTest {

    private static final Duration CONEXION = Duration.ofSeconds(2);
    private static final Duration LECTURA = Duration.ofSeconds(2);
    private static final String SOBRE = PlantillasWsfe.feDummy();
    private static final String RESPUESTA = "<respuesta>con acentos: ñandú</respuesta>";

    private static Transporte.Pedido pedido(URI destino) {
        return new Transporte.Pedido(destino, MetodoWsfe.FE_DUMMY.soapAction(), SOBRE);
    }

    @Test
    @DisplayName("un 200: manda POST con el Content-Type y la SOAPAction de SOAP 1.1, y devuelve el status y el cuerpo")
    void respuesta200() throws IOException {
        AtomicReference<HttpExchange> recibido = new AtomicReference<>();
        AtomicReference<String> cuerpo = new AtomicReference<>();
        HttpServer servidor = servidor(intercambio -> {
            recibido.set(intercambio);
            cuerpo.set(new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            responder(intercambio, 200, RESPUESTA);
        });
        try {
            Transporte.Respuesta respuesta = new TransporteHttp(CONEXION, LECTURA).enviar(pedido(uri(servidor)));

            assertThat(respuesta.status()).isEqualTo(200);
            assertThat(respuesta.cuerpo()).isEqualTo(RESPUESTA);
            assertThat(recibido.get().getRequestMethod()).isEqualTo("POST");
            assertThat(recibido.get().getRequestHeaders().getFirst("Content-Type")).isEqualTo("text/xml; charset=utf-8");
            assertThat(recibido.get().getRequestHeaders().getFirst("SOAPAction"))
                    .isEqualTo("\"http://ar.gov.afip.dif.FEV1/FEDummy\"");
            assertThat(cuerpo.get()).isEqualTo(SOBRE);
        } finally {
            servidor.stop(0);
        }
    }

    @Test
    @DisplayName("un 500 no es una falla del transporte: vuelve con su status y su cuerpo, sin interpretar el SOAP")
    void otroStatus() throws IOException {
        HttpServer servidor = servidor(intercambio -> responder(intercambio, 500, "<soap:Fault/>"));
        try {
            Transporte.Respuesta respuesta = new TransporteHttp(CONEXION, LECTURA).enviar(pedido(uri(servidor)));

            assertThat(respuesta).isEqualTo(new Transporte.Respuesta(500, "<soap:Fault/>"));
        } finally {
            servidor.stop(0);
        }
    }

    @Test
    @DisplayName("conexión rechazada (127.0.0.1:9, donde no escucha nadie): no llegó")
    void conexionRechazada() {
        assertThatThrownBy(() -> new TransporteHttp(CONEXION, LECTURA).enviar(pedido(URI.create("http://127.0.0.1:9/"))))
                .isInstanceOf(PedidoNoLlegoException.class)
                .hasMessage("No se pudo conectar con ARCA (conexión rechazada).");
    }

    @Test
    @DisplayName("host desconocido (como lo entrega el HttpClient del JDK: ConnectException por UnresolvedAddress): no llegó")
    void hostDesconocido() {
        ConnectException comoElJdk = new ConnectException();
        comoElJdk.initCause(new UnresolvedAddressException());
        TransporteHttp transporte = new TransporteHttp(new HttpClientQueFalla(comoElJdk), CONEXION, LECTURA);

        assertThatThrownBy(() -> transporte.enviar(pedido(URI.create("https://wswhomo.example/"))))
                .isInstanceOf(PedidoNoLlegoException.class)
                .hasMessage("No se pudo conectar con ARCA (host desconocido).");
    }

    @Test
    @DisplayName("timeout de conexión (HttpConnectTimeoutException, que es un HttpTimeoutException): no llegó")
    void timeoutDeConexion() {
        TransporteHttp transporte = new TransporteHttp(
                new HttpClientQueFalla(new HttpConnectTimeoutException("HTTP connect timed out")), CONEXION, LECTURA);

        assertThatThrownBy(() -> transporte.enviar(pedido(URI.create("https://wswhomo.example/"))))
                .isInstanceOf(PedidoNoLlegoException.class)
                .hasMessage("No se pudo conectar con ARCA (timeout de conexión).");
    }

    @Test
    @DisplayName("una lectura que excede el timeout: incierto (el pedido ya salió)")
    void timeoutDeLectura() throws IOException {
        HttpServer servidor = servidor(intercambio -> {
            dormir(1_500);
            responder(intercambio, 200, RESPUESTA);
        });
        try {
            TransporteHttp transporte = new TransporteHttp(CONEXION, Duration.ofMillis(300));

            assertThatThrownBy(() -> transporte.enviar(pedido(uri(servidor))))
                    .isInstanceOf(ResultadoInciertoException.class)
                    .hasMessage("ARCA no respondió a tiempo (timeout de lectura).");
        } finally {
            servidor.stop(0);
        }
    }

    @Test
    @DisplayName("una respuesta que empieza y no termina corta en conexión + lectura: incierto, dentro del presupuesto")
    void respuestaQueNoTermina() throws IOException {
        HttpServer servidor = servidor(intercambio -> {
            intercambio.sendResponseHeaders(200, 1_000);
            intercambio.getResponseBody().write("<a>".getBytes(StandardCharsets.UTF_8));
            intercambio.getResponseBody().flush();
            dormir(3_000);
            intercambio.close();
        });
        try {
            TransporteHttp transporte = new TransporteHttp(Duration.ofMillis(300), Duration.ofMillis(300));
            long desde = System.nanoTime();

            assertThatThrownBy(() -> transporte.enviar(pedido(uri(servidor))))
                    .isInstanceOf(ResultadoInciertoException.class)
                    .hasMessage("ARCA no respondió a tiempo (timeout de lectura).");
            assertThat(Duration.ofNanos(System.nanoTime() - desde)).isLessThan(Duration.ofMillis(2_000));
        } finally {
            servidor.stop(0);
        }
    }

    @Test
    @DisplayName("un servidor que en TLS 1.2 solo ofrece DHE: el handshake falla y el pedido no llegó")
    void soloDhe() throws IOException {
        try (ServidorHttpsDePrueba servidor = ServidorHttpsDePrueba.con("TLSv1.2",
                new String[] {"TLS_DHE_RSA_WITH_AES_128_GCM_SHA256"}, intercambio -> responder(intercambio, 200, RESPUESTA))) {
            TransporteHttp transporte = new TransporteHttp(CONEXION, LECTURA, ServidorHttpsDePrueba.truststore());

            assertThatThrownBy(() -> transporte.enviar(pedido(servidor.uri("/wsfev1/service.asmx"))))
                    .isInstanceOf(PedidoNoLlegoException.class)
                    .hasMessage("Falló el handshake TLS.");
        }
    }

    @Test
    @DisplayName("un servidor que en TLS 1.2 ofrece ECDHE: anda")
    void conEcdhe() throws IOException {
        try (ServidorHttpsDePrueba servidor = ServidorHttpsDePrueba.con("TLSv1.2",
                new String[] {"TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"}, intercambio -> responder(intercambio, 200, RESPUESTA))) {
            TransporteHttp transporte = new TransporteHttp(CONEXION, LECTURA, ServidorHttpsDePrueba.truststore());

            Transporte.Respuesta respuesta = transporte.enviar(pedido(servidor.uri("/wsfev1/service.asmx")));

            assertThat(respuesta).isEqualTo(new Transporte.Respuesta(200, RESPUESTA));
        }
    }

    @Test
    @DisplayName("con el truststore del JDK, el certificado autofirmado no se acepta: el handshake falla y no llegó")
    void truststoreDelJdk() throws IOException {
        try (ServidorHttpsDePrueba servidor = ServidorHttpsDePrueba.con("TLSv1.2",
                new String[] {"TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384"}, intercambio -> responder(intercambio, 200, RESPUESTA))) {
            assertThatThrownBy(() -> new TransporteHttp(CONEXION, LECTURA).enviar(pedido(servidor.uri("/"))))
                    .isInstanceOf(PedidoNoLlegoException.class)
                    .hasMessage("Falló el handshake TLS.");
        }
    }

    private static HttpServer servidor(HttpHandler manejador) throws IOException {
        HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", manejador);
        servidor.start();
        return servidor;
    }

    private static URI uri(HttpServer servidor) {
        return URI.create("http://127.0.0.1:" + servidor.getAddress().getPort() + "/wsfev1/service.asmx");
    }

    private static void responder(HttpExchange intercambio, int status, String cuerpo) throws IOException {
        byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
        intercambio.getResponseHeaders().add("Content-Type", "text/xml; charset=utf-8");
        intercambio.sendResponseHeaders(status, bytes.length);
        try (OutputStream salida = intercambio.getResponseBody()) {
            salida.write(bytes);
        }
    }

    private static void dormir(long milisegundos) {
        try {
            Thread.sleep(milisegundos);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
