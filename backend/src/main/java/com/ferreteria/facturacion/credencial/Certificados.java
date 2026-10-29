package com.ferreteria.facturacion.credencial;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;

/**
 * Lo que {@code facturacion} lee y escribe de un certificado X.509 o de una clave pública: el PEM, el nombre común (CN), la
 * huella y el {@code serialNumber} del sujeto. Solo lectura y formato; nada de esto toca una clave privada.
 */
final class Certificados {

    private static final HexFormat HEXA = HexFormat.of().withUpperCase();
    private static final HexFormat HEXA_CON_DOS_PUNTOS = HexFormat.ofDelimiter(":").withUpperCase();

    private Certificados() {
    }

    /** Un certificado X.509 en PEM o DER, con el {@code CertificateFactory} del JDK. */
    static X509Certificate leer(byte[] pemODer) throws CertificateException {
        return leer(new ByteArrayInputStream(pemODer));
    }

    static X509Certificate leer(InputStream pemODer) throws CertificateException {
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(pemODer);
    }

    /** El CN del sujeto (el nombre de una CA: «Computadores Test»), o el sujeto entero si no tiene CN. */
    static String nombreComun(X509Certificate certificado) {
        String sujeto = certificado.getSubjectX500Principal().getName();
        try {
            return new LdapName(sujeto).getRdns().stream().filter(rdn -> rdn.getType().equalsIgnoreCase("CN"))
                    .map(Rdn::getValue).map(Object::toString).findFirst().orElse(sujeto);
        } catch (InvalidNameException e) {
            return sujeto;
        }
    }

    /**
     * El {@code serialNumber} del sujeto (OID 2.5.4.5), leído como atributo y no buscando el texto: en los certificados de
     * ARCA es «CUIT » más los 11 dígitos. El JDK no lo nombra (lo da como OID con el valor en hexa), así que se lee con
     * el parser ASN.1 de BouncyCastle, sin registrarlo como proveedor.
     */
    static Optional<String> numeroDeSerieDelSujeto(X509Certificate certificado) {
        RDN[] rdns = X500Name.getInstance(certificado.getSubjectX500Principal().getEncoded()).getRDNs(BCStyle.SERIALNUMBER);
        if (rdns.length != 1 || rdns[0].isMultiValued()) {
            return Optional.empty();
        }
        return Optional.of(IETFUtils.valueToString(rdns[0].getFirst().getValue()));
    }

    /** El SHA-256 de la clave pública ({@code SubjectPublicKeyInfo} en DER), en hexa en mayúsculas y sin separadores. */
    static String huella(PublicKey clave) {
        try {
            return HEXA.formatHex(MessageDigest.getInstance("SHA-256").digest(clave.getEncoded()));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("La JVM no tiene SHA-256.", e);
        }
    }

    /** La huella como se muestra: de a dos dígitos con dos puntos («AB:CD:…»). */
    static String huellaParaMostrar(String huella) {
        return HEXA_CON_DOS_PUNTOS.formatHex(HEXA.parseHex(huella));
    }

    /** DER a PEM, con líneas de 64 caracteres y fin de línea LF. */
    static String pem(String etiqueta, byte[] der) {
        return "-----BEGIN " + etiqueta + "-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(der)
                + "\n-----END " + etiqueta + "-----\n";
    }
}
