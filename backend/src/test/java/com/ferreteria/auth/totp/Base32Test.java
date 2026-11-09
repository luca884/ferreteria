package com.ferreteria.auth.totp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * La clave que se carga a mano en Google Authenticator (totp-super-admin, S3): Base32 de la RFC 4648 sin el relleno
 * {@code =}. Los vectores son los de la sección 10 de la RFC.
 */
class Base32Test {

    @ParameterizedTest(name = "\"{0}\" da \"{1}\"")
    @CsvSource({"'', ''", "f, MY", "fo, MZXQ", "foo, MZXW6", "foob, MZXW6YQ", "fooba, MZXW6YTB", "foobar, MZXW6YTBOI"})
    @DisplayName("S3: los vectores de la RFC 4648, sin relleno")
    void vectoresDeLaRfc4648(String claro, String esperado) {
        assertThat(Base32.codificar(claro.getBytes(StandardCharsets.US_ASCII))).isEqualTo(esperado);
    }
}
