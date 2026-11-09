package com.ferreteria.auth.totp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * El código de la app del super admin (totp-super-admin, S3): RFC 6238 con HMAC-SHA1, 6 dígitos y pasos de 30 s, el
 * mismo que calcula Google Authenticator. Los vectores son los SHA-1 del Apéndice B de la RFC 6238 (semilla
 * "12345678901234567890", 8 dígitos): con 6 dígitos, el código son sus últimos 6.
 */
class TotpTest {

    private static final byte[] SEMILLA = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @ParameterizedTest(name = "T = {0} s da {1}")
    @CsvSource({
            "59, 94287082",
            "1111111109, 07081804",
            "1111111111, 14050471",
            "1234567890, 89005924",
            "2000000000, 69279037",
            "20000000000, 65353130"})
    @DisplayName("S3: los vectores SHA-1 del Apéndice B de la RFC 6238 dan sus últimos 6 dígitos")
    void vectoresDeLaRfc6238(long segundos, String ochoDigitos) {
        long paso = Totp.paso(Instant.ofEpochSecond(segundos));

        assertThat(Totp.codigo(SEMILLA, paso)).isEqualTo(ochoDigitos.substring(2));
    }

    @Test
    @DisplayName("S3: se acepta el código del paso actual, del anterior y del siguiente; dos pasos más allá o uno inventado, no")
    void ventanaDeTresPasos() {
        long ahora = Totp.paso(Instant.ofEpochSecond(1111111111));

        assertThat(Totp.pasoDelCodigo(SEMILLA, Totp.codigo(SEMILLA, ahora), ahora)).hasValue(ahora);
        assertThat(Totp.pasoDelCodigo(SEMILLA, Totp.codigo(SEMILLA, ahora - 1), ahora)).hasValue(ahora - 1);
        assertThat(Totp.pasoDelCodigo(SEMILLA, Totp.codigo(SEMILLA, ahora + 1), ahora)).hasValue(ahora + 1);
        assertThat(Totp.pasoDelCodigo(SEMILLA, Totp.codigo(SEMILLA, ahora - 2), ahora)).isEmpty();
        assertThat(Totp.pasoDelCodigo(SEMILLA, Totp.codigo(SEMILLA, ahora + 2), ahora)).isEmpty();
        assertThat(Totp.pasoDelCodigo(SEMILLA, "000000", ahora)).isEmpty();
    }
}
