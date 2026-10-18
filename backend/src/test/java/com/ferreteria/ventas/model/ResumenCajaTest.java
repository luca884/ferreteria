package com.ferreteria.ventas.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** CU-10: los totales de la caja y el efectivo esperado. Dominio puro. */
class ResumenCajaTest {

    private static BigDecimal bd(String valor) {
        return new BigDecimal(valor);
    }

    @Test
    @DisplayName("esperado = inicial + ventas en efectivo + ingresos - gastos - retiros; los otros medios no suman al efectivo")
    void efectivoEsperado() {
        ResumenCaja resumen = ResumenCaja.de(bd("5000.00"),
                Map.of(MedioPago.EFECTIVO, bd("12000.00"), MedioPago.DEBITO, bd("8000.00"),
                        MedioPago.CREDITO, bd("3000.00"), MedioPago.TRANSFERENCIA, bd("1500.00")),
                Map.of(TipoMovimientoCaja.INGRESO, bd("700.00"), TipoMovimientoCaja.GASTO, bd("1200.00"),
                        TipoMovimientoCaja.RETIRO, bd("10000.00")));

        assertThat(resumen.ventasEnEfectivo()).isEqualByComparingTo("12000");
        assertThat(resumen.totalVentas()).isEqualByComparingTo("24500");
        assertThat(resumen.ingresos()).isEqualByComparingTo("700");
        assertThat(resumen.gastos()).isEqualByComparingTo("1200");
        assertThat(resumen.retiros()).isEqualByComparingTo("10000");
        assertThat(resumen.efectivoEsperado()).isEqualByComparingTo("6500");
    }

    @Test
    @DisplayName("sin ventas ni movimientos, cada medio y cada tipo esta en cero y el esperado es el inicial")
    void sinMovimientos() {
        ResumenCaja resumen = ResumenCaja.de(bd("300.00"), Map.of(), Map.of());

        assertThat(resumen.ventasPorMedio()).containsOnlyKeys(MedioPago.values());
        assertThat(resumen.ventasPorMedio().values()).allSatisfy(v -> assertThat(v).isEqualByComparingTo("0"));
        assertThat(resumen.ingresos()).isEqualByComparingTo("0");
        assertThat(resumen.gastos()).isEqualByComparingTo("0");
        assertThat(resumen.retiros()).isEqualByComparingTo("0");
        assertThat(resumen.efectivoEsperado()).isEqualByComparingTo("300");
    }

    @Test
    @DisplayName("la diferencia es contado - esperado: negativa si falta plata, positiva si sobra")
    void diferencia() {
        ResumenCaja resumen = ResumenCaja.de(bd("1000.00"), Map.of(MedioPago.EFECTIVO, bd("500.00")), Map.of());

        assertThat(resumen.diferencia(bd("1400.00"))).isEqualByComparingTo("-100");
        assertThat(resumen.diferencia(bd("1550.00"))).isEqualByComparingTo("50");
        assertThat(resumen.diferencia(bd("1500"))).isEqualByComparingTo("0");
    }
}
