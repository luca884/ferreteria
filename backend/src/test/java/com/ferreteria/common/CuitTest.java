package com.ferreteria.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * CUIT: 11 digitos, se guarda como 30-12345678-1 y el ultimo es el digito verificador (modulo 11 con los
 * pesos 5,4,3,2,7,6,5,4,3,2). Los mismos casos estan en frontend/src/app/core/formato/cuit.spec.ts.
 */
class CuitTest {

    @ParameterizedTest
    @ValueSource(strings = {"30123456781", "30-12345678-1", " 30-12345678-1 ", "30 12345678 1", "30.12345678.1"})
    @DisplayName("acepta el CUIT con o sin guiones, espacios o puntos y lo devuelve con guiones")
    void normaliza(String escrito) {
        assertThat(Cuit.normalizar(escrito)).isEqualTo("30-12345678-1");
    }

    @Test
    @DisplayName("si el resto da 11 el verificador es 0: 30-87654321-0")
    void verificadorCero() {
        assertThat(Cuit.normalizar("30876543210")).isEqualTo("30-87654321-0");
    }

    @Test
    @DisplayName("una persona: 20-12345678-6")
    void persona() {
        assertThat(Cuit.normalizar("20-12345678-6")).isEqualTo("20-12345678-6");
    }

    @ParameterizedTest
    @ValueSource(strings = {"20-12345678-6", "23-12345678-1", "24-12345678-1", "27-12345678-1", "30-12345678-1", "33-12345678-1", "34-12345678-1"})
    @DisplayName("tienePrefijoValido: 20, 23, 24, 27, 30, 33 y 34 son validos")
    void prefijosValidos(String normalizado) {
        assertThat(Cuit.tienePrefijoValido(normalizado)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"10-12345678-1", "21-12345678-1", "25-12345678-1", "31-12345678-1", "35-12345678-1", "99-12345678-1", "", "3"})
    @DisplayName("tienePrefijoValido: cualquier otro prefijo (o texto corto) no lo es")
    void prefijosInvalidos(String normalizado) {
        assertThat(Cuit.tienePrefijoValido(normalizado)).isFalse();
    }

    @Test
    @DisplayName("tienePrefijoValido con null es falso")
    void prefijoNull() {
        assertThat(Cuit.tienePrefijoValido(null)).isFalse();
    }

    @Test
    @DisplayName("vacio o null es sin CUIT")
    void vacio() {
        assertThat(Cuit.normalizar(null)).isNull();
        assertThat(Cuit.normalizar("   ")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"123", "301234567812", "30-1234567-1", "30-12345678-X"})
    @DisplayName("sin 11 numeros: mensaje de formato")
    void formatoInvalido(String escrito) {
        assertThatThrownBy(() -> Cuit.normalizar(escrito))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El CUIT tiene que tener 11 números, por ejemplo 30-12345678-1.");
    }

    @Test
    @DisplayName("como lo llama el simulador de ARCA (número rellenado a 11 dígitos): el 0 tiene verificador pero no prefijo; un negativo o uno de 12 dígitos no es un CUIT")
    void comoLoLlamaElSimulador() {
        String formato = "El CUIT tiene que tener 11 números, por ejemplo 30-12345678-1.";

        assertThat(Cuit.normalizar(String.format("%011d", 0L))).isEqualTo("00-00000000-0");
        assertThat(Cuit.tienePrefijoValido(Cuit.normalizar(String.format("%011d", 0L)))).isFalse();
        assertThatThrownBy(() -> Cuit.normalizar(String.format("%011d", -5L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(formato);
        assertThatThrownBy(() -> Cuit.normalizar(String.format("%011d", 100_000_000_000L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(formato);
    }

    @ParameterizedTest
    @ValueSource(strings = {"30-12345678-9", "30-87654321-1", "20-00000001-0", "20-00000001-9"})
    @DisplayName("un verificador que no cierra (o un resto 10, que no existe) no es valido")
    void verificadorInvalido(String escrito) {
        assertThatThrownBy(() -> Cuit.normalizar(escrito))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El CUIT no es válido: revisá los números, el último no coincide.");
    }
}
