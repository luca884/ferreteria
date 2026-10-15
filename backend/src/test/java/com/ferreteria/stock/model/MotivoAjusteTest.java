package com.ferreteria.stock.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** El motivo del ajuste queda escrito en el movimiento como texto legible. */
class MotivoAjusteTest {

    @Test
    @DisplayName("sin detalle queda el nombre del motivo")
    void sinDetalle() {
        assertThat(MotivoAjuste.INGRESO_MERCADERIA.describir(null)).isEqualTo("Ingreso de mercadería");
        assertThat(MotivoAjuste.ROTURA.describir("   ")).isEqualTo("Rotura");
        assertThat(MotivoAjuste.CONTEO.describir("")).isEqualTo("Conteo");
    }

    @Test
    @DisplayName("con detalle se agrega despues de dos puntos y sin espacios sobrantes")
    void conDetalle() {
        assertThat(MotivoAjuste.ROTURA.describir("  se cayó la caja ")).isEqualTo("Rotura: se cayó la caja");
    }

    @Test
    @DisplayName("OTRO exige el detalle")
    void otroExigeDetalle() {
        assertThat(MotivoAjuste.OTRO.describir("préstamo a la otra sucursal"))
                .isEqualTo("Otro: préstamo a la otra sucursal");
        assertThatThrownBy(() -> MotivoAjuste.OTRO.describir("  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Contá el motivo del ajuste.");
    }

    @Test
    @DisplayName("el detalle admite hasta 150 caracteres")
    void detalleLargo() {
        assertThat(MotivoAjuste.CONTEO.describir("x".repeat(150))).hasSize("Conteo: ".length() + 150);
        assertThatThrownBy(() -> MotivoAjuste.CONTEO.describir("x".repeat(151)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("El detalle del motivo no puede superar 150 caracteres.");
    }
}
