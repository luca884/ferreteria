package com.ferreteria.facturacion.credencial;

import java.math.BigInteger;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.CertIOException;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;

/**
 * La CA del simulador (rama 5, S5): crea la CA de una ferretería (autofirmada, RSA 2048 del JDK) y firma con ella un pedido
 * de certificado, como haría ARCA, para probar el alta completa en SIMULADOR sin pasar por ARCA. El JDK no emite
 * certificados X.509: los arma BouncyCastle, con los proveedores del JDK y sin registrarse como proveedor. Las horas salen
 * del {@code Clock} de quien llama.
 */
final class FirmaDelSimulador {

    /** El nombre de la CA, que el Dueño ve como emisor. */
    static final String NOMBRE = "Simulador de ARCA";
    /** Lo que dura un certificado del simulador: dos años, como los de ARCA. */
    static final Duration VIGENCIA_DEL_CERTIFICADO = Duration.ofDays(730);
    private static final Duration VIGENCIA_DE_LA_CA = Duration.ofDays(3650);
    private static final String FIRMA = "SHA256withRSA";
    private static final SecureRandom AZAR = new SecureRandom();

    /** Una CA recién creada: su certificado y su par de claves; {@link #toString()} no muestra la privada. */
    record CaNueva(X509Certificate certificado, PrivateKey privada) {

        @Override
        public String toString() {
            return "FirmaDelSimulador.CaNueva[clave=***]";
        }
    }

    private FirmaDelSimulador() {
    }

    /** Una CA nueva, vigente diez años desde {@code ahora}. */
    static CaNueva crearCa(Instant ahora) {
        try {
            KeyPairGenerator generador = KeyPairGenerator.getInstance("RSA");
            generador.initialize(PedidoDeCertificado.BITS, AZAR);
            KeyPair claves = generador.generateKeyPair();
            X500Name nombre = new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.C, "AR")
                    .addRDN(BCStyle.O, PedidoDeCertificado.NOMBRE_DEL_SISTEMA).addRDN(BCStyle.CN, NOMBRE).build();
            X509v3CertificateBuilder constructor = new JcaX509v3CertificateBuilder(nombre, serie(), Date.from(ahora),
                    Date.from(ahora.plus(VIGENCIA_DE_LA_CA)), nombre, claves.getPublic());
            constructor.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            constructor.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            return new CaNueva(firmar(constructor, claves.getPrivate()), claves.getPrivate());
        } catch (GeneralSecurityException | CertIOException e) {
            throw new IllegalStateException("No se pudo crear la CA del simulador.", e);
        }
    }

    /** El certificado del pedido, firmado por la CA: el sujeto y la clave pública del CSR, vigente dos años desde ahora. */
    static X509Certificate firmar(String pedidoPem, X509Certificate ca, PrivateKey claveDeLaCa, Instant ahora) {
        try {
            JcaPKCS10CertificationRequest pedido = PedidoDeCertificado.leer(pedidoPem);
            X509v3CertificateBuilder constructor = new JcaX509v3CertificateBuilder(ca, serie(), Date.from(ahora),
                    Date.from(ahora.plus(VIGENCIA_DEL_CERTIFICADO)), pedido.getSubject(), pedido.getPublicKey());
            constructor.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            return firmar(constructor, claveDeLaCa);
        } catch (GeneralSecurityException | CertIOException e) {
            throw new IllegalStateException("La CA del simulador no pudo firmar el pedido.", e);
        }
    }

    private static X509Certificate firmar(X509v3CertificateBuilder constructor, PrivateKey clave)
            throws GeneralSecurityException {
        try {
            return new JcaX509CertificateConverter()
                    .getCertificate(constructor.build(new JcaContentSignerBuilder(FIRMA).build(clave)));
        } catch (OperatorCreationException e) {
            throw new GeneralSecurityException(e);
        }
    }

    private static BigInteger serie() {
        return new BigInteger(64, AZAR).add(BigInteger.ONE);
    }
}
