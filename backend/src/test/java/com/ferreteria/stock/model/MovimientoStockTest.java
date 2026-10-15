package com.ferreteria.stock.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MovimientoStockTest {

    @Test
    @DisplayName("un ajuste guarda la diferencia con signo, el motivo y ningun comprobante")
    void ajuste() {
        MovimientoStock m = MovimientoStock.ajuste(3L, 40L, new BigDecimal("-2.5"), "Rotura", 9L);

        assertThat(m.getTipo()).isEqualTo(TipoMovimiento.AJUSTE);
        assertThat(m.getFerreteriaId()).isEqualTo(3L);
        assertThat(m.getArticuloId()).isEqualTo(40L);
        assertThat(m.getCantidad()).isEqualByComparingTo("-2.5");
        assertThat(m.getMotivo()).isEqualTo("Rotura");
        assertThat(m.getUsuarioId()).isEqualTo(9L);
        assertThat(m.getComprobanteId()).isNull();
    }

    @Test
    @DisplayName("un ajuste sin diferencia o sin motivo no se puede crear")
    void ajusteInvalido() {
        assertThatThrownBy(() -> MovimientoStock.ajuste(3L, 40L, BigDecimal.ZERO, "Conteo", 9L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MovimientoStock.ajuste(3L, 40L, BigDecimal.ONE, " ", 9L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MovimientoStock.ajuste(3L, 40L, BigDecimal.ONE, "x".repeat(201), 9L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
