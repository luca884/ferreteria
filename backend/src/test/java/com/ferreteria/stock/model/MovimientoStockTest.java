package com.ferreteria.stock.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MovimientoStockTest {

    /** La fecha que pasa quien crea el movimiento (la de la venta, o la del reloj). */
    private static final LocalDateTime FECHA = LocalDateTime.of(2026, 10, 28, 10, 15, 30);

    @Test
    @DisplayName("un ajuste guarda la diferencia con signo, el motivo, la fecha recibida y ningun comprobante")
    void ajuste() {
        MovimientoStock m = MovimientoStock.ajuste(3L, 40L, new BigDecimal("-2.5"), "Rotura", 9L, FECHA);

        assertThat(m.getTipo()).isEqualTo(TipoMovimiento.AJUSTE);
        assertThat(m.getFerreteriaId()).isEqualTo(3L);
        assertThat(m.getArticuloId()).isEqualTo(40L);
        assertThat(m.getCantidad()).isEqualByComparingTo("-2.5");
        assertThat(m.getMotivo()).isEqualTo("Rotura");
        assertThat(m.getUsuarioId()).isEqualTo(9L);
        assertThat(m.getComprobanteId()).isNull();
        assertThat(m.getFecha()).isEqualTo(FECHA);
    }

    @Test
    @DisplayName("una salida por remito guarda la cantidad negativa, el remito y ningun comprobante")
    void remito() {
        MovimientoStock m = MovimientoStock.remito(3L, 40L, new BigDecimal("2.5"), 70L, 9L, FECHA);

        assertThat(m.getTipo()).isEqualTo(TipoMovimiento.REMITO);
        assertThat(m.getFerreteriaId()).isEqualTo(3L);
        assertThat(m.getArticuloId()).isEqualTo(40L);
        assertThat(m.getCantidad()).isEqualByComparingTo("-2.5");
        assertThat(m.getRemitoId()).isEqualTo(70L);
        assertThat(m.getComprobanteId()).isNull();
        assertThat(m.getUsuarioId()).isEqualTo(9L);
    }

    @Test
    @DisplayName("anular un remito devuelve la cantidad con signo positivo y guarda el remito")
    void anulacionDeRemito() {
        MovimientoStock m = MovimientoStock.anulacionRemito(3L, 40L, new BigDecimal("2.5"), 70L, 9L, FECHA);

        assertThat(m.getTipo()).isEqualTo(TipoMovimiento.ANULACION_REMITO);
        assertThat(m.getCantidad()).isEqualByComparingTo("2.5");
        assertThat(m.getRemitoId()).isEqualTo(70L);
        assertThat(m.getComprobanteId()).isNull();
    }

    @Test
    @DisplayName("la diferencia con el remito es una venta con signo, con comprobante, remito y motivo")
    void diferenciaConRemito() {
        MovimientoStock sale = MovimientoStock.diferenciaConRemito(
                3L, 40L, new BigDecimal("-2"), 77L, 70L, "Diferencia con el remito N.º 5", 9L, FECHA);
        MovimientoStock vuelve = MovimientoStock.diferenciaConRemito(
                3L, 41L, new BigDecimal("1.5"), 77L, 70L, "Diferencia con el remito N.º 5", 9L, FECHA);

        assertThat(sale.getTipo()).isEqualTo(TipoMovimiento.VENTA);
        assertThat(sale.getCantidad()).isEqualByComparingTo("-2");
        assertThat(vuelve.getCantidad()).isEqualByComparingTo("1.5");
        assertThat(sale.getComprobanteId()).isEqualTo(77L);
        assertThat(sale.getRemitoId()).isEqualTo(70L);
        assertThat(sale.getMotivo()).isEqualTo("Diferencia con el remito N.º 5");
    }

    @Test
    @DisplayName("una diferencia en cero no se puede crear")
    void diferenciaEnCero() {
        assertThatThrownBy(() -> MovimientoStock.diferenciaConRemito(
                3L, 40L, BigDecimal.ZERO, 77L, 70L, "Diferencia", 9L, FECHA))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("un ajuste sin diferencia o sin motivo no se puede crear")
    void ajusteInvalido() {
        assertThatThrownBy(() -> MovimientoStock.ajuste(3L, 40L, BigDecimal.ZERO, "Conteo", 9L, FECHA))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MovimientoStock.ajuste(3L, 40L, BigDecimal.ONE, " ", 9L, FECHA))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MovimientoStock.ajuste(3L, 40L, BigDecimal.ONE, "x".repeat(201), 9L, FECHA))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
