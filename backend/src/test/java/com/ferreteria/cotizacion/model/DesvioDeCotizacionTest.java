package com.ferreteria.cotizacion.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Dominio puro: un dolar que se aleja mas del umbral de la ferreteria del vigente no se aplica solo (auditoria H09). */
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
    @DisplayName("el umbral por defecto es 5 %")
    void umbralPorDefecto() {
        assertThat(DesvioDeCotizacion.UMBRAL_POR_DEFECTO).isEqualByComparingTo("5");
    }

    @Test
    @DisplayName("exactamente el umbral se acepta solo; un poco mas ya hay que confirmarlo, hacia arriba o hacia abajo")
    void borde() {
        assertThat(DesvioDeCotizacion.hayQueConfirmar(bd("1000"), bd("1050"), bd("5"))).isFalse();
        assertThat(DesvioDeCotizacion.hayQueConfirmar(bd("1000"), bd("950"), bd("5"))).isFalse();
        assertThat(DesvioDeCotizacion.hayQueConfirmar(bd("1000"), bd("1050.1"), bd("5"))).isTrue();
        assertThat(DesvioDeCotizacion.hayQueConfirmar(bd("1000"), bd("949.9"), bd("5"))).isTrue();
    }

    @Test
    @DisplayName("el umbral es el de la ferreteria: 6 % pide confirmar con umbral 5 y se acepta solo con umbral 10")
    void umbralDeLaFerreteria() {
        assertThat(DesvioDeCotizacion.hayQueConfirmar(bd("1000"), bd("1060"), bd("5"))).isTrue();
        assertThat(DesvioDeCotizacion.hayQueConfirmar(bd("1000"), bd("1060"), bd("10"))).isFalse();
        assertThat(DesvioDeCotizacion.hayQueConfirmar(bd("1000"), bd("1200"), bd("20"))).isFalse();
        assertThat(DesvioDeCotizacion.hayQueConfirmar(bd("1000"), bd("1200.1"), bd("20"))).isTrue();
    }

    @Test
    @DisplayName("el caso de la auditoria: 1450 a 14,5 hay que confirmarlo")
    void cienVecesMenos() {
        assertThat(DesvioDeCotizacion.hayQueConfirmar(bd("1450"), bd("14.5"), bd("50"))).isTrue();
    }

    @Test
    @DisplayName("sin anterior (el primer dolar) o con anterior en cero no hay nada que confirmar")
    void sinAnterior() {
        assertThat(DesvioDeCotizacion.hayQueConfirmar(null, bd("1450"), bd("5"))).isFalse();
        assertThat(DesvioDeCotizacion.hayQueConfirmar(BigDecimal.ZERO, bd("1450"), bd("5"))).isFalse();
    }

    @Test
    @DisplayName("el umbral valido va de 1 a 50 %, con hasta 2 decimales")
    void umbralValido() {
        assertThat(DesvioDeCotizacion.umbralValido(bd("1"))).isTrue();
        assertThat(DesvioDeCotizacion.umbralValido(bd("50"))).isTrue();
        assertThat(DesvioDeCotizacion.umbralValido(bd("7.25"))).isTrue();
        assertThat(DesvioDeCotizacion.umbralValido(bd("0.99"))).isFalse();
        assertThat(DesvioDeCotizacion.umbralValido(bd("50.01"))).isFalse();
        assertThat(DesvioDeCotizacion.umbralValido(bd("5.001"))).isFalse();
        assertThat(DesvioDeCotizacion.umbralValido(null)).isFalse();
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
