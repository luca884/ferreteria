package com.ferreteria.ventas.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CU-10: ingresos, gastos y retiros de la caja. Dominio puro. */
class MovimientoCajaTest {

    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 12, 11, 30);

    @Test
    @DisplayName("un movimiento guarda la caja, el tipo, el importe, el motivo sin espacios de mas, el usuario y la hora")
    void registrar() {
        MovimientoCaja m = MovimientoCaja.de(3L, 20L, TipoMovimientoCaja.GASTO, new BigDecimal("1500.50"),
                "  Pago al cadete  ", 9L, AHORA);

        assertThat(m.getFerreteriaId()).isEqualTo(3L);
        assertThat(m.getCajaId()).isEqualTo(20L);
        assertThat(m.getTipo()).isEqualTo(TipoMovimientoCaja.GASTO);
        assertThat(m.getImporte()).isEqualByComparingTo("1500.50");
        assertThat(m.getMotivo()).isEqualTo("Pago al cadete");
        assertThat(m.getUsuarioId()).isEqualTo(9L);
        assertThat(m.getFecha()).isEqualTo(AHORA);
    }

    @Test
    @DisplayName("los datos validos no dan errores")
    void validos() {
        assertThat(MovimientoCaja.validar(TipoMovimientoCaja.RETIRO, new BigDecimal("0.01"), "Retiro del dueño"))
                .isEmpty();
        assertThat(MovimientoCaja.validar(TipoMovimientoCaja.INGRESO, new BigDecimal("9999999999999.99"),
                "x".repeat(MovimientoCaja.MOTIVO_MAXIMO))).isEmpty();
    }

    @Test
    @DisplayName("el importe tiene que ser mayor que cero, con hasta 2 decimales y dentro del tope de DECIMAL(15,2)")
    void importeInvalido() {
        assertThat(MovimientoCaja.validar(TipoMovimientoCaja.GASTO, BigDecimal.ZERO, "Luz"))
                .containsEntry("importe", "El importe tiene que ser mayor que cero.");
        assertThat(MovimientoCaja.validar(TipoMovimientoCaja.GASTO, new BigDecimal("-5"), "Luz"))
                .containsEntry("importe", "El importe tiene que ser mayor que cero.");
        assertThat(MovimientoCaja.validar(TipoMovimientoCaja.GASTO, null, "Luz"))
                .containsEntry("importe", "Cargá el importe.");
        assertThat(MovimientoCaja.validar(TipoMovimientoCaja.GASTO, new BigDecimal("1.234"), "Luz"))
                .containsEntry("importe", "El importe admite hasta 2 decimales.");
        assertThat(MovimientoCaja.validar(TipoMovimientoCaja.GASTO, new BigDecimal("10000000000000"), "Luz"))
                .containsEntry("importe", "El importe es demasiado grande: el máximo es $ 9.999.999.999.999,99.");
    }

    @Test
    @DisplayName("el motivo es obligatorio y corto; el tipo es obligatorio; los errores salen todos juntos")
    void motivoYTipoInvalidos() {
        assertThat(MovimientoCaja.validar(TipoMovimientoCaja.INGRESO, BigDecimal.TEN, "  "))
                .containsEntry("motivo", "Escribí el motivo del movimiento.");
        assertThat(MovimientoCaja.validar(TipoMovimientoCaja.INGRESO, BigDecimal.TEN,
                "x".repeat(MovimientoCaja.MOTIVO_MAXIMO + 1)))
                .containsEntry("motivo", "El motivo admite hasta 120 caracteres.");
        assertThat(MovimientoCaja.validar(null, null, null))
                .containsOnlyKeys("tipo", "importe", "motivo")
                .containsEntry("tipo", "Elegí el tipo de movimiento: INGRESO, GASTO o RETIRO.");
    }

    @Test
    @DisplayName("con datos invalidos el movimiento no se puede crear")
    void noSeCreaInvalido() {
        assertThatThrownBy(() -> MovimientoCaja.de(3L, 20L, TipoMovimientoCaja.GASTO, BigDecimal.ZERO, "Luz", 9L, AHORA))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
