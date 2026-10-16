package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Descuento o recargo de una columna de la lista: valida el rango y distingue "borrar" de "conservar". */
class AjusteDeFilaTest {

    private static Celda texto(String valor) {
        return new Celda(valor, null);
    }

    @ParameterizedTest(name = "\"{0}\" -> {1}")
    @CsvSource(delimiter = '|', value = {
        "-5 | -5", "+3 | 3", "3,5 | 3.5", "-5 % | -5", "1000 | 1000", "-99,99 | -99.99", "0 | 0", "10.5 | 10.5"})
    @DisplayName("un descuento (negativo) o un recargo (positivo) dentro del rango es valido")
    void validos(String entrada, String esperado) {
        AjusteDeFila.ResultadoAjuste resultado = AjusteDeFila.interpretar(1, texto(entrada));

        assertThat(resultado.esValor()).isTrue();
        assertThat(resultado.valor()).isEqualByComparingTo(esperado);
        assertThat(resultado.mensaje()).isNull();
    }

    @ParameterizedTest(name = "{0} -> fuera de rango")
    @CsvSource({"-100", "-150", "1000.01", "-99.995", "5000"})
    @DisplayName("-100 o menos, o mas de 1000, es un error por fuera del rango (decision 31)")
    void fueraDeRango(String entrada) {
        AjusteDeFila.ResultadoAjuste resultado = AjusteDeFila.interpretar(1, new Celda(entrada, new BigDecimal(entrada)));

        assertThat(resultado.esError()).isTrue();
        assertThat(resultado.valor()).isNull();
        assertThat(resultado.mensaje())
            .isEqualTo("El descuento o recargo 1 tiene que estar entre -99,99 y 1000.");
    }

    @Test
    @DisplayName("el error nombra la posicion de la columna: 1, 2 o 3")
    void nombraLaPosicion() {
        assertThat(AjusteDeFila.interpretar(2, texto("abc")).mensaje())
            .isEqualTo("El descuento o recargo 2 no es un número.");
        assertThat(AjusteDeFila.interpretar(3, texto("abc")).mensaje())
            .isEqualTo("El descuento o recargo 3 no es un número.");
        assertThat(AjusteDeFila.interpretar(3, texto("-100")).mensaje())
            .isEqualTo("El descuento o recargo 3 tiene que estar entre -99,99 y 1000.");
    }

    @Test
    @DisplayName("un texto que no es numero es un error")
    void noNumerico() {
        AjusteDeFila.ResultadoAjuste resultado = AjusteDeFila.interpretar(1, texto("abc"));

        assertThat(resultado.esError()).isTrue();
        assertThat(resultado.mensaje()).isEqualTo("El descuento o recargo 1 no es un número.");
    }

    @Test
    @DisplayName("celda vacia con la columna mapeada es borrar (null)")
    void celdaVaciaBorra() {
        AjusteDeFila.ResultadoAjuste vacia = AjusteDeFila.interpretar(1, Celda.VACIA);
        AjusteDeFila.ResultadoAjuste enBlanco = AjusteDeFila.interpretar(2, texto("   "));

        assertThat(vacia.esBorrar()).isTrue();
        assertThat(vacia.valor()).isNull();
        assertThat(vacia.esConservar()).isFalse();
        assertThat(vacia.esError()).isFalse();
        assertThat(enBlanco.esBorrar()).isTrue();
    }

    @Test
    @DisplayName("columna no mapeada (celda null) es conservar, distinto de borrar")
    void sinColumnaConserva() {
        AjusteDeFila.ResultadoAjuste resultado = AjusteDeFila.interpretar(1, null);

        assertThat(resultado.esConservar()).isTrue();
        assertThat(resultado.esBorrar()).isFalse();
        assertThat(resultado.esValor()).isFalse();
        assertThat(resultado.valor()).isNull();
    }

    @Test
    @DisplayName("el valor queda a 2 decimales (como DECIMAL(6,2)) para que lo calculado sea lo guardado")
    void aDosDecimales() {
        assertThat(AjusteDeFila.interpretar(1, texto("3,555")).valor()).isEqualTo(new BigDecimal("3.56"));
        assertThat(AjusteDeFila.interpretar(1, texto("3,5")).valor()).isEqualTo(new BigDecimal("3.50"));
        assertThat(AjusteDeFila.interpretar(1, texto("-99,994")).valor()).isEqualTo(new BigDecimal("-99.99"));
    }

    @Test
    @DisplayName("una celda numerica con formato % de Excel (-0,05) es un descuento de 5")
    void conFormatoPorcentaje() {
        AjusteDeFila.ResultadoAjuste resultado =
            AjusteDeFila.interpretar(1, new Celda("-0.05", new BigDecimal("-0.05"), true));

        assertThat(resultado.valor()).isEqualByComparingTo("-5");
    }
}
