package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Porcentajes de la lista (IVA, descuentos y recargos): con signo, con coma o punto y con el simbolo %. */
class ParserPorcentajeTest {

    private static final char MENOS_TIPOGRAFICO = (char) 0x2212;
    private static final char RAYA_CORTA = (char) 0x2013;

    private static Celda texto(String valor) {
        return new Celda(valor, null);
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
        "-5      | -5",
        "+3      | 3",
        "3,5     | 3.5",
        "-5 %    | -5",
        "-5%     | -5",
        "10.5    | 10.5",
        "10,5    | 10.5",
        "21      | 21",
        "21,00   | 21.00",
        "21 %    | 21",
        "  0     | 0",
        "1000    | 1000",
        "-99,99  | -99.99",
    })
    @DisplayName("acepta los formatos habituales de una celda de texto")
    void textos(String entrada, String esperado) {
        ParserPorcentaje.Resultado resultado = ParserPorcentaje.parsear(texto(entrada));

        assertThat(resultado.esValor()).isTrue();
        assertThat(resultado.valor()).isEqualByComparingTo(esperado);
    }

    @Test
    @DisplayName("acepta el menos tipografico (U+2212) y la raya corta (U+2013) como signo")
    void menosTipografico() {
        assertThat(ParserPorcentaje.parsear(texto(MENOS_TIPOGRAFICO + "5")).valor()).isEqualByComparingTo("-5");
        assertThat(ParserPorcentaje.parsear(texto(RAYA_CORTA + "3,5 %")).valor()).isEqualByComparingTo("-3.5");
    }

    @Test
    @DisplayName("3,5 es exacto: sin punto flotante")
    void exacto() {
        BigDecimal valor = ParserPorcentaje.parsear(texto("3,5")).valor();

        assertThat(valor).isEqualTo(new BigDecimal("3.5"));
        assertThat(valor.scale()).isEqualTo(1);
    }

    @Test
    @DisplayName("un texto que no es numero es invalido, no vacio")
    void invalido() {
        ParserPorcentaje.Resultado resultado = ParserPorcentaje.parsear(texto("abc"));

        assertThat(resultado.esInvalido()).isTrue();
        assertThat(resultado.esVacio()).isFalse();
        assertThat(resultado.esValor()).isFalse();
        assertThat(ParserPorcentaje.parsear(texto("5 por ciento")).esInvalido()).isTrue();
        assertThat(ParserPorcentaje.parsear(texto("%")).esInvalido()).isTrue();
    }

    @Test
    @DisplayName("una celda en blanco es vacia, no invalida")
    void vacio() {
        assertThat(ParserPorcentaje.parsear(Celda.VACIA).esVacio()).isTrue();
        assertThat(ParserPorcentaje.parsear(texto("   ")).esVacio()).isTrue();
        assertThat(ParserPorcentaje.parsear(Celda.VACIA).esInvalido()).isFalse();
        assertThat(ParserPorcentaje.parsear(null).esVacio()).isTrue();
    }

    @Test
    @DisplayName("una celda numerica con formato % de Excel (0,21) se toma como se ve: 21")
    void numericaConFormatoPorcentaje() {
        Celda veintiuno = new Celda("0.21", new BigDecimal("0.21"), true);
        Celda diezYMedio = new Celda("0.105", new BigDecimal("0.105"), true);
        Celda descuento = new Celda("-0.05", new BigDecimal("-0.05"), true);

        assertThat(ParserPorcentaje.parsear(veintiuno).valor()).isEqualByComparingTo("21");
        assertThat(ParserPorcentaje.parsear(diezYMedio).valor()).isEqualByComparingTo("10.5");
        assertThat(ParserPorcentaje.parsear(descuento).valor()).isEqualByComparingTo("-5");
    }

    @Test
    @DisplayName("el mismo 0,21 sin formato % queda en 0,21 (despues da error de alicuota)")
    void numericaSinFormatoPorcentaje() {
        Celda comun = new Celda("0.21", new BigDecimal("0.21"), false);

        assertThat(ParserPorcentaje.parsear(comun).valor()).isEqualByComparingTo("0.21");
        assertThat(ParserPorcentaje.parsear(new Celda("21", new BigDecimal("21"))).valor())
            .isEqualByComparingTo("21");
    }

    @Test
    @DisplayName("un texto \"21 %\" no se multiplica por 100 aunque la celda diga porcentaje")
    void textoConSimboloNoSeMultiplica() {
        assertThat(ParserPorcentaje.parsear(new Celda("21 %", null, true)).valor()).isEqualByComparingTo("21");
    }
}
