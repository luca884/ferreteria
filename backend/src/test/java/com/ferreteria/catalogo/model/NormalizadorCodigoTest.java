package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Clave de coincidencia que imita la colacion utf8mb4_0900_ai_ci. */
class NormalizadorCodigoTest {

    @Test
    @DisplayName("pasa a mayusculas")
    void mayusculas() {
        assertThat(NormalizadorCodigo.clave("abc-1")).isEqualTo("ABC-1");
    }

    @Test
    @DisplayName("quita los acentos")
    void acentos() {
        assertThat(NormalizadorCodigo.clave("TÉE-10")).isEqualTo("TEE-10");
        assertThat(NormalizadorCodigo.clave("tuerca ñandú")).isEqualTo("TUERCA NANDU");
    }

    @Test
    @DisplayName("hace trim de espacios comunes y de espacios no separables")
    void trim() {
        assertThat(NormalizadorCodigo.clave(" X-5 ")).isEqualTo("X-5");
        assertThat(NormalizadorCodigo.clave(" X-5 ")).isEqualTo("X-5");
        assertThat(NormalizadorCodigo.clave(" X-5 ")).isEqualTo("X-5");
    }

    @Test
    @DisplayName("un codigo en blanco o null no tiene clave")
    void enBlanco() {
        assertThat(NormalizadorCodigo.clave(null)).isNull();
        assertThat(NormalizadorCodigo.clave("")).isNull();
        assertThat(NormalizadorCodigo.clave("   ")).isNull();
        assertThat(NormalizadorCodigo.clave("   ")).isNull();
    }

    @Test
    @DisplayName("conserva los ceros a la izquierda: 00123 no es 123")
    void cerosALaIzquierda() {
        assertThat(NormalizadorCodigo.clave("00123")).isEqualTo("00123");
        assertThat(NormalizadorCodigo.clave("00123")).isNotEqualTo(NormalizadorCodigo.clave("123"));
    }

    @Test
    @DisplayName("dos escrituras del mismo codigo dan la misma clave")
    void mismaClave() {
        assertThat(NormalizadorCodigo.clave(" abc-1 ")).isEqualTo(NormalizadorCodigo.clave("ABC-1"));
    }
}
