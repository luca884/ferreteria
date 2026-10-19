package com.ferreteria.clientes.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CC-001, CC-037: formato único de la referencia que el libro copia de ventas y de recibos. Dominio puro. */
class ReferenciasTest {

    @Test
    @DisplayName("la venta se escribe «Venta N.º n» (con el ordinal º)")
    void venta() {
        assertThat(Referencias.venta(7L)).isEqualTo("Venta N.º 7");
        assertThat(Referencias.venta(123456L)).isEqualTo("Venta N.º 123456");
    }

    @Test
    @DisplayName("el recibo se escribe «Recibo N.º n» (con el ordinal º)")
    void recibo() {
        assertThat(Referencias.recibo(12L)).isEqualTo("Recibo N.º 12");
        assertThat(Referencias.recibo(1L)).isEqualTo("Recibo N.º 1");
    }

    @Test
    @DisplayName("el ordinal es º (U+00BA), no el símbolo de grado ni «o»")
    void ordinalExacto() {
        assertThat(Referencias.venta(1L).charAt(Referencias.venta(1L).indexOf("N.") + 2)).isEqualTo('\u00BA');
        assertThat(Referencias.recibo(1L).charAt(Referencias.recibo(1L).indexOf("N.") + 2)).isEqualTo('\u00BA');
    }

    @Test
    @DisplayName("un número nulo o no positivo se rechaza")
    void numeroInvalido() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Referencias.venta(null))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Referencias.recibo(0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
