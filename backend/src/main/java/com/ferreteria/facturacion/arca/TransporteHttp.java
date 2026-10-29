package com.ferreteria.facturacion.arca;

import com.ferreteria.facturacion.wsfe.PedidoNoLlegoException;
import com.ferreteria.facturacion.wsfe.ResultadoInciertoException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManagerFactory;

/**
 * El transporte real a ARCA (S5): {@code java.net.http.HttpClient} con HTTP/1.1, SOAP 1.1
 * ({@code Content-Type: text/xml; charset=utf-8} y la {@code SOAPAction} entre comillas) y un TLS propio:
 * <ul>
 *   <li>un {@code SSLContext} con el truststore del JDK (o el que se le pase, en los tests), y el host verificado;</li>
 *   <li>TLS 1.3 y 1.2, y en 1.2 solo suites ECDHE con AES-GCM o AES-CBC con SHA-2: servicios1 elige DHE de 1024 bits si se
 *       le ofrece una lista amplia (verificado el 2026-10-29). Ningún host de ARCA ofrece hoy TLS 1.3, pero queda
 *       habilitado.</li>
 * </ul>
 *
 * <p>Los tiempos salen de C6: {@code timeout-conexion} para conectar (con el handshake) y {@code timeout-lectura} para la
 * respuesta. Además, el pedido entero (conectar, mandar y leer el cuerpo) corta en la suma de los dos, porque el timeout del
 * {@code HttpClient} deja de correr con los headers: así una llamada nunca pasa el presupuesto del motor
 * ({@code app.facturacion.motor.presupuesto-llamada}) ni el lease de la serie.
 *
 * <p>Las fallas se mapean según si el pedido pudo haber llegado (ver {@link Transporte}). El mensaje es legible y nunca lleva
 * el sobre. No loguea nada: el que llama decide qué mostrar.
 */
public final class TransporteHttp implements Transporte {

    /** TLS 1.3 primero; 1.2 para los hosts de ARCA, que hoy no ofrecen 1.3. */
    static final String[] PROTOCOLOS = {"TLSv1.3", "TLSv1.2"};

    /** Las de TLS 1.3, y en 1.2 solo ECDHE (nunca DHE ni RSA estático). */
    static final String[] SUITES = {
        "TLS_AES_256_GCM_SHA384",
        "TLS_AES_128_GCM_SHA256",
        "TLS_CHACHA20_POLY1305_SHA256",
        "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
        "TLS_ECDHE_RSA_WITH_AES_256_CBC_SHA384",
        "TLS_ECDHE_RSA_WITH_AES_128_CBC_SHA256"
    };

    private final HttpClient cliente;
    private final Duration timeoutLectura;
    private final Duration tope;

    /** Con el truststore del JDK: el de producción. */
    public TransporteHttp(Duration timeoutConexion, Duration timeoutLectura) {
        this(timeoutConexion, timeoutLectura, null);
    }

    /**
     * Con otro truststore (en los tests, el del servidor de prueba).
     *
     * @param truststore las CA en que se confía; null es el del JDK ({@code cacerts})
     */
    public TransporteHttp(Duration timeoutConexion, Duration timeoutLectura, KeyStore truststore) {
        this(HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(positivo("el timeout de conexión", timeoutConexion))
                .followRedirects(HttpClient.Redirect.NEVER)
                .sslContext(contexto(truststore))
                .sslParameters(parametros())
                .build(), timeoutConexion, timeoutLectura);
    }

    /** Con un {@code HttpClient} armado aparte (en los tests, uno que falla como el del JDK). */
    TransporteHttp(HttpClient cliente, Duration timeoutConexion, Duration timeoutLectura) {
        this.cliente = Objects.requireNonNull(cliente, "el cliente HTTP");
        this.timeoutLectura = positivo("el timeout de lectura", timeoutLectura);
        this.tope = positivo("el timeout de conexión", timeoutConexion).plus(timeoutLectura);
    }

