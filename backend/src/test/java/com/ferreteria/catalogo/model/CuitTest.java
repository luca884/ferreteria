package com.ferreteria.catalogo.model;

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

    @ParameterizedTest
    @ValueSource(strings = {"30-12345678-9", "30-87654321-1", "20-00000001-0", "20-00000001-9"})
    @DisplayName("un verificador que no cierra (o un resto 10, que no existe) no es valido")
    void verificadorInvalido(String escrito) {
        assertThatThrownBy(() -> Cuit.normalizar(escrito))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El CUIT no es válido: revisá los números, el último no coincide.");
    }
}
