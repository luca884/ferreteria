package com.ferreteria.catalogo.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Dominio puro: sin Spring ni base de datos. */
class BonificacionesTest {

    @Test
    @DisplayName("NINGUNA no tiene ninguna bonificacion y el numerador es 100 x 100 x 100")
    void ningunaEsTodoNull() {
        assertThat(Bonificaciones.NINGUNA.b1()).isNull();
        assertThat(Bonificaciones.NINGUNA.b2()).isNull();
        assertThat(Bonificaciones.NINGUNA.b3()).isNull();
        assertThat(Bonificaciones.NINGUNA.numerador()).isEqualByComparingTo("1000000");
    }

    @Test
    @DisplayName("una bonificacion null cuenta como 0")
    void nullCuentaComoCero() {
        Bonificaciones soloUna = new Bonificaciones(new BigDecimal("10"), null, null);

        // (100 - 10) x 100 x 100
        assertThat(soloUna.numerador()).isEqualByComparingTo("900000");
    }

    @Test
    @DisplayName("el numerador de 40 + 10 es exacto: 60 x 90 x 100")
    void numeradorExacto() {
        Bonificaciones b = new Bonificaciones(new BigDecimal("40"), new BigDecimal("10"), null);

        assertThat(b.numerador()).isEqualByComparingTo("540000");
    }

    @Test
    @DisplayName("el numerador conserva los decimales sin redondear")
    void numeradorConDecimales() {
        Bonificaciones b = new Bonificaciones(new BigDecimal("12.50"), new BigDecimal("0.25"), null);

        // 87.50 x 99.75 x 100
        assertThat(b.numerador()).isEqualByComparingTo("872812.50");
    }

    @Test
    @DisplayName("0 y 100 son valores validos")
    void extremosValidos() {
        Bonificaciones b = new Bonificaciones(BigDecimal.ZERO, new BigDecimal("100"), null);

        assertThat(b.numerador()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("fuera de 0..100 se rechaza")
    void fueraDeRango() {
        assertThatThrownBy(() -> new Bonificaciones(new BigDecimal("101"), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Bonificaciones(null, new BigDecimal("-1"), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Bonificaciones(null, null, new BigDecimal("100.01")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
