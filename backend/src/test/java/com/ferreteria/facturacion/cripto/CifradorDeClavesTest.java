package com.ferreteria.facturacion.cripto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * El cifrado de las claves privadas de ARCA (rama 5, S6 y S7): AES-GCM con la clave maestra, el formato guardado (versión,
 * id de clave, nonce y texto cifrado con su tag), el AAD (propósito, ferretería y huella de la clave pública), la rotación
 * con la clave anterior y la clave maestra validada al construir el cifrador (al arrancar la aplicación).
 */
@ExtendWith(OutputCaptureExtension.class)
class CifradorDeClavesTest {

    private static final String CLAVE = base64("clave-maestra-de-prueba-numero-1");
    private static final String OTRA_CLAVE = base64("clave-maestra-de-prueba-numero-2");
    private static final String HUELLA = "AB".repeat(32);
    private static final ContextoDeCifrado CONTEXTO =
            new ContextoDeCifrado(Proposito.CLAVE_DE_CREDENCIAL, 7L, HUELLA);
    private static final byte[] CLARO = "una clave privada PKCS#8 de mentira".getBytes(StandardCharsets.UTF_8);

    private static String base64(String texto) {
        return Base64.getEncoder().encodeToString(texto.getBytes(StandardCharsets.UTF_8));
    }

    private static CifradorDeClaves cifrador(String clave) {
        return new CifradorDeClaves(clave, "");
    }

    @Test
    @DisplayName("formato: versión 1, id de la clave (8 bytes del SHA-256), nonce de 12 bytes y texto cifrado con tag de 16")
    void formatoGuardado() throws Exception {
        byte[] guardado = cifrador(CLAVE).cifrar(CLARO, CONTEXTO);

        assertThat(guardado[0]).isEqualTo((byte) 1);
        byte[] sha = MessageDigest.getInstance("SHA-256").digest(Base64.getDecoder().decode(CLAVE));
        assertThat(Arrays.copyOfRange(guardado, 1, 9)).isEqualTo(Arrays.copyOf(sha, 8));
        assertThat(guardado).hasSize(1 + 8 + 12 + CLARO.length + 16);
        assertThat(cifrador(CLAVE).descifrar(guardado, CONTEXTO)).isEqualTo(CLARO);
    }

    @Test
    @DisplayName("cada cifrado lleva un nonce aleatorio: el mismo dato cifrado dos veces sale distinto")
    void nonceAleatorio() {
        CifradorDeClaves cifrador = cifrador(CLAVE);

        byte[] uno = cifrador.cifrar(CLARO, CONTEXTO);
        byte[] otro = cifrador.cifrar(CLARO, CONTEXTO);

        assertThat(Arrays.copyOfRange(uno, 9, 21)).isNotEqualTo(Arrays.copyOfRange(otro, 9, 21));
        assertThat(uno).isNotEqualTo(otro);
        assertThat(cifrador.descifrar(otro, CONTEXTO)).isEqualTo(CLARO);
    }

    @Test
    @DisplayName("el AAD ata el dato a su propósito, su ferretería y su huella: con otro cualquiera no se descifra")
    void aadAtaElDato() {
        CifradorDeClaves cifrador = cifrador(CLAVE);
        byte[] guardado = cifrador.cifrar(CLARO, CONTEXTO);

        for (ContextoDeCifrado otro : new ContextoDeCifrado[] {
                new ContextoDeCifrado(Proposito.CLAVE_DE_CREDENCIAL, 8L, HUELLA),
                new ContextoDeCifrado(Proposito.CLAVE_DE_CA_SIMULADOR, 7L, HUELLA),
                new ContextoDeCifrado(Proposito.CLAVE_DE_CREDENCIAL, 7L, "CD".repeat(32))}) {
            assertThatThrownBy(() -> cifrador.descifrar(guardado, otro)).as(otro.toString())
                    .isInstanceOfSatisfying(DatoIlegibleException.class,
                            e -> assertThat(e.motivo()).isEqualTo(DatoIlegibleException.Motivo.NO_SE_DESCIFRA));
        }
    }

