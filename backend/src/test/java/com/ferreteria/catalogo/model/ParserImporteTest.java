package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Formato argentino de importes: dominio puro, sin Spring. */
class ParserImporteTest {

    private static Celda texto(String valor) {
        return new Celda(valor, null);
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
        "1.234,56|1234.56",
        "1234,56|1234.56",
        "1,234.56|1234.56",
        "$ 1.234|1234",
        "$ 1.234,56|1234.56",
        "12.5|12.5",
        "1.234.567|1234567",
        "1,234,567|1234567",
        "1.234|1234",
        "12,5|12.5",
        "0|0",
        "-5|-5",
        "-1.234,50|-1234.50",
        "  99  |99",
    })
    @DisplayName("interpreta los formatos de texto")
    void formatosDeTexto(String entrada, String esperado) {
        assertThat(ParserImporte.parsear(texto(entrada))).hasValueSatisfying(
            valor -> assertThat(valor).isEqualByComparingTo(esperado));
    }

    @Test
    @DisplayName("no redondea y conserva la escala: 12,3456 sigue siendo 12.3456")
    void sinRedondear() {
        BigDecimal valor = ParserImporte.parsear(texto("12,3456")).orElseThrow();

        assertThat(valor).isEqualTo(new BigDecimal("12.3456"));
        assertThat(valor.scale()).isEqualTo(4);
    }

    @Test
    @DisplayName("ignora espacio no separable (U+00A0) y espacio fino no separable (U+202F)")
    void espaciosEspeciales() {
        assertThat(ParserImporte.parsear(texto(" 1.234,56 "))).contains(new BigDecimal("1234.56"));
        assertThat(ParserImporte.parsear(texto("1 234,56"))).contains(new BigDecimal("1234.56"));
        assertThat(ParserImporte.parsear(texto("$  12,5"))).contains(new BigDecimal("12.5"));
    }

    @ParameterizedTest(name = "\"{0}\" -> vacio")
    @ValueSource(strings = {"", "   ", " ", "$", "abc", "12abc", "1e5", "1.2.3", "1,2,3,4x", "--5", "NaN", "Infinity", "1.234,56,7"})
    @DisplayName("texto en blanco o que no es un numero devuelve vacio")
    void noEsNumero(String entrada) {
        assertThat(ParserImporte.parsear(texto(entrada))).isEmpty();
    }

    @Test
    @DisplayName("la celda vacia devuelve vacio")
    void celdaVacia() {
        assertThat(ParserImporte.parsear(Celda.VACIA)).isEmpty();
    }

    @Test
    @DisplayName("la celda numerica devuelve numero() tal cual, sin pasar por el texto")
    void celdaNumerica() {
        BigDecimal numero = new BigDecimal("1234.56");

        assertThat(ParserImporte.parsear(new Celda("1234.56", numero))).containsSame(numero);
    }

    @Test
    @DisplayName("el cero se devuelve como cero: lo rechaza el planificador, no el parser")
    void cero() {
        assertThat(ParserImporte.parsear(new Celda("0", BigDecimal.ZERO))).hasValueSatisfying(
            valor -> assertThat(valor.signum()).isZero());
        assertThat(ParserImporte.parsear(texto("0,00"))).hasValueSatisfying(
            valor -> assertThat(valor.signum()).isZero());
    }
}
