package com.ferreteria.cotizacion.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Dominio puro: un dolar que se aleja mas del 20 % del vigente no se aplica solo (auditoria H09). */
class DesvioDeCotizacionTest {

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    @Test
    @DisplayName("el desvio es el porcentaje de diferencia contra el anterior, sin signo y con 2 decimales")
    void porcentaje() {
        assertThat(DesvioDeCotizacion.porcentaje(bd("1000"), bd("1200"))).isEqualByComparingTo("20.00");
        assertThat(DesvioDeCotizacion.porcentaje(bd("1450"), bd("14.5"))).isEqualByComparingTo("99.00");
        assertThat(DesvioDeCotizacion.porcentaje(bd("1535"), bd("1555"))).isEqualByComparingTo("1.30");
    }

    @Test
    @DisplayName("exactamente 20 % se acepta solo; 20,01 % ya hay que confirmarlo, hacia arriba o hacia abajo")
    void borde() {
        assertThat(DesvioDeCotizacion.hayQueConfirmar(bd("1000"), bd("1200"))).isFalse();
        assertThat(DesvioDeCotizacion.hayQueConfirmar(bd("1000"), bd("800"))).isFalse();
        assertThat(DesvioDeCotizacion.hayQueConfirmar(bd("1000"), bd("1200.1"))).isTrue();
        assertThat(DesvioDeCotizacion.hayQueConfirmar(bd("1000"), bd("799.9"))).isTrue();
    }

    @Test
    @DisplayName("el caso de la auditoria: 1450 a 14,5 hay que confirmarlo")
    void cienVecesMenos() {
        assertThat(DesvioDeCotizacion.hayQueConfirmar(bd("1450"), bd("14.5"))).isTrue();
    }

    @Test
    @DisplayName("sin anterior (el primer dolar) o con anterior en cero no hay nada que confirmar")
    void sinAnterior() {
        assertThat(DesvioDeCotizacion.hayQueConfirmar(null, bd("1450"))).isFalse();
        assertThat(DesvioDeCotizacion.hayQueConfirmar(BigDecimal.ZERO, bd("1450"))).isFalse();
    }

    @Test
    @DisplayName("el aviso al Dueno dice el valor nuevo, el desvio y el dolar con el que siguen los precios")
    void aviso() {
        DolarAConfirmar aConfirmar = DolarAConfirmar.de("oficial", LadoCotizacion.VENTA, bd("1450"), bd("14.5"));

        assertThat(aConfirmar.desvioPorcentaje()).isEqualByComparingTo("99.00");
        assertThat(aConfirmar.mensaje()).isEqualTo("El dólar oficial venta vino en $ 14,50, un 99 % distinto del anterior "
                + "($ 1.450,00). Los precios siguen con $ 1.450,00 hasta que lo confirmes.");
        assertThat(DolarAConfirmar.de("bolsa", LadoCotizacion.COMPRA, bd("1000"), bd("1250.5")).mensaje())
                .startsWith("El dólar MEP compra vino en $ 1.250,50, un 25,05 % distinto");
    }
}
