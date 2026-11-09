package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Dominio puro: el formato argentino de los importes que van en mensajes al usuario. Los espacios del importe son
 * duros (U+00A0, el escape de Java en los textos esperados): un aviso en una celda angosta no lo parte en dos renglones.
 */
class ImporteArgentinoTest {

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    @Test
    @DisplayName("pesos: 1234.5 es «$ 1.234,50»")
    void pesos() {
        assertThat(ImporteArgentino.formato(bd("1234.5"), Moneda.ARS)).isEqualTo("$\u00a01.234,50");
    }

    @Test
    @DisplayName("dolares: 1234.5 es «US$ 1.234,50»")
    void dolares() {
        assertThat(ImporteArgentino.formato(bd("1234.5"), Moneda.USD)).isEqualTo("US$\u00a01.234,50");
    }

    @Test
    @DisplayName("siempre dos decimales: 100 es «$ 100,00» y 950 es «$ 950,00»")
    void dosDecimales() {
        assertThat(ImporteArgentino.formato(bd("100"), Moneda.ARS)).isEqualTo("$\u00a0100,00");
        assertThat(ImporteArgentino.formato(bd("950"), Moneda.ARS)).isEqualTo("$\u00a0950,00");
    }

    @Test
    @DisplayName("un negativo lleva el signo delante del simbolo: «-$ 5,00»")
    void negativo() {
        assertThat(ImporteArgentino.formato(bd("-5"), Moneda.ARS)).isEqualTo("-$\u00a05,00");
        assertThat(ImporteArgentino.formato(bd("-5"), Moneda.USD)).isEqualTo("-US$\u00a05,00");
    }

    @Test
    @DisplayName("sin punto flotante: 0,1 + 0,2 es «$ 0,30»")
    void sinPuntoFlotante() {
        assertThat(ImporteArgentino.formato(bd("0.1").add(bd("0.2")), Moneda.ARS)).isEqualTo("$\u00a00,30");
    }

    @Test
    @DisplayName("redondea HALF_UP al mostrar: 0,005 es «$ 0,01»")
    void redondeaAlMostrar() {
        assertThat(ImporteArgentino.formato(bd("0.005"), Moneda.ARS)).isEqualTo("$\u00a00,01");
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
        assertThat(ImporteArgentino.formatoCompacto(bd("100.00"), Moneda.ARS)).isEqualTo("$\u00a0100");
        assertThat(ImporteArgentino.formatoCompacto(bd("100.5"), Moneda.USD)).isEqualTo("US$\u00a0100,50");
        assertThat(ImporteArgentino.formatoCompacto(bd("-50"), Moneda.USD)).isEqualTo("-US$\u00a050");
    }

    @Test
    @DisplayName("el importe no se parte en dos renglones: la moneda y el numero van con espacio duro, sin espacio comun")
    void monedaYNumeroConEspacioDuro() {
        assertThat(ImporteArgentino.formato(bd("769.5"), Moneda.ARS)).isEqualTo("$\u00a0769,50").doesNotContain(" ");
        assertThat(ImporteArgentino.formato(bd("12"), Moneda.USD)).isEqualTo("US$\u00a012,00").doesNotContain(" ");
        assertThat(ImporteArgentino.formatoCompacto(bd("100"), Moneda.ARS)).isEqualTo("$\u00a0100").doesNotContain(" ");
        assertThat(ImporteArgentino.formatoCompacto(bd("100"), Moneda.USD)).isEqualTo("US$\u00a0100")
            .doesNotContain(" ");
    }

    @Test
    @DisplayName("con su unidad, el numero y «/ m» tambien van con espacios duros; por unidad, sin sufijo")
    void numeroYUnidadConEspacioDuro() {
        assertThat(ImporteArgentino.formato(bd("1231.2"), Moneda.ARS, Unidad.METRO))
            .isEqualTo("$\u00a01.231,20\u00a0/\u00a0m").doesNotContain(" ");
        assertThat(ImporteArgentino.formato(bd("10"), Moneda.USD, Unidad.KILO))
            .isEqualTo("US$\u00a010,00\u00a0/\u00a0kg").doesNotContain(" ");
        assertThat(ImporteArgentino.formato(bd("160"), Moneda.ARS, Unidad.LITRO))
            .isEqualTo("$\u00a0160,00\u00a0/\u00a0l").doesNotContain(" ");
        assertThat(ImporteArgentino.formato(bd("100"), Moneda.ARS, Unidad.UNIDAD)).isEqualTo("$\u00a0100,00");
    }
}
