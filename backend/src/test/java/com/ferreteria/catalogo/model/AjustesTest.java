package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Hasta tres descuentos o recargos por articulo: negativo es descuento, positivo es recargo. */
class AjustesTest {

    private static BigDecimal bd(String valor) {
        return valor == null ? null : new BigDecimal(valor);
    }

    @Test
    @DisplayName("NINGUNO vale 100 x 100 x 100: ningun ajuste no cambia el costo")
    void ningunoNoCambiaNada() {
        assertThat(Ajustes.NINGUNO.numerador()).isEqualByComparingTo("1000000");
        assertThat(new Ajustes(null, null, null)).isEqualTo(Ajustes.NINGUNO);
    }

    @Test
    @DisplayName("el numerador es el producto de (100 + ai), con null como 100")
    void numeradorEsElProducto() {
        assertThat(new Ajustes(bd("-5"), bd("3"), null).numerador()).isEqualByComparingTo("978500");
        assertThat(new Ajustes(bd("3.5"), null, null).numerador()).isEqualByComparingTo("1035000");
        assertThat(new Ajustes(null, null, bd("-10")).numerador()).isEqualByComparingTo("900000");
    }

    @Test
    @DisplayName("+3 y +3 se encadenan (103 x 103) y no se suman (106)")
    void seEncadenanNoSeSuman() {
        BigDecimal encadenado = new Ajustes(bd("3"), bd("3"), null).numerador();

        assertThat(encadenado).isEqualByComparingTo("1060900");
        assertThat(encadenado).isNotEqualByComparingTo("1060000");
    }

    @Test
    @DisplayName("los limites del rango, -99,99 y 1000, son validos")
    void limitesValidos() {
        assertThat(new Ajustes(bd("-99.99"), bd("1000"), bd("0")).numerador()).isEqualByComparingTo("1100");
    }

    @Test
    @DisplayName("un ajuste de -100 o menos, o de mas de 1000, es un error de programacion")
    void fueraDeRango() {
        assertThatThrownBy(() -> new Ajustes(bd("-100"), null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Ajustes(null, bd("-150"), null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Ajustes(null, null, bd("1000.01"))).isInstanceOf(IllegalArgumentException.class);
    }
}
