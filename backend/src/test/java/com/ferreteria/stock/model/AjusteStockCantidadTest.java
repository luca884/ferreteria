package com.ferreteria.stock.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Dominio puro: el modo CANTIDAD suma (o resta) N al stock vigente, sin pasar por un stock real. */
class AjusteStockCantidadTest {

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    @Test
    @DisplayName("sumar 25 con 10 en stock queda en 35")
    void suma() {
        assertThat(AjusteStock.stockResultante(bd("10"), bd("25"), "UNIDAD")).isEqualByComparingTo("35");
    }

    @Test
    @DisplayName("restar 3 con 10 queda en 7 y restar todo deja 0")
    void resta() {
        assertThat(AjusteStock.stockResultante(bd("10"), bd("-3"), "UNIDAD")).isEqualByComparingTo("7");
        assertThat(AjusteStock.stockResultante(bd("10"), bd("-10"), "UNIDAD")).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("sacar mas de lo que hay se rechaza nombrando la unidad")
    void noQuedaNegativo() {
        assertThatThrownBy(() -> AjusteStock.stockResultante(bd("3"), bd("-5"), "UNIDAD"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No podés sacar 5: hay 3 unidades.");
        assertThatThrownBy(() -> AjusteStock.stockResultante(bd("1"), bd("-2"), "UNIDAD"))
                .hasMessage("No podés sacar 2: hay 1 unidad.");
        assertThatThrownBy(() -> AjusteStock.stockResultante(bd("1.500"), bd("-2.25"), "METRO"))
                .hasMessage("No podés sacar 2,25: hay 1,5 metros.");
        assertThatThrownBy(() -> AjusteStock.stockResultante(bd("0"), bd("-1"), "KILO"))
                .hasMessage("No podés sacar 1: hay 0 kilos.");
    }

    @Test
    @DisplayName("la cantidad es obligatoria y no puede ser cero")
    void obligatoriaYNoCero() {
        assertThatThrownBy(() -> AjusteStock.validarCantidad(null, false))
                .hasMessage("La cantidad es obligatoria.");
        assertThatThrownBy(() -> AjusteStock.validarCantidad(bd("0.000"), true))
                .hasMessage("La cantidad no puede ser cero.");
    }

    @Test
    @DisplayName("UNIDAD solo enteros; METRO, KILO y LITRO hasta 3 decimales")
    void decimales() {
        AjusteStock.validarCantidad(bd("-2.125"), true);
        AjusteStock.validarCantidad(bd("4.000"), false);
        assertThatThrownBy(() -> AjusteStock.validarCantidad(bd("2.5"), false))
                .hasMessage("Este artículo se cuenta por unidad: la cantidad tiene que ser un número entero.");
        assertThatThrownBy(() -> AjusteStock.validarCantidad(bd("1.2345"), true))
                .hasMessage("La cantidad admite hasta 3 decimales.");
    }

    @Test
    @DisplayName("una cantidad que no entra en la columna se rechaza")
    void demasiadoGrande() {
        assertThatThrownBy(() -> AjusteStock.validarCantidad(bd("1000000000000"), false))
                .hasMessage("La cantidad es demasiado grande: el máximo es 999.999.999.999,999.");
    }
}
