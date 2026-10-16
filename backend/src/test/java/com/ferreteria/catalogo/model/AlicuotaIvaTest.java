package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Alicuotas legales: la exacta de un porcentaje y la mas cercana a lo que sale de dos precios (decision 7). */
class AlicuotaIvaTest {

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "0, IVA_0", "0.00, IVA_0", "2.5, IVA_2_5", "5, IVA_5", "10.5, IVA_10_5", "10.50, IVA_10_5",
        "21, IVA_21", "21.00, IVA_21", "27, IVA_27", "27.0, IVA_27"
    })
    @DisplayName("legal: un porcentaje exacto de las seis alicuotas, sin importar la escala")
    void legalExacta(String porcentaje, AlicuotaIva esperada) {
        assertThat(AlicuotaIva.legal(bd(porcentaje))).contains(esperada);
    }

    @ParameterizedTest
    @CsvSource({"19", "0.21", "10.4", "10.51", "21.01", "-21", "100", "1"})
    @DisplayName("legal: cualquier otro valor no es una alicuota")
    void legalNoExiste(String porcentaje) {
        assertThat(AlicuotaIva.legal(bd(porcentaje))).isEmpty();
    }

    @ParameterizedTest(name = "sin {0} con {1} -> {2}, calculado {3}, advertir={4}")
    @CsvSource({
        // sin    con     alicuota   calculado  advertir
        "1000, 1105, IVA_10_5, 10.5, false",
        "1000, 1210, IVA_21, 21, false",
        "1000, 1000, IVA_0, 0, false",
        "1000, 1215, IVA_21, 21.5, false",
        "1000, 1216, IVA_21, 21.6, true",
        "1000, 1190, IVA_21, 19, true",
        "1000, 900, IVA_0, -10, true",
        "1000, 1270, IVA_27, 27, false",
        "1000, 1500, IVA_27, 50, true",
        "18, 21.78, IVA_21, 21, false",
        "47.5, 52.4875, IVA_10_5, 10.5, false",
    })
    @DisplayName("masCercana: elige la alicuota mas cercana y avisa si la diferencia pasa de 0,5 puntos")
    void masCercana(String sin, String con, AlicuotaIva esperada, String calculado, boolean advertir) {
        AlicuotaIva.Aproximacion resultado = AlicuotaIva.masCercana(bd(sin), bd(con));

        assertThat(resultado.alicuota()).isEqualTo(esperada);
        assertThat(resultado.calculado()).isEqualByComparingTo(calculado);
        assertThat(resultado.advertir()).isEqualTo(advertir);
    }

    @ParameterizedTest(name = "sin 1000 con {0} (calculado {1}) empata entre dos alicuotas -> {2} con advertencia")
    @CsvSource({
        "1012.5, 1.25, IVA_0",
        "1037.5, 3.75, IVA_2_5",
        "1077.5, 7.75, IVA_5",
        "1157.5, 15.75, IVA_10_5",
        "1240, 24, IVA_21",
    })
    @DisplayName("masCercana: en un empate exacto gana la menor y siempre hay advertencia (decision 36)")
    void empate(String con, String calculado, AlicuotaIva esperada) {
        AlicuotaIva.Aproximacion resultado = AlicuotaIva.masCercana(bd("1000"), bd(con));

        assertThat(resultado.alicuota()).isEqualTo(esperada);
        assertThat(resultado.calculado()).isEqualByComparingTo(calculado);
        assertThat(resultado.advertir()).isTrue();
    }

    @Test
    @DisplayName("masCercana: no divide para elegir, asi que un cociente periodico no mueve el limite")
    void sinRedondeoPrevio() {
        // 8,505 / 7 = 1,215 justo (21,5 %: el limite); 8,506 / 7 es 21,5143 % y ya pasa de 0,5 puntos.
        assertThat(AlicuotaIva.masCercana(bd("7"), bd("8.505")).advertir()).isFalse();
        assertThat(AlicuotaIva.masCercana(bd("7"), bd("8.506")).advertir()).isTrue();
        assertThat(AlicuotaIva.masCercana(bd("7"), bd("8.506")).alicuota()).isEqualTo(AlicuotaIva.IVA_21);
        // Un cociente periodico (8,5 / 7 = 21,428571...) tampoco cambia la eleccion.
        assertThat(AlicuotaIva.masCercana(bd("7"), bd("8.5")).alicuota()).isEqualTo(AlicuotaIva.IVA_21);
        assertThat(AlicuotaIva.masCercana(bd("7"), bd("8.5")).advertir()).isFalse();
    }

    @Test
    @DisplayName("masCercana: un precio sin IVA que no es positivo no se puede dividir y es un error de programacion")
    void sinIvaNoPositivo() {
        assertThatThrownBy(() -> AlicuotaIva.masCercana(BigDecimal.ZERO, bd("10")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> AlicuotaIva.masCercana(bd("-1"), bd("10")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
