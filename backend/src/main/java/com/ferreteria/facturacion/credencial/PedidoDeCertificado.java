package com.ferreteria.facturacion.credencial;

import java.io.IOException;
import java.io.StringReader;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.X500NameBuilder;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.PKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequest;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;

/**
 * El pedido de certificado de ARCA (CU-01 paso 3, rama 5, S3): una clave RSA 2048 nueva, del {@code KeyPairGenerator} del
 * JDK, y su CSR PKCS#10 firmado con SHA256withRSA. El JDK no arma un PKCS#10: lo arma BouncyCastle, con los proveedores del
 * JDK y sin registrarse como proveedor ({@code Security.addProvider}).
 *
 * <p>El sujeto, en este orden: {@code C=AR, O=<razón social>, CN=Ferreteria SaaS, serialNumber=CUIT <11 dígitos>}, el que
 * pide ARCA para los certificados de los web services. El CN es un nombre fijo del sistema (S15); en el portal de ARCA el
 * Dueño elige el alias.
 */
final class PedidoDeCertificado {

    static final String NOMBRE_DEL_SISTEMA = "Ferreteria SaaS";
    static final int BITS = 2048;
    private static final String FIRMA = "SHA256withRSA";

    /**
     * Un pedido recién generado: el CSR en PEM y su par de claves. La privada se cifra enseguida y no se guarda en ningún
     * otro lado; {@link #toString()} no la muestra.
     */
    record Generado(String pem, PublicKey publica, PrivateKey privada) {

        @Override
        public String toString() {
            return "PedidoDeCertificado.Generado[clave=***]";
        }
    }

    private PedidoDeCertificado() {
    }

    /** Genera la clave y el CSR para esa razón social y ese CUIT (11 dígitos). */
    static Generado generar(String razonSocial, String cuit) {
        try {
            KeyPairGenerator generador = KeyPairGenerator.getInstance("RSA");
            generador.initialize(BITS, new SecureRandom());
            KeyPair claves = generador.generateKeyPair();
            X500Name sujeto = new X500NameBuilder(BCStyle.INSTANCE)
                    .addRDN(BCStyle.C, "AR")
                    .addRDN(BCStyle.O, razonSocial)
                    .addRDN(BCStyle.CN, NOMBRE_DEL_SISTEMA)
                    .addRDN(BCStyle.SERIALNUMBER, "CUIT " + cuit)
                    .build();
            PKCS10CertificationRequest csr = new JcaPKCS10CertificationRequestBuilder(sujeto, claves.getPublic())
                    .build(new JcaContentSignerBuilder(FIRMA).build(claves.getPrivate()));
            return new Generado(Certificados.pem("CERTIFICATE REQUEST", csr.getEncoded()), claves.getPublic(),
                    claves.getPrivate());
        } catch (GeneralSecurityException | OperatorCreationException | IOException e) {
            throw new IllegalStateException("No se pudo generar el pedido de certificado.", e);
        }
    }

    /** El CSR guardado (PEM), para volver a leer su sujeto y su clave pública. */
    static JcaPKCS10CertificationRequest leer(String pem) {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            return new JcaPKCS10CertificationRequest((PKCS10CertificationRequest) parser.readObject());
        } catch (IOException | ClassCastException e) {
            throw new IllegalStateException("El pedido de certificado guardado no se puede leer.", e);
        }
    }
}
