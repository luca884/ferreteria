package com.ferreteria.facturacion.credencial;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

/**
 * Una CA generada en el momento, para los tests: ARCA no firma certificados de prueba. Emite certificados con el sujeto, la
 * clave pública, la vigencia y hasta el texto del emisor que el test quiera. Con BouncyCastle, sin registrarlo como
 * proveedor.
 */
public final class AutoridadDePrueba {

    private static final AtomicLong SERIES = new AtomicLong(1000);
    private static final Instant DESDE = Instant.parse("2020-01-01T00:00:00Z");
    private static final Instant HASTA = Instant.parse("2040-01-01T00:00:00Z");

    private final String nombre;
    private final KeyPair claves;
    private final X509Certificate certificado;

    private AutoridadDePrueba(String nombre, KeyPair claves, X509Certificate certificado) {
        this.nombre = nombre;
        this.claves = claves;
        this.certificado = certificado;
    }

    /** Una CA con ese CN, vigente de 2020 a 2040. */
    public static AutoridadDePrueba nueva(String nombre) {
        return nueva(nombre, DESDE, HASTA);
    }

    /** Una CA con ese CN y esa vigencia. */
    public static AutoridadDePrueba nueva(String nombre, Instant desde, Instant hasta) {
        try {
            KeyPair claves = rsa(2048);
            X500Name sujeto = new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.C, "AR").addRDN(BCStyle.O, "Pruebas")
                    .addRDN(BCStyle.CN, nombre).build();
            JcaX509v3CertificateBuilder constructor = new JcaX509v3CertificateBuilder(sujeto,
                    BigInteger.valueOf(SERIES.incrementAndGet()), Date.from(desde), Date.from(hasta), sujeto,
                    claves.getPublic());
            constructor.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            X509Certificate certificado = new JcaX509CertificateConverter().getCertificate(
                    constructor.build(new JcaContentSignerBuilder("SHA256withRSA").build(claves.getPrivate())));
            return new AutoridadDePrueba(nombre, claves, certificado);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static KeyPair rsa(int bits) throws Exception {
        KeyPairGenerator generador = KeyPairGenerator.getInstance("RSA");
        generador.initialize(bits);
        return generador.generateKeyPair();
    }

    /** El sujeto de un certificado de ARCA: C=AR, O=razón social, CN=Ferreteria SaaS, serialNumber=CUIT y 11 dígitos. */
    public static X500Name sujeto(String razonSocial, String cuit) {
        return new X500NameBuilder(BCStyle.INSTANCE).addRDN(BCStyle.C, "AR").addRDN(BCStyle.O, razonSocial)
                .addRDN(BCStyle.CN, "Ferreteria SaaS").addRDN(BCStyle.SERIALNUMBER, "CUIT " + cuit).build();
    }

    /** Un certificado para esa clave pública y ese sujeto, firmado por esta CA y con su nombre de emisor. */
    public X509Certificate emitir(PublicKey publica, X500Name sujeto, Instant desde, Instant hasta) {
        return emitir(publica, sujeto, desde, hasta, X500Name.getInstance(certificado.getSubjectX500Principal().getEncoded()));
    }

    /** Lo mismo, pero con el texto de emisor que se quiera (la firma sigue siendo de esta CA). */
    public X509Certificate emitir(PublicKey publica, X500Name sujeto, Instant desde, Instant hasta, X500Name emisor) {
        try {
            JcaX509v3CertificateBuilder constructor = new JcaX509v3CertificateBuilder(emisor,
                    BigInteger.valueOf(SERIES.incrementAndGet()), Date.from(desde), Date.from(hasta), sujeto, publica);
            return new JcaX509CertificateConverter().getCertificate(
                    constructor.build(new JcaContentSignerBuilder("SHA256withRSA").build(claves.getPrivate())));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String nombre() {
        return nombre;
    }

    public X509Certificate certificado() {
        return certificado;
    }

    /** La clave privada de la CA (para guardarla cifrada como la de una CA del simulador). */
    public PrivateKey privada() {
        return claves.getPrivate();
    }
}
