package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Dominio puro: el formato argentino de los importes que van en mensajes al usuario. */
class ImporteArgentinoTest {

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    @Test
    @DisplayName("pesos: 1234.5 es «$ 1.234,50»")
    void pesos() {
        assertThat(ImporteArgentino.formato(bd("1234.5"), Moneda.ARS)).isEqualTo("$ 1.234,50");
    }

    @Test
    @DisplayName("dolares: 1234.5 es «US$ 1.234,50»")
    void dolares() {
        assertThat(ImporteArgentino.formato(bd("1234.5"), Moneda.USD)).isEqualTo("US$ 1.234,50");
    }

    @Test
    @DisplayName("siempre dos decimales: 100 es «$ 100,00» y 950 es «$ 950,00»")
    void dosDecimales() {
        assertThat(ImporteArgentino.formato(bd("100"), Moneda.ARS)).isEqualTo("$ 100,00");
        assertThat(ImporteArgentino.formato(bd("950"), Moneda.ARS)).isEqualTo("$ 950,00");
    }

    @Test
    @DisplayName("un negativo lleva el signo delante del simbolo: «-$ 5,00»")
    void negativo() {
        assertThat(ImporteArgentino.formato(bd("-5"), Moneda.ARS)).isEqualTo("-$ 5,00");
        assertThat(ImporteArgentino.formato(bd("-5"), Moneda.USD)).isEqualTo("-US$ 5,00");
    }

    @Test
    @DisplayName("sin punto flotante: 0,1 + 0,2 es «$ 0,30»")
    void sinPuntoFlotante() {
        assertThat(ImporteArgentino.formato(bd("0.1").add(bd("0.2")), Moneda.ARS)).isEqualTo("$ 0,30");
    }

    @Test
    @DisplayName("redondea HALF_UP al mostrar: 0,005 es «$ 0,01»")
    void redondeaAlMostrar() {
        assertThat(ImporteArgentino.formato(bd("0.005"), Moneda.ARS)).isEqualTo("$ 0,01");
    }

    @Test
    @DisplayName("el monto de un masivo se muestra sin decimales que no significan nada: «100» y «100,50»")
    void montoCompacto() {
        assertThat(ImporteArgentino.montoCompacto(bd("100"))).isEqualTo("100");
        assertThat(ImporteArgentino.montoCompacto(bd("100.00"))).isEqualTo("100");
        assertThat(ImporteArgentino.montoCompacto(bd("100.50"))).isEqualTo("100,50");
        assertThat(ImporteArgentino.montoCompacto(bd("-1234.5"))).isEqualTo("-1.234,50");
    }

    @Test
    @DisplayName("formato compacto con simbolo: «$ 100», «US$ 100,50» y «-US$ 50»")
    void formatoCompacto() {
        assertThat(ImporteArgentino.formatoCompacto(bd("100.00"), Moneda.ARS)).isEqualTo("$ 100");
        assertThat(ImporteArgentino.formatoCompacto(bd("100.5"), Moneda.USD)).isEqualTo("US$ 100,50");
        assertThat(ImporteArgentino.formatoCompacto(bd("-50"), Moneda.USD)).isEqualTo("-US$ 50");
    }
}
