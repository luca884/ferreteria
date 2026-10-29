package com.ferreteria.facturacion.credencial;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.interfaces.RSAPrivateCrtKey;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S7: todo lo que lleva una clave privada, en claro o cifrada, enmascara su {@code toString}: un log o un mensaje de error
 * que lo imprima no la muestra. Las entidades con el dato cifrado y los records que llevan la clave recién generada.
 */
class SinSecretosEnToStringTest {

    private static final Instant AHORA = Instant.parse("2026-10-28T13:15:30Z");
    private static final String HUELLA = "AB".repeat(32);
    private static final byte[] CIFRADA = HexFormat.of().parseHex("01020304050607080910111213141516171819202122232425");

    @Test
    @DisplayName("la credencial y la CA del simulador no muestran el dato cifrado")
    void entidades() {
        String base64 = Base64.getEncoder().encodeToString(CIFRADA);
        String hexa = HexFormat.of().formatHex(CIFRADA);

        for (Object entidad : new Object[] {
                CredencialArca.pendiente(7L, 30999999995L, HUELLA, CIFRADA, "-----BEGIN CERTIFICATE REQUEST-----", AHORA),
                CaSimulador.nueva(7L, "-----BEGIN CERTIFICATE-----", HUELLA, CIFRADA, AHORA)}) {
            assertThat(entidad.toString()).as(entidad.getClass().getSimpleName()).contains("clave=***")
                    .doesNotContain(base64).doesNotContain(hexa).doesNotContain("[B@");
        }
    }

    @Test
    @DisplayName("el pedido recién generado y la CA nueva del simulador no muestran la clave privada")
    void recordsConLaClave() {
        PedidoDeCertificado.Generado pedido = PedidoDeCertificado.generar("Ferretería de Prueba", "30999999995");
        FirmaDelSimulador.CaNueva ca = FirmaDelSimulador.crearCa(AHORA);

        for (Object conClave : new Object[] {pedido, ca}) {
            RSAPrivateCrtKey privada = (RSAPrivateCrtKey) (conClave instanceof PedidoDeCertificado.Generado g
                    ? g.privada() : ((FirmaDelSimulador.CaNueva) conClave).privada());
            assertThat(conClave.toString()).as(conClave.getClass().getSimpleName()).contains("clave=***")
                    .doesNotContain(privada.getPrivateExponent().toString())
                    .doesNotContain(privada.getPrivateExponent().toString(16))
                    .doesNotContain(Base64.getEncoder().encodeToString(privada.getEncoded()).substring(0, 40));
        }
    }
}
