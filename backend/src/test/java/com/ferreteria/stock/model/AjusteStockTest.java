package com.ferreteria.stock.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** Dominio puro: el ajuste se carga como stock real contado y el sistema calcula la diferencia. */
class AjusteStockTest {

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    @Test
    @DisplayName("la diferencia es el stock real menos el actual: 12 contados con 10 en sistema da +2")
    void diferenciaPositiva() {
        assertThat(AjusteStock.diferencia(bd("10"), bd("12"), false)).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("una rotura baja el stock: 7 contados con 10 en sistema da -3")
    void diferenciaNegativa() {
        assertThat(AjusteStock.diferencia(bd("10"), bd("7"), false)).isEqualByComparingTo("-3");
    }

    @Test
    @DisplayName("con stock negativo, contar 0 lo lleva a 0: -4 da +4")
    void desdeNegativo() {
        assertThat(AjusteStock.diferencia(bd("-4"), BigDecimal.ZERO, false)).isEqualByComparingTo("4");
    }

    @Test
    @DisplayName("METRO, KILO y LITRO admiten hasta 3 decimales: 145,5 m con 150 da -4,5")
    void decimales() {
        assertThat(AjusteStock.diferencia(bd("150"), bd("145.5"), true)).isEqualByComparingTo("-4.5");
        assertThat(AjusteStock.diferencia(bd("0"), bd("2.125"), true)).isEqualByComparingTo("2.125");
    }

    @Test
    @DisplayName("UNIDAD acepta un entero escrito con decimales en cero (10.000)")
    void unidadConCerosDecimales() {
        assertThat(AjusteStock.diferencia(bd("8"), bd("10.000"), false)).isEqualByComparingTo("2");
    }

    @Test
    @DisplayName("UNIDAD no admite decimales")
    void unidadSinDecimales() {
        assertThatThrownBy(() -> AjusteStock.diferencia(bd("8"), bd("2.5"), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Este artículo se cuenta por unidad: el stock real tiene que ser un número entero.");
    }

    @Test
    @DisplayName("mas de 3 decimales no se admite")
    void masDeTresDecimales() {
        assertThatThrownBy(() -> AjusteStock.diferencia(bd("8"), bd("2.1255"), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El stock real admite hasta 3 decimales.");
    }

    @Test
    @DisplayName("el stock real contado no puede ser negativo")
    void negativo() {
        assertThatThrownBy(() -> AjusteStock.diferencia(bd("8"), bd("-1"), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El stock real no puede ser negativo.");
    }

    @Test
    @DisplayName("el stock real es obligatorio")
    void obligatorio() {
        assertThatThrownBy(() -> AjusteStock.diferencia(bd("8"), null, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El stock real es obligatorio.");
    }

    @Test
    @DisplayName("el stock real no puede superar 999.999.999.999,999")
    void demasiadoGrande() {
        assertThatThrownBy(() -> AjusteStock.diferencia(bd("0"), bd("1000000000000"), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El stock real es demasiado grande: el máximo es 999.999.999.999,999.");
    }

    @ParameterizedTest
    @ValueSource(strings = {"10", "10.000"})
    @DisplayName("si el stock real es igual al actual no hay nada que ajustar")
    void sinDiferencia(String real) {
        assertThatThrownBy(() -> AjusteStock.diferencia(bd("10"), bd(real), false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El stock real es igual al actual: no hay nada que ajustar.");
    }

    @Test
    @DisplayName("validarStockReal acepta lo valido sin mirar el stock actual")
    void validarSinStockActual() {
        AjusteStock.validarStockReal(bd("3"), false);
        AjusteStock.validarStockReal(bd("3.5"), true);
        assertThatThrownBy(() -> AjusteStock.validarStockReal(bd("3.5"), false))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
