package com.ferreteria.auth.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ferreteria.common.exception.DatosInvalidosException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/** La contrasena va de 8 a 72 caracteres, y no mas de 72 bytes UTF-8 (el limite de bcrypt). */
class PoliticaDeContrasenaTest {

    @Test
    @DisplayName("los limites son 8 y 72")
    void limites() {
        assertThat(PoliticaDeContrasena.MIN).isEqualTo(8);
        assertThat(PoliticaDeContrasena.MAX).isEqualTo(72);
    }

    @Test
    @DisplayName("7 caracteres falla en el campo indicado")
    void sieteFalla() {
        assertThatThrownBy(() -> PoliticaDeContrasena.validar("password", "a".repeat(7)))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e -> {
                    assertThat(e.errors()).containsOnlyKeys("password");
                    assertThat(e.errors().get("password")).isEqualTo("La contraseña debe tener entre 8 y 72 caracteres.");
                });
    }

    @Test
    @DisplayName("8 espacios en blanco falla en el campo indicado con el mensaje de solo espacios")
    void soloEspaciosFalla() {
        for (String blanca : new String[] {"        ", "\t".repeat(8), " ".repeat(72)}) {
            assertThatThrownBy(() -> PoliticaDeContrasena.validar("password", blanca))
                    .isInstanceOfSatisfying(DatosInvalidosException.class, e -> {
                        assertThat(e.errors()).containsOnlyKeys("password");
                        assertThat(e.errors().get("password")).isEqualTo("La contraseña no puede ser solo espacios.");
                    });
        }
    }

    @Test
    @DisplayName("una contraseña con espacios en el medio o en los bordes, pero con otros caracteres, pasa")
    void conEspaciosYLetrasPasa() {
        assertThatCode(() -> PoliticaDeContrasena.validar("password", "  abcd  ")).doesNotThrowAnyException();
        assertThatCode(() -> PoliticaDeContrasena.validar("password", "mi clave 1")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("8 y 72 caracteres pasan")
    void ochoYSetentaYDosPasan() {
        assertThatCode(() -> PoliticaDeContrasena.validar("password", "a".repeat(8))).doesNotThrowAnyException();
        assertThatCode(() -> PoliticaDeContrasena.validar("password", "a".repeat(72))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("73 caracteres falla")
    void setentaYTresFalla() {
        assertThatThrownBy(() -> PoliticaDeContrasena.validar("passwordNueva", "a".repeat(73)))
                .isInstanceOfSatisfying(DatosInvalidosException.class,
                        e -> assertThat(e.errors()).containsOnlyKeys("passwordNueva"));
    }

    @Test
    @DisplayName("37 letras ñ son 74 bytes: falla con el mensaje de bytes")
    void unaEnieCuentaDoble() {
        assertThatThrownBy(() -> PoliticaDeContrasena.validar("password", "ñ".repeat(37)))
                .isInstanceOfSatisfying(DatosInvalidosException.class, e ->
                        assertThat(e.errors().get("password")).contains("la ñ y las letras con tilde cuentan doble"));
    }

    @Test
    @DisplayName("36 letras ñ son 72 bytes: pasan")
    void treintaYSeisEniesPasan() {
        assertThatCode(() -> PoliticaDeContrasena.validar("password", "ñ".repeat(36))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("una contrasena nula falla")
    void nulaFalla() {
        assertThatThrownBy(() -> PoliticaDeContrasena.validar("password", null))
                .isInstanceOf(DatosInvalidosException.class);
    }

    @Test
    @DisplayName("riesgo AD-14: bcrypt rechaza 73 bytes en encode, por eso la politica corta antes")
    void bcryptRechazaMasDeSetentaYDosBytes() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

        assertThatThrownBy(() -> encoder.encode("a".repeat(73))).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> encoder.encode("a".repeat(72))).doesNotThrowAnyException();
    }
}