    @Test
    @DisplayName("un dato tocado (texto cifrado, tag, nonce o cabecera) no se descifra")
    void datoTocado() {
        CifradorDeClaves cifrador = cifrador(CLAVE);
        byte[] guardado = cifrador.cifrar(CLARO, CONTEXTO);

        for (int posicion : new int[] {12, 25, guardado.length - 1}) {
            byte[] tocado = guardado.clone();
            tocado[posicion] ^= 1;
            assertThatThrownBy(() -> cifrador.descifrar(tocado, CONTEXTO)).as("byte " + posicion)
                    .isInstanceOfSatisfying(DatoIlegibleException.class,
                            e -> assertThat(e.motivo()).isEqualTo(DatoIlegibleException.Motivo.NO_SE_DESCIFRA));
        }
        assertThatThrownBy(() -> cifrador.descifrar(Arrays.copyOf(guardado, 20), CONTEXTO))
                .isInstanceOfSatisfying(DatoIlegibleException.class,
                        e -> assertThat(e.motivo()).isEqualTo(DatoIlegibleException.Motivo.NO_SE_DESCIFRA));
        byte[] otraVersion = guardado.clone();
        otraVersion[0] = 2;
        assertThatThrownBy(() -> cifrador.descifrar(otraVersion, CONTEXTO))
                .isInstanceOfSatisfying(DatoIlegibleException.class,
                        e -> assertThat(e.motivo()).isEqualTo(DatoIlegibleException.Motivo.VERSION_DESCONOCIDA));
    }

    @Test
    @DisplayName("cada byte tocado de a uno da su motivo (versión, id de clave o «no se descifra») y ningún recorte se descifra")
    void cadaByteTocado() {
        CifradorDeClaves cifrador = cifrador(CLAVE);
        byte[] guardado = cifrador.cifrar(CLARO, CONTEXTO);

        for (int posicion = 0; posicion < guardado.length; posicion++) {
            byte[] tocado = guardado.clone();
            tocado[posicion] ^= 1;
            DatoIlegibleException.Motivo esperado = posicion == 0 ? DatoIlegibleException.Motivo.VERSION_DESCONOCIDA
                    : posicion <= 8 ? DatoIlegibleException.Motivo.CLAVE_DESCONOCIDA
                    : DatoIlegibleException.Motivo.NO_SE_DESCIFRA;
            assertThatThrownBy(() -> cifrador.descifrar(tocado, CONTEXTO)).as("byte " + posicion)
                    .isInstanceOfSatisfying(DatoIlegibleException.class, e -> assertThat(e.motivo()).isEqualTo(esperado));
        }
        for (int largo : new int[] {0, 1, 9, 20, 21, 36, guardado.length - 1}) {
            assertThatThrownBy(() -> cifrador.descifrar(Arrays.copyOf(guardado, largo), CONTEXTO)).as("largo " + largo)
                    .isInstanceOf(DatoIlegibleException.class);
        }
        assertThatThrownBy(() -> cifrador.descifrar(null, CONTEXTO)).isInstanceOf(DatoIlegibleException.class);
    }

    @Test
    @DisplayName("un id de clave desconocido es un error tipado: sin la clave anterior, el dato de la vieja no se lee")
    void idDeClaveDesconocido() {
        byte[] conLaVieja = cifrador(OTRA_CLAVE).cifrar(CLARO, CONTEXTO);

        assertThatThrownBy(() -> cifrador(CLAVE).descifrar(conLaVieja, CONTEXTO))
                .isInstanceOfSatisfying(DatoIlegibleException.class,
                        e -> assertThat(e.motivo()).isEqualTo(DatoIlegibleException.Motivo.CLAVE_DESCONOCIDA));
    }

    @Test
    @DisplayName("rotación: con ARCA_CLAVE_MAESTRA_ANTERIOR se lee lo cifrado con la vieja, y lo nuevo se cifra con la actual")
    void rotacion() {
        byte[] conLaVieja = cifrador(OTRA_CLAVE).cifrar(CLARO, CONTEXTO);
        CifradorDeClaves rotado = new CifradorDeClaves(CLAVE, OTRA_CLAVE);

        assertThat(rotado.descifrar(conLaVieja, CONTEXTO)).isEqualTo(CLARO);
        byte[] nuevo = rotado.cifrar(CLARO, CONTEXTO);
        assertThat(cifrador(CLAVE).descifrar(nuevo, CONTEXTO)).isEqualTo(CLARO);
        assertThatThrownBy(() -> cifrador(OTRA_CLAVE).descifrar(nuevo, CONTEXTO))
                .isInstanceOf(DatoIlegibleException.class);
    }

    @Test
    @DisplayName("rotación: ponerle a un dato de la vieja el id de clave de la actual no lo vuelve legible")
    void idDeClaveCambiado() {
        CifradorDeClaves rotado = new CifradorDeClaves(CLAVE, OTRA_CLAVE);
        byte[] cambiado = cifrador(OTRA_CLAVE).cifrar(CLARO, CONTEXTO);
        System.arraycopy(rotado.cifrar(CLARO, CONTEXTO), 1, cambiado, 1, 8);

        assertThatThrownBy(() -> rotado.descifrar(cambiado, CONTEXTO))
                .isInstanceOfSatisfying(DatoIlegibleException.class,
                        e -> assertThat(e.motivo()).isEqualTo(DatoIlegibleException.Motivo.NO_SE_DESCIFRA));
    }

