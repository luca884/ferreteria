package com.ferreteria.facturacion.credencial;

import static org.assertj.core.api.Assertions.assertThat;

import com.ferreteria.facturacion.credencial.CasDeArca.CaDeArca;
import com.ferreteria.facturacion.model.Ambiente;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Las CA oficiales de ARCA que vienen con la aplicación (C3, bajadas de ARCA y verificadas con {@code openssl verify} el
 * 2026-10-28): la de producción y la de homologación cargan de los PEM de {@code facturacion/ca}, con su ambiente, su
 * nombre, su vigencia y su huella SHA-256. Los tests de integración las reemplazan por CA generadas en el momento
 * ({@code CasDeArcaDePrueba}): ARCA no firma certificados de prueba.
 */
class CasDeArcaTest {

    private static String huella(CaDeArca ca) throws Exception {
        return HexFormat.ofDelimiter(":").withUpperCase()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(ca.certificado().getEncoded()));
    }

    @Test
    @DisplayName("los PEM oficiales cargan: Computadores (producción) y Computadores Test (homologación), con su huella")
    void lasCaOficialesCargan() throws Exception {
        CasDeArca cas = new CasDeArcaConfig().casDeArca();

        assertThat(cas.todas()).extracting(CaDeArca::ambiente).containsExactlyInAnyOrder(Ambiente.PRODUCCION,
                Ambiente.HOMOLOGACION);
        CaDeArca produccion = cas.de(Ambiente.PRODUCCION);
        CaDeArca homologacion = cas.de(Ambiente.HOMOLOGACION);

        assertThat(produccion.nombre()).isEqualTo("Computadores");
        assertThat(produccion.certificado().getSubjectX500Principal().getName()).isEqualTo("C=AR,O=AFIP,CN=Computadores");
        assertThat(produccion.certificado().getIssuerX500Principal().getName()).isEqualTo("C=AR,O=AFIP,CN=AFIP Root CA 2");
        assertThat(produccion.certificado().getNotBefore().toInstant()).isEqualTo(Instant.parse("2024-01-03T12:34:41Z"));
        assertThat(produccion.certificado().getNotAfter().toInstant()).isEqualTo(Instant.parse("2035-01-03T12:34:41Z"));
        assertThat(huella(produccion)).isEqualTo("92:84:C8:A4:8B:A1:79:1F:AC:74:4A:95:B5:F7:E9:8C:0F:6A:DF:6E:78:6A:CB:84:"
                + "69:91:11:35:F7:35:A5:1D");

        assertThat(homologacion.nombre()).isEqualTo("Computadores Test");
        assertThat(homologacion.certificado().getSubjectX500Principal().getName())
                .isEqualTo("C=AR,O=AFIP,CN=Computadores Test");
        assertThat(homologacion.certificado().getIssuerX500Principal().getName()).isEqualTo("C=AR,O=AFIP,CN=AC Raiz Test");
        assertThat(homologacion.certificado().getNotBefore().toInstant()).isEqualTo(Instant.parse("2022-07-07T12:50:59Z"));
        assertThat(homologacion.certificado().getNotAfter().toInstant()).isEqualTo(Instant.parse("2030-07-07T12:50:59Z"));
        assertThat(huella(homologacion)).isEqualTo("40:FD:CA:A7:BA:F7:66:D3:A6:44:E6:07:59:D4:EA:48:DD:9E:AB:E2:D5:42:EF:"
                + "EB:5E:F0:73:B6:89:57:76:9E");
    }

    @Test
    @DisplayName("una CA no firma a la otra: el ambiente sale de la firma, y una CA oficial no se reconoce como firmante de sí misma")
    void cadaCaSoloSeReconocePorSuFirma() {
        CasDeArca cas = new CasDeArcaConfig().casDeArca();

        // Las dos las firma su raíz, que no está en el conjunto: ninguna verifica con la otra.
        assertThat(cas.laQueFirmo(cas.de(Ambiente.PRODUCCION).certificado())).isEmpty();
        assertThat(cas.laQueFirmo(cas.de(Ambiente.HOMOLOGACION).certificado())).isEmpty();
    }
}
