package com.ferreteria.auth.totp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * El secreto del código de la app se guarda cifrado (totp-super-admin, S5e y S7): AES-256-GCM con
 * {@code TOTP_CLAVE_MAESTRA}, atado al id del usuario, con un nonce nuevo en cada cifrado.
 */
class CifradorTotpTest {

    private static final byte[] BYTES_DE_LA_CLAVE = "clave-maestra-del-totp-de-prueba".getBytes(StandardCharsets.US_ASCII);
    private static final String CLAVE = Base64.getEncoder().encodeToString(BYTES_DE_LA_CLAVE);
    private static final String OTRA_CLAVE = Base64.getEncoder()
            .encodeToString("otra-clave-maestra-totp-distinta".getBytes(StandardCharsets.US_ASCII));
    private static final byte[] SECRETO = "veinte-bytes-secreto".getBytes(StandardCharsets.US_ASCII);

    private final CifradorTotp cifrador = new CifradorTotp(CLAVE);

    @Test
    @DisplayName("S5e: AES-256-GCM con la clave maestra y el id del usuario como AAD; cada cifrado es distinto y no lleva el secreto")
    void cifraConAes256Gcm() throws Exception {
        byte[] guardado = cifrador.cifrar(7L, SECRETO);

        assertThat(cifrador.descifrar(7L, guardado)).isEqualTo(SECRETO);
        assertThat(guardado).hasSize(1 + 12 + SECRETO.length + 16);
        assertThat(guardado[0]).isEqualTo((byte) 1);
        assertThat(contiene(guardado, SECRETO)).isFalse();
        assertThat(cifrador.cifrar(7L, SECRETO)).isNotEqualTo(guardado);
        // El formato documentado en CifradorTotp, leído con el JDK: versión, nonce de 12 bytes y el cifrado con su tag.
        Cipher aes = Cipher.getInstance("AES/GCM/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(BYTES_DE_LA_CLAVE, "AES"),
                new GCMParameterSpec(128, Arrays.copyOfRange(guardado, 1, 13)));
        aes.updateAAD(ByteBuffer.allocate(1 + 6).put((byte) 1).put("totp|7".getBytes(StandardCharsets.UTF_8)).array());
        assertThat(aes.doFinal(guardado, 13, guardado.length - 13)).isEqualTo(SECRETO);
    }

    @Test
    @DisplayName("S5e: con el id de otro usuario, con otra clave maestra o tocado, no se descifra, y el error no lleva el dato")
    void atadoAlUsuarioYALaClave() {
        byte[] guardado = cifrador.cifrar(7L, SECRETO);
        byte[] tocado = guardado.clone();
        tocado[tocado.length - 1] ^= 1;

        assertThatThrownBy(() -> cifrador.descifrar(8L, guardado)).isInstanceOf(IllegalStateException.class)
                .hasMessageNotContaining(Base64.getEncoder().encodeToString(guardado));
        assertThatThrownBy(() -> new CifradorTotp(OTRA_CLAVE).descifrar(7L, guardado))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cifrador.descifrar(7L, tocado)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> cifrador.descifrar(7L, new byte[3])).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("S7: sin TOTP_CLAVE_MAESTRA, con algo que no es Base64 o con otro largo no arranca, y el mensaje no muestra el valor")
    void claveMaestraInvalida() {
        String corta = Base64.getEncoder().encodeToString(new byte[16]);

        assertThatThrownBy(() -> new CifradorTotp(null)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("TOTP_CLAVE_MAESTRA").hasMessageContaining("No está definida.");
        assertThatThrownBy(() -> new CifradorTotp("   ")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No está definida.");
        assertThatThrownBy(() -> new CifradorTotp("no-es-base64!!")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No es Base64.").hasMessageNotContaining("no-es-base64!!");
        assertThatThrownBy(() -> new CifradorTotp(corta)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Tiene 16 bytes.").hasMessageNotContaining(corta);
        assertThat(cifrador.toString()).doesNotContain(CLAVE);
    }

    private static boolean contiene(byte[] donde, byte[] que) {
        for (int i = 0; i + que.length <= donde.length; i++) {
            if (Arrays.equals(Arrays.copyOfRange(donde, i, i + que.length), que)) {
                return true;
            }
        }
        return false;
    }
}