    @Test
    @DisplayName("al arrancar, Spring arma el cifrador con las dos propiedades: una clave o una anterior de otro largo frenan "
            + "el arranque sin mostrar el valor")
    void arranqueConLasPropiedades() {
        ApplicationContextRunner contexto = new ApplicationContextRunner().withUserConfiguration(CifradorDeClaves.class);
        String deDieciseisBytes = base64("dieciseis-bytes!");
        byte[] conLaVieja = cifrador(OTRA_CLAVE).cifrar(CLARO, CONTEXTO);

        contexto.withPropertyValues("app.facturacion.clave-maestra=" + CLAVE,
                "app.facturacion.clave-maestra-anterior=" + OTRA_CLAVE).run(c -> {
                    assertThat(c).hasNotFailed();
                    assertThat(c.getBean(CifradorDeClaves.class).descifrar(conLaVieja, CONTEXTO)).isEqualTo(CLARO);
                });
        contexto.withPropertyValues("app.facturacion.clave-maestra=" + deDieciseisBytes).run(c -> assertThat(c)
                .getFailure().rootCause().hasMessageContaining("ARCA_CLAVE_MAESTRA")
                .hasMessageNotContaining(deDieciseisBytes));
        contexto.withPropertyValues("app.facturacion.clave-maestra=" + CLAVE,
                "app.facturacion.clave-maestra-anterior=" + deDieciseisBytes).run(c -> assertThat(c)
                .getFailure().rootCause().hasMessageContaining("ARCA_CLAVE_MAESTRA_ANTERIOR")
                .hasMessageNotContaining(deDieciseisBytes));
    }

    @ParameterizedTest(name = "[{index}] «{0}»")
    // 8 bytes, 33 bytes y 31 bytes en Base64.
    @ValueSource(strings = {"", "   ", "no es base64 !!", "c29sby1kZXY=", "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXphYmNkZWZn",
        "YWJjZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXphYmNkZQ=="})
    @DisplayName("la clave maestra se valida al construir: vacía, fuera de Base64 o de otro largo que 32 bytes, no arranca y "
        + "el error no muestra la clave")
    void claveMaestraInvalida(String invalida) {
        assertThatThrownBy(() -> new CifradorDeClaves(invalida, ""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ARCA_CLAVE_MAESTRA")
                .hasMessageContaining("32 bytes en Base64")
                .satisfies(e -> {
                    if (!invalida.isBlank()) {
                        assertThat(e.getMessage()).doesNotContain(invalida.strip());
                    }
                });
        assertThatThrownBy(() -> new CifradorDeClaves(null, "")).isInstanceOf(IllegalStateException.class);
        if (!invalida.isBlank()) {
            assertThatThrownBy(() -> new CifradorDeClaves(CLAVE, invalida))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("ARCA_CLAVE_MAESTRA_ANTERIOR")
                    .satisfies(e -> assertThat(e.getMessage()).doesNotContain(invalida.strip()));
        }
    }

    @Test
    @DisplayName("toString enmascarado: ni el cifrador ni el contexto muestran la clave maestra")
    void toStringEnmascarado() {
        CifradorDeClaves cifrador = new CifradorDeClaves(CLAVE, OTRA_CLAVE);

        assertThat(cifrador.toString()).doesNotContain(CLAVE).doesNotContain(OTRA_CLAVE).contains("***");
    }

    @Test
    @DisplayName("un dato que no se descifra se loguea sin contenido: ni el dato cifrado ni la clave")
    void elErrorSeLogueaSinContenido(CapturedOutput salida) {
        byte[] guardado = cifrador(OTRA_CLAVE).cifrar(CLARO, CONTEXTO);

        assertThatThrownBy(() -> cifrador(CLAVE).descifrar(guardado, CONTEXTO))
                .isInstanceOf(DatoIlegibleException.class)
                .satisfies(e -> assertThat(e.getMessage()).doesNotContain(Base64.getEncoder().encodeToString(guardado)));

        assertThat(salida.getAll()).contains("CLAVE_DESCONOCIDA").contains("ferretería 7")
                .doesNotContain(Base64.getEncoder().encodeToString(guardado))
                .doesNotContain(CLAVE).doesNotContain(OTRA_CLAVE)
                .doesNotContain(new String(CLARO, StandardCharsets.UTF_8));
    }
}
