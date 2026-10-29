package com.ferreteria.facturacion.arca;

import com.ferreteria.facturacion.credencial.AutoridadDePrueba;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.KeyPair;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Un {@code HttpsServer} del JDK en 127.0.0.1, con un certificado autofirmado de prueba (RSA 2048, con la IP en el
 * {@code subjectAltName} para que el cliente verifique el host) y solo el protocolo y las suites que el test elija. El
 * cliente confía en él con {@link #truststore()}. BouncyCastle arma el certificado, sin registrarse como proveedor.
 */
final class ServidorHttpsDePrueba implements AutoCloseable {

    private static final char[] CLAVE = "solo-para-el-test".toCharArray();
    private static final KeyStore TRUSTSTORE;
    private static final SSLContext CONTEXTO;

    static {
        try {
            KeyPair claves = AutoridadDePrueba.rsa(2048);
            X500Name sujeto = new X500Name("CN=127.0.0.1, O=Pruebas, C=AR");
            JcaX509v3CertificateBuilder constructor = new JcaX509v3CertificateBuilder(sujeto, BigInteger.valueOf(4242),
                    Date.from(Instant.parse("2020-01-01T00:00:00Z")), Date.from(Instant.parse("2040-01-01T00:00:00Z")),
                    sujeto, claves.getPublic());
            constructor.addExtension(Extension.subjectAlternativeName, false,
                    new GeneralNames(new GeneralName(GeneralName.iPAddress, "127.0.0.1")));
            X509Certificate certificado = new JcaX509CertificateConverter().getCertificate(
                    constructor.build(new JcaContentSignerBuilder("SHA256withRSA").build(claves.getPrivate())));

            KeyStore llavero = KeyStore.getInstance("PKCS12");
            llavero.load(null, null);
            llavero.setKeyEntry("servidor", claves.getPrivate(), CLAVE, new Certificate[] {certificado});
            KeyManagerFactory llaves = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            llaves.init(llavero, CLAVE);
            CONTEXTO = SSLContext.getInstance("TLS");
            CONTEXTO.init(llaves.getKeyManagers(), null, null);

            TRUSTSTORE = KeyStore.getInstance("PKCS12");
            TRUSTSTORE.load(null, null);
            TRUSTSTORE.setCertificateEntry("servidor", certificado);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private final HttpsServer servidor;

    private ServidorHttpsDePrueba(HttpsServer servidor) {
        this.servidor = servidor;
    }

    /** Un servidor que solo habla ese protocolo con esas suites, y contesta con {@code manejador}. */
    static ServidorHttpsDePrueba con(String protocolo, String[] suites, HttpHandler manejador) throws IOException {
        HttpsServer servidor = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.setHttpsConfigurator(new HttpsConfigurator(CONTEXTO) {
            @Override
            public void configure(HttpsParameters parametros) {
                SSLParameters ssl = getSSLContext().getDefaultSSLParameters();
                ssl.setProtocols(new String[] {protocolo});
                ssl.setCipherSuites(suites);
                parametros.setSSLParameters(ssl);
            }
        });
        servidor.createContext("/", manejador);
        servidor.start();
        return new ServidorHttpsDePrueba(servidor);
    }

    /** El truststore con el certificado del servidor, para el cliente. */
    static KeyStore truststore() {
        return TRUSTSTORE;
    }

    URI uri(String camino) {
        return URI.create("https://127.0.0.1:" + servidor.getAddress().getPort() + camino);
    }

    @Override
    public void close() {
        servidor.stop(0);
    }
}
