package com.ferreteria.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Hallazgo alto 2 de la revision del 05/11: un numero del pedido con un exponente gigante no pasa. */
class NumeroDelPedidoTest {

    @ParameterizedTest
    @ValueSource(strings = {"0", "12.34", "-99.99", "1570.00", "0.0001", "1E+100", "1E-100", "123456789012.12"})
    void unNumeroConExponenteRazonableSePasaTalCual(String texto) {
        BigDecimal numero = new BigDecimal(texto);

        assertThat(NumeroDelPedido.validado(numero)).isSameAs(numero);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1E+101", "1E-101", "1E+1000000000", "1E-1000000000", "-5E+200000000"})
    void unNumeroConMasDeCienPosicionesDeExponenteSeRechaza(String texto) {
        BigDecimal numero = new BigDecimal(texto);

        assertThatThrownBy(() -> NumeroDelPedido.validado(numero))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(NumeroDelPedido.FUERA_DE_RANGO);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1E+1000000000", "1E-1000000000"})
    void elParametroDeLaUrlConExponenteGiganteSeRechaza(String texto) {
        assertThatThrownBy(() -> new NumeroDelPedido.DesdeTexto().setAsText(texto))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage(NumeroDelPedido.FUERA_DE_RANGO);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1570", " 1570.50 ", "-3"})
    void elParametroDeLaUrlNormalSeLeeComoAntes(String texto) {
        NumeroDelPedido.DesdeTexto editor = new NumeroDelPedido.DesdeTexto();
        editor.setAsText(texto);

        assertThat((BigDecimal) editor.getValue()).isEqualByComparingTo(texto.strip());
    }

    @Test
    void elParametroDeLaUrlVacioEsNullComoAntes() {
        NumeroDelPedido.DesdeTexto editor = new NumeroDelPedido.DesdeTexto();
        editor.setAsText("  ");

        assertThat(editor.getValue()).isNull();
    }
}
