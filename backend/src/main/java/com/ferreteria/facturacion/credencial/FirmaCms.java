package com.ferreteria.facturacion.credencial;

import com.ferreteria.facturacion.cripto.CifradorDeClaves;
import com.ferreteria.facturacion.cripto.DatoIlegibleException;
import com.ferreteria.facturacion.model.Ambiente;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import org.bouncycastle.asn1.ASN1Encoding;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cms.CMSException;
import org.bouncycastle.cms.CMSProcessableByteArray;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.CMSSignedDataGenerator;
import org.bouncycastle.cms.CMSTypedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.operator.OperatorCreationException;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * El CMS de {@code loginCms} de WSAA (rama 6, C3 y S2): firma el TRA con la credencial ACTIVA de la ferretería del
 * {@code TenantContext}, la del ambiente pedido. SignedData <em>attached</em>, SHA256withRSA y el certificado de la
 * credencial incluido, en DER pasado a Base64 sin cabeceras. El CMS lo arma BouncyCastle (el JDK no lo arma), con los
 * proveedores del JDK y sin registrarse como proveedor; la API de esta clase no tiene tipos de BouncyCastle, así
 * {@code facturacion.arca} la usa sin depender de él.
 *
 * <p>La clave privada se descifra solo para firmar y la copia en claro se borra enseguida ({@code Arrays.fill}), como en
 * {@code CredencialArcaService}. Ni el CMS ni la clave aparecen en un log o un {@code toString}.
 *
 * <p>{@link #verificar} es la otra punta: el simulador en el borde (T6) verifica el CMS que le llega contra la CA del
 * simulador de la ferretería, como hace ARCA con las suyas.
 */
@Component
public class FirmaCms {

    private static final String FIRMA = "SHA256withRSA";

    private final CredencialArcaRepository credenciales;
    private final CifradorDeClaves cifrador;

    public FirmaCms(CredencialArcaRepository credenciales, CifradorDeClaves cifrador) {
        this.credenciales = credenciales;
        this.cifrador = cifrador;
    }

    /**
     * Quién firmaría en ese ambiente: la huella y la CUIT de la credencial activa de la ferretería del request, si es de ese
     * ambiente. No descifra nada (con la clave ilegible también vuelve).
     */
    @Transactional(readOnly = true)
    public Optional<Firmante> firmante(Ambiente ambiente) {
        return activaDe(ambiente).map(c -> new Firmante(c.getHuella(), c.getCuit()));
    }

    /**
     * Firma {@code contenido} (el TRA) con la credencial activa de ese ambiente.
     *
     * @throws NoSePudoFirmarException sin credencial activa de ese ambiente, con su clave ilegible o si el CMS no se pudo
     *     armar
     */
    @Transactional(readOnly = true)
    public Firmado firmar(Ambiente ambiente, byte[] contenido) {
        Objects.requireNonNull(contenido, "el contenido");
        CredencialArca activa = activaDe(ambiente).orElseThrow(() -> new NoSePudoFirmarException(
                "No hay un certificado de ARCA activo de " + ambiente + ": no se pudo firmar el pedido del ticket."));
        byte[] pkcs8 = null;
        try {
            pkcs8 = cifrador.descifrar(activa.getClavePrivada(), activa.contexto());
            PrivateKey clave = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
            X509Certificate certificado = Certificados.leer(activa.getCertificado().getBytes(StandardCharsets.US_ASCII));
            CMSSignedDataGenerator generador = new CMSSignedDataGenerator();
            generador.addSignerInfoGenerator(new JcaSignerInfoGeneratorBuilder(new JcaDigestCalculatorProviderBuilder()
                    .build()).build(new JcaContentSignerBuilder(FIRMA).build(clave), certificado));
            generador.addCertificate(new JcaX509CertificateHolder(certificado));
            CMSSignedData cms = generador.generate(new CMSProcessableByteArray(contenido), true);
            return new Firmado(Base64.getEncoder().encodeToString(cms.getEncoded(ASN1Encoding.DER)), activa.getHuella(),
                    activa.getCuit());
        } catch (DatoIlegibleException e) {
            throw new NoSePudoFirmarException("La clave guardada del certificado de ARCA no se puede leer: no se pudo "
                    + "firmar el pedido del ticket.", e);
        } catch (GeneralSecurityException | OperatorCreationException | CMSException | IOException e) {
            throw new NoSePudoFirmarException("No se pudo armar la firma del pedido del ticket de ARCA.", e);
        } finally {
            if (pkcs8 != null) {
                Arrays.fill(pkcs8, (byte) 0);
            }
        }
    }

    /**
     * Verifica un CMS de {@code loginCms}: que sea Base64 de un SignedData con el contenido adentro, que traiga el
     * certificado de su firmante, que la firma verifique con ese certificado y que ese certificado lo haya emitido la CA de
     * {@code certificadoCaPem}. No mira vigencias: las decide quien llama, con su {@code Clock}.
     *
     * @param cmsBase64 el {@code in0} de {@code loginCms}
     * @param certificadoCaPem la CA en que se confía, en PEM
     * @return el contenido firmado (el TRA) y el certificado del firmante
     * @throws CmsInvalidoException si algo de lo anterior no se cumple, con el motivo
     */
    public static Verificado verificar(String cmsBase64, String certificadoCaPem) {
        X509Certificate ca = certificadoDeLaCa(certificadoCaPem);
        byte[] der;
        try {
            der = Base64.getDecoder().decode(cmsBase64.replaceAll("\\s", ""));
        } catch (IllegalArgumentException e) {
            throw new CmsInvalidoException(CmsInvalidoException.Motivo.NO_ES_BASE64);
        }
        CMSSignedData cms;
        try {
            cms = new CMSSignedData(der);
        } catch (CMSException | RuntimeException e) {
            throw new CmsInvalidoException(CmsInvalidoException.Motivo.NO_ES_CMS);
        }
        CMSTypedData contenido = cms.getSignedContent();
        if (contenido == null || cms.getSignerInfos().size() != 1) {
            throw new CmsInvalidoException(CmsInvalidoException.Motivo.NO_ES_CMS);
        }
        SignerInformation firmante = cms.getSignerInfos().getSigners().iterator().next();
        @SuppressWarnings("unchecked")
        Collection<X509CertificateHolder> suyos = cms.getCertificates().getMatches(firmante.getSID());
        if (suyos.isEmpty()) {
            throw new CmsInvalidoException(CmsInvalidoException.Motivo.SIN_CERTIFICADO);
        }
        X509CertificateHolder suyo = suyos.iterator().next();
        try {
            if (!firmante.verify(new JcaSimpleSignerInfoVerifierBuilder().build(suyo))) {
                throw new CmsInvalidoException(CmsInvalidoException.Motivo.FIRMA_INVALIDA);
            }
        } catch (CMSException | OperatorCreationException | CertificateException e) {
            throw new CmsInvalidoException(CmsInvalidoException.Motivo.FIRMA_INVALIDA);
        }
        X509Certificate certificado;
        try {
            certificado = new JcaX509CertificateConverter().getCertificate(suyo);
            certificado.verify(ca.getPublicKey());
        } catch (GeneralSecurityException e) {
            throw new CmsInvalidoException(CmsInvalidoException.Motivo.OTRA_CA);
        }
        return new Verificado((byte[]) contenido.getContent(), certificado);
    }

    private Optional<CredencialArca> activaDe(Ambiente ambiente) {
        Objects.requireNonNull(ambiente, "el ambiente");
        return credenciales.activaScoped().filter(c -> c.getAmbiente() == ambiente);
    }

    private static X509Certificate certificadoDeLaCa(String pem) {
        try {
            return Certificados.leer(pem.getBytes(StandardCharsets.US_ASCII));
        } catch (CertificateException e) {
            throw new IllegalArgumentException("El certificado de la CA no se puede leer.", e);
        }
    }

    /**
     * La credencial que firmaría: la huella de su clave pública (la del AAD del TA guardado) y la CUIT de su certificado.
     *
     * @param huella el SHA-256 de la clave pública, en hexa en mayúsculas
     * @param cuit la CUIT del {@code serialNumber} del certificado, 11 dígitos
     */
    public record Firmante(String huella, long cuit) {
    }

    /**
     * El CMS firmado y con qué credencial. {@link #toString()} no muestra el CMS.
     *
     * @param cmsBase64 el SignedData en DER, en Base64 sin cabeceras ni saltos de línea: el {@code in0} de {@code loginCms}
     * @param huella la huella de la credencial que firmó
     * @param cuit la CUIT de su certificado
     */
    public record Firmado(String cmsBase64, String huella, long cuit) {

        @Override
        public String toString() {
            return "FirmaCms.Firmado[cms=***, huella=" + huella + ", cuit=" + cuit + "]";
        }
    }

    /**
     * Un CMS que verificó: lo que se firmó y quién.
     *
     * @param contenido el contenido firmado (el TRA)
     * @param firmante el certificado del firmante, emitido por la CA de la verificación
     */
    public record Verificado(byte[] contenido, X509Certificate firmante) {
    }

    /** No hubo con qué firmar: quien llama lo trata como «no se pudo firmar» (el pedido a ARCA no sale). */
    public static class NoSePudoFirmarException extends RuntimeException {

        public NoSePudoFirmarException(String mensaje) {
            super(mensaje);
        }

        public NoSePudoFirmarException(String mensaje, Throwable causa) {
            super(mensaje, causa);
        }
    }

    /** Un CMS que no verifica, con el motivo y el código con que WSAA lo rechaza. El mensaje no lleva el CMS. */
    public static class CmsInvalidoException extends RuntimeException {

        /** Por qué no verificó, con el código de WSAA de la especificación técnica 1.2.2. */
        public enum Motivo {
            NO_ES_BASE64("cms.bad.base64"),
            NO_ES_CMS("cms.bad"),
            SIN_CERTIFICADO("cms.cert.notFound"),
            FIRMA_INVALIDA("cms.sign.invalid"),
            OTRA_CA("cms.cert.untrusted");

            private final String codigoWsaa;

            Motivo(String codigoWsaa) {
                this.codigoWsaa = codigoWsaa;
            }

            /** El {@code faultcode} con que WSAA rechaza un CMS así. */
            public String codigoWsaa() {
                return codigoWsaa;
            }
        }

        private final Motivo motivo;

        public CmsInvalidoException(Motivo motivo) {
            super("El CMS no verifica: " + motivo + " (" + motivo.codigoWsaa() + ").");
            this.motivo = motivo;
        }

        public Motivo motivo() {
            return motivo;
        }
    }
}