    @Override
    public Respuesta enviar(Pedido pedido) {
        HttpRequest pedidoHttp = HttpRequest.newBuilder(pedido.destino())
                .timeout(timeoutLectura)
                .header("Content-Type", "text/xml; charset=utf-8")
                .header("SOAPAction", "\"" + pedido.soapAction() + "\"")
                .POST(HttpRequest.BodyPublishers.ofString(pedido.sobre(), StandardCharsets.UTF_8))
                .build();
        CompletableFuture<HttpResponse<String>> enCurso =
                cliente.sendAsync(pedidoHttp, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        try {
            HttpResponse<String> respuesta = enCurso.get(tope.toMillis(), TimeUnit.MILLISECONDS);
            return new Respuesta(respuesta.statusCode(), respuesta.body());
        } catch (ExecutionException e) {
            throw clasificar(e.getCause() == null ? e : e.getCause());
        } catch (TimeoutException e) {
            enCurso.cancel(true);
            throw new ResultadoInciertoException("ARCA no respondió a tiempo (timeout de lectura).", e);
        } catch (InterruptedException e) {
            enCurso.cancel(true);
            Thread.currentThread().interrupt();
            throw new ResultadoInciertoException("Se interrumpió la espera de la respuesta de ARCA.", e);
        }
    }

    /**
     * Si la falla pasó antes de mandar el pedido (no llegó) o después (incierto). {@link HttpConnectTimeoutException} es un
     * {@link HttpTimeoutException}: se mira primero. Lo que no se reconoce como anterior al envío es incierto, que es lo
     * seguro para {@code FECAESolicitar}.
     */
    static RuntimeException clasificar(Throwable falla) {
        if (en(falla, HttpConnectTimeoutException.class)) {
            return new PedidoNoLlegoException("No se pudo conectar con ARCA (timeout de conexión).", falla);
        }
        if (en(falla, HttpTimeoutException.class)) {
            return new ResultadoInciertoException("ARCA no respondió a tiempo (timeout de lectura).", falla);
        }
        if (en(falla, UnresolvedAddressException.class) || en(falla, UnknownHostException.class)) {
            return new PedidoNoLlegoException("No se pudo conectar con ARCA (host desconocido).", falla);
        }
        if (en(falla, ConnectException.class)) {
            return new PedidoNoLlegoException("No se pudo conectar con ARCA (conexión rechazada).", falla);
        }
        if (en(falla, SSLHandshakeException.class)) {
            return new PedidoNoLlegoException("Falló el handshake TLS.", falla);
        }
        return new ResultadoInciertoException("Se cortó la comunicación con ARCA después de mandar el pedido.", falla);
    }

    /** Si la falla o alguna de sus causas es de ese tipo. */
    private static boolean en(Throwable falla, Class<? extends Throwable> tipo) {
        for (Throwable t = falla; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (tipo.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    /** El TLS del transporte; package-private para que el handshake real con ARCA (tag {@code arca-real}) use el mismo. */
    static SSLContext contexto(KeyStore truststore) {
        try {
            TrustManagerFactory confianza = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            confianza.init(truststore);
            SSLContext contexto = SSLContext.getInstance("TLS");
            contexto.init(null, confianza.getTrustManagers(), null);
            return contexto;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("No se pudo armar el TLS para ARCA.", e);
        }
    }

    /** Las suites y protocolos del transporte, con el host verificado; package-private por la misma razón. */
    static SSLParameters parametros() {
        SSLParameters parametros = new SSLParameters(SUITES.clone(), PROTOCOLOS.clone());
        parametros.setEndpointIdentificationAlgorithm("HTTPS");
        return parametros;
    }

    private static Duration positivo(String que, Duration duracion) {
        Objects.requireNonNull(duracion, que);
        if (duracion.isNegative() || duracion.isZero()) {
            throw new IllegalArgumentException(que + " tiene que ser positivo: " + duracion);
        }
        return duracion;
    }
}
