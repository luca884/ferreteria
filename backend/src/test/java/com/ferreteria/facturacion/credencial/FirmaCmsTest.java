package com.ferreteria.facturacion.credencial;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ferreteria.facturacion.cripto.CifradorDeClaves;
import com.ferreteria.facturacion.cripto.ContextoDeCifrado;
import com.ferreteria.facturacion.cripto.Proposito;
import com.ferreteria.facturacion.model.Ambiente;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.Optional;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cms.CMSSignedData;
import org.bouncycastle.cms.SignerInformation;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * C3 y S2, el CMS de {@code loginCms}: {@link FirmaCms} firma con la credencial ACTIVA del ambiente pedido (SignedData
 * attached, SHA256withRSA, con el certificado incluido, en DER pasado a Base64 sin cabeceras) y devuelve la huella y la CUIT
 * de esa credencial; sin credencial activa de ese ambiente, o con la clave ilegible, no firma. {@link FirmaCms#verificar}
 * (la que usa el simulador en el borde, T6) devuelve el contenido y el firmante solo si la firma verifica y el firmante lo
 * emitió la CA dada. Sin base: la credencial se arma en memoria y el repositorio es un mock.
 */
class FirmaCmsTest {

    private static final long FERRETERIA = 7L;
    private static final long CUIT = 30999999995L;
    private static final Instant AHORA = Instant.parse("2026-10-29T13:15:30Z");
    private static final AutoridadDePrueba CA = AutoridadDePrueba.nueva("Computadores Test");
    private static final byte[] TRA = "<loginTicketRequest version=\"1.0\">…</loginTicketRequest>"
            .getBytes(StandardCharsets.UTF_8);

    private final CifradorDeClaves cifrador = new CifradorDeClaves(
            Base64.getEncoder().encodeToString("clave-maestra-de-prueba-firmacms".getBytes(StandardCharsets.UTF_8)), "");
    private final CredencialArcaRepository credenciales = mock(CredencialArcaRepository.class);
    private final FirmaCms firma = new FirmaCms(credenciales, cifrador);

    private X509Certificate certificado;
    private CredencialArca activa;

    @BeforeEach
    void credencialActivaDeHomologacion() throws Exception {
        KeyPair claves = AutoridadDePrueba.rsa(2048);
        String huella = Certificados.huella(claves.getPublic());
        byte[] cifrada = cifrador.cifrar(claves.getPrivate().getEncoded(),
                new ContextoDeCifrado(Proposito.CLAVE_DE_CREDENCIAL, FERRETERIA, huella));
        certificado = CA.emitir(claves.getPublic(), AutoridadDePrueba.sujeto("Ferretería", String.valueOf(CUIT)),
                AHORA.minus(Duration.ofDays(1)), AHORA.plus(Duration.ofDays(730)));
        activa = CredencialArca.pendiente(FERRETERIA, CUIT, huella, cifrada, "CSR", AHORA);
        activa.activar(Certificados.pem("CERTIFICATE", certificado.getEncoded()), CUIT, Ambiente.HOMOLOGACION,
                CA.nombre(), certificado.getNotBefore().toInstant(), certificado.getNotAfter().toInstant(), null, AHORA);
        when(credenciales.activaScoped()).thenReturn(Optional.of(activa));
    }

    @Test
    @DisplayName("S2: el CMS se parsea, es SignedData attached con SHA256withRSA y el certificado incluido, la firma verifica "
            + "con el certificado y el contenido es el TRA; vuelve con la huella y la CUIT de la credencial")
    void elCmsVerificaConElCertificadoYTraeElTra() throws Exception {
        FirmaCms.Firmado firmado = firma.firmar(Ambiente.HOMOLOGACION, TRA);

        assertThat(firmado.huella()).isEqualTo(activa.getHuella());
        assertThat(firmado.cuit()).isEqualTo(CUIT);
        assertThat(firmado.cmsBase64()).doesNotContain("-----").doesNotContain("\n").doesNotContain("\r");
        CMSSignedData cms = new CMSSignedData(Base64.getDecoder().decode(firmado.cmsBase64()));
        assertThat((byte[]) cms.getSignedContent().getContent()).isEqualTo(TRA);
        SignerInformation firmante = cms.getSignerInfos().getSigners().iterator().next();
        // SHA-256 (2.16.840.1.101.3.4.2.1) con RSA: sha256WithRSAEncryption, o rsaEncryption como lo escribe BouncyCastle.
        assertThat(firmante.getDigestAlgOID()).isEqualTo("2.16.840.1.101.3.4.2.1");
        assertThat(firmante.getEncryptionAlgOID()).isIn("1.2.840.113549.1.1.11", "1.2.840.113549.1.1.1");
        @SuppressWarnings("unchecked")
        Collection<X509CertificateHolder> incluidos = cms.getCertificates().getMatches(firmante.getSID());
        X509Certificate incluido = new JcaX509CertificateConverter().getCertificate(incluidos.iterator().next());
        assertThat(incluido).isEqualTo(certificado);
        assertThat(firmante.verify(new JcaSimpleSignerInfoVerifierBuilder().build(certificado))).isTrue();
        assertThat(firmado.toString()).doesNotContain(firmado.cmsBase64());
    }

    @Test
    @DisplayName("verificar devuelve el TRA y el firmante si la firma verifica y el firmante lo emitió esa CA; si no, el "
            + "motivo con su código de WSAA")
    void verificarConLaCaDelFirmante() throws Exception {
        String cms = firma.firmar(Ambiente.HOMOLOGACION, TRA).cmsBase64();

        FirmaCms.Verificado verificado = FirmaCms.verificar(cms, pem(CA.certificado()));

        assertThat(verificado.contenido()).isEqualTo(TRA);
        assertThat(verificado.firmante()).isEqualTo(certificado);
        assertThat(motivo(cms, pem(AutoridadDePrueba.nueva("Otra CA").certificado())))
                .isEqualTo(FirmaCms.CmsInvalidoException.Motivo.OTRA_CA);
        assertThat(FirmaCms.CmsInvalidoException.Motivo.OTRA_CA.codigoWsaa()).isEqualTo("cms.cert.untrusted");
        assertThat(motivo("esto no es Base64 %%%", pem(CA.certificado())))
                .isEqualTo(FirmaCms.CmsInvalidoException.Motivo.NO_ES_BASE64);
        assertThat(motivo(Base64.getEncoder().encodeToString(TRA), pem(CA.certificado())))
                .isEqualTo(FirmaCms.CmsInvalidoException.Motivo.NO_ES_CMS);
    }

    @Test
    @DisplayName("sin credencial activa, con la activa de otro ambiente o con su clave ilegible, no firma")
    void sinCredencialDelAmbienteNoFirma() {
        assertThatThrownBy(() -> firma.firmar(Ambiente.PRODUCCION, TRA))
                .isInstanceOf(FirmaCms.NoSePudoFirmarException.class).hasMessageContaining("PRODUCCION");

        when(credenciales.activaScoped()).thenReturn(Optional.empty());
        assertThatThrownBy(() -> firma.firmar(Ambiente.HOMOLOGACION, TRA))
                .isInstanceOf(FirmaCms.NoSePudoFirmarException.class);
        assertThat(firma.firmante(Ambiente.HOMOLOGACION)).isEmpty();

        when(credenciales.activaScoped()).thenReturn(Optional.of(activa));
        FirmaCms conOtraClaveMaestra = new FirmaCms(credenciales, new CifradorDeClaves(
                Base64.getEncoder().encodeToString("otra-clave-maestra-de-pruebas-01".getBytes(StandardCharsets.UTF_8)),
                ""));
        assertThatThrownBy(() -> conOtraClaveMaestra.firmar(Ambiente.HOMOLOGACION, TRA))
                .isInstanceOf(FirmaCms.NoSePudoFirmarException.class).hasMessageContaining("no se puede leer");
        assertThat(conOtraClaveMaestra.firmante(Ambiente.HOMOLOGACION))
                .hasValue(new FirmaCms.Firmante(activa.getHuella(), CUIT));
    }

    private static FirmaCms.CmsInvalidoException.Motivo motivo(String cms, String ca) {
        try {
            FirmaCms.verificar(cms, ca);
        } catch (FirmaCms.CmsInvalidoException e) {
            return e.motivo();
        }
        throw new AssertionError("verificó un CMS que no tenía que verificar");
    }

    private static String pem(X509Certificate certificado) throws Exception {
        return Certificados.pem("CERTIFICATE", certificado.getEncoded());
    }
}
