package com.ferreteria.facturacion.arca;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/**
 * Un {@code HttpClient} que no sale a la red: cada pedido falla con la excepción que el test elija, como la entregaría el del
 * JDK. Sirve para lo que la red no da de forma determinista, como un timeout de conexión.
 */
final class HttpClientQueFalla extends HttpClient {

    private final Throwable falla;

    HttpClientQueFalla(Throwable falla) {
        this.falla = falla;
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest pedido, HttpResponse.BodyHandler<T> manejador) {
        return CompletableFuture.failedFuture(falla);
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest pedido, HttpResponse.BodyHandler<T> manejador,
            HttpResponse.PushPromiseHandler<T> promesas) {
        return sendAsync(pedido, manejador);
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest pedido, HttpResponse.BodyHandler<T> manejador)
            throws IOException, InterruptedException {
        throw new UnsupportedOperationException("TransporteHttp usa sendAsync");
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
        return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
        return null;
    }

    @Override
    public SSLParameters sslParameters() {
        return null;
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return Optional.empty();
    }

    @Override
    public Version version() {
        return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
        return Optional.empty();
    }
}
