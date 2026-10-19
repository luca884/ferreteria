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
    @DisplayName("CC-025: lo vendido a cuenta corriente es un renglon informativo: suma al total de ventas, no al efectivo esperado")
    void cuentaCorrienteEsInformativa() {
        ResumenCaja resumen = ResumenCaja.de(bd("5000.00"),
                Map.of(MedioPago.EFECTIVO, bd("1000.00"), MedioPago.CUENTA_CORRIENTE, bd("2000.00")), Map.of());

        assertThat(resumen.ventasPorMedio().get(MedioPago.CUENTA_CORRIENTE)).isEqualByComparingTo("2000");
        assertThat(resumen.totalVentas()).isEqualByComparingTo("3000");
        assertThat(resumen.efectivoEsperado()).isEqualByComparingTo("6000");
        assertThat(resumen.diferencia(bd("6000.00"))).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("CC-025: la cuenta corriente no entra en el efectivo esperado ni con ingresos, gastos y retiros")
    void cuentaCorrienteNoMuevaElEfectivoConMovimientos() {
        ResumenCaja resumen = ResumenCaja.de(bd("5000.00"),
                Map.of(MedioPago.EFECTIVO, bd("1000.00"), MedioPago.CUENTA_CORRIENTE, bd("9999.00")),
                Map.of(TipoMovimientoCaja.INGRESO, bd("700.00"), TipoMovimientoCaja.GASTO, bd("1200.00"),
                        TipoMovimientoCaja.RETIRO, bd("2000.00")));

        // 5000 + 1000 + 700 - 1200 - 2000 = 3500, igual con o sin los 9999 a cuenta corriente
        assertThat(resumen.efectivoEsperado()).isEqualByComparingTo("3500");
        assertThat(resumen.totalVentas()).isEqualByComparingTo("10999");
    }

    @Test
    @DisplayName("CC-025: sin ventas a cuenta corriente la clave esta presente con cero")
    void sinVentasACuentaCorrienteLaClaveEstaEnCero() {
        ResumenCaja resumen = ResumenCaja.de(bd("5000.00"), Map.of(MedioPago.EFECTIVO, bd("1000.00")), Map.of());

        assertThat(resumen.ventasPorMedio()).containsKey(MedioPago.CUENTA_CORRIENTE);
        assertThat(resumen.ventasPorMedio().get(MedioPago.CUENTA_CORRIENTE)).isEqualByComparingTo("0");
        assertThat(resumen.totalVentas()).isEqualByComparingTo("1000");
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
