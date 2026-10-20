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
                Map.of(), Map.of(),
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
        ResumenCaja resumen = ResumenCaja.de(bd("300.00"), Map.of(), Map.of(), Map.of(), Map.of());

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
                Map.of(MedioPago.EFECTIVO, bd("1000.00"), MedioPago.CUENTA_CORRIENTE, bd("2000.00")), Map.of(), Map.of(), Map.of());

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
                Map.of(), Map.of(),
                Map.of(TipoMovimientoCaja.INGRESO, bd("700.00"), TipoMovimientoCaja.GASTO, bd("1200.00"),
                        TipoMovimientoCaja.RETIRO, bd("2000.00")));

        // 5000 + 1000 + 700 - 1200 - 2000 = 3500, igual con o sin los 9999 a cuenta corriente
        assertThat(resumen.efectivoEsperado()).isEqualByComparingTo("3500");
        assertThat(resumen.totalVentas()).isEqualByComparingTo("10999");
    }

    @Test
    @DisplayName("CC-025: sin ventas a cuenta corriente la clave esta presente con cero")
    void sinVentasACuentaCorrienteLaClaveEstaEnCero() {
        ResumenCaja resumen = ResumenCaja.de(bd("5000.00"), Map.of(MedioPago.EFECTIVO, bd("1000.00")), Map.of(), Map.of(), Map.of());

        assertThat(resumen.ventasPorMedio()).containsKey(MedioPago.CUENTA_CORRIENTE);
        assertThat(resumen.ventasPorMedio().get(MedioPago.CUENTA_CORRIENTE)).isEqualByComparingTo("0");
        assertThat(resumen.totalVentas()).isEqualByComparingTo("1000");
    }

    @Test
    @DisplayName("la diferencia es contado - esperado: negativa si falta plata, positiva si sobra")
    void diferencia() {
        ResumenCaja resumen = ResumenCaja.de(bd("1000.00"), Map.of(MedioPago.EFECTIVO, bd("500.00")), Map.of(), Map.of(), Map.of());

        assertThat(resumen.diferencia(bd("1400.00"))).isEqualByComparingTo("-100");
        assertThat(resumen.diferencia(bd("1550.00"))).isEqualByComparingTo("50");
        assertThat(resumen.diferencia(bd("1500"))).isEqualByComparingTo("0");
    }

    // ---- CCT3-303 (CC-053, CJ-R-012, CJ-R-013): las cobranzas de cuenta corriente ----

    @Test
    @DisplayName("CC-053: ejemplo del spec: cobranza 700 en efectivo y 300 por transferencia suma 700 al esperado; totalVentas no cambia")
    void cobranzasEnEfectivoSumanAlEsperado() {
        ResumenCaja resumen = ResumenCaja.de(bd("5000.00"), Map.of(MedioPago.EFECTIVO, bd("1000.00")),
                Map.of(MedioPago.EFECTIVO, bd("700.00"), MedioPago.TRANSFERENCIA, bd("300.00")), Map.of(), Map.of());

        assertThat(resumen.efectivoEsperado()).isEqualByComparingTo("6700");
        assertThat(resumen.diferencia(bd("6700.00"))).isEqualByComparingTo("0");
        assertThat(resumen.totalVentas()).isEqualByComparingTo("1000");
        assertThat(resumen.cobranzasEnEfectivo()).isEqualByComparingTo("700");
        assertThat(resumen.totalCobranzas()).isEqualByComparingTo("1000");
        assertThat(resumen.cobranzasPorMedio().get(MedioPago.EFECTIVO)).isEqualByComparingTo("700");
        assertThat(resumen.cobranzasPorMedio().get(MedioPago.TRANSFERENCIA)).isEqualByComparingTo("300");
    }

    @Test
    @DisplayName("CJ-R-013: inicial 5.000, ventas efectivo 1.800, cobranza efectivo 700, ingreso 200, gasto 300, retiro 1.000 → esperado 6.400")
    void ejemploCompletoDeCajaConCobranza() {
        ResumenCaja resumen = ResumenCaja.de(bd("5000.00"), Map.of(MedioPago.EFECTIVO, bd("1800.00")),
                Map.of(MedioPago.EFECTIVO, bd("700.00")), Map.of(),
                Map.of(TipoMovimientoCaja.INGRESO, bd("200.00"), TipoMovimientoCaja.GASTO, bd("300.00"),
                        TipoMovimientoCaja.RETIRO, bd("1000.00")));

        assertThat(resumen.efectivoEsperado()).isEqualByComparingTo("6400");
        assertThat(resumen.diferencia(bd("6400.00"))).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("CJ-R-012 sin cobranzas: esperado 5.700 y diferencia −50 con contado 5.650 (el ejemplo no cambia)")
    void ejemploSinCobranzasNoCambia() {
        ResumenCaja resumen = ResumenCaja.de(bd("5000.00"), Map.of(MedioPago.EFECTIVO, bd("1000.00")), Map.of(), Map.of(),
                Map.of(TipoMovimientoCaja.INGRESO, bd("200.00"), TipoMovimientoCaja.GASTO, bd("500.00")));

        assertThat(resumen.efectivoEsperado()).isEqualByComparingTo("5700");
        assertThat(resumen.diferencia(bd("5650.00"))).isEqualByComparingTo("-50");
    }

    @Test
    @DisplayName("sin cobranzas, todos los medios de cobranzasPorMedio están presentes con cero")
    void sinCobranzasLasClavesEstanEnCero() {
        ResumenCaja resumen = ResumenCaja.de(bd("300.00"), Map.of(), Map.of(), Map.of(), Map.of());

        assertThat(resumen.cobranzasPorMedio()).containsOnlyKeys(MedioPago.values());
        assertThat(resumen.cobranzasPorMedio().values()).allSatisfy(v -> assertThat(v).isEqualByComparingTo("0"));
        assertThat(resumen.cobranzasEnEfectivo()).isEqualByComparingTo("0");
        assertThat(resumen.totalCobranzas()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("la transferencia cobrada es informativa: no entra al efectivo esperado, y las cobranzas no entran a ventasPorMedio")
    void transferenciaNoEntraAlEsperadoNiLasCobranzasALasVentas() {
        ResumenCaja resumen = ResumenCaja.de(bd("100.00"), Map.of(MedioPago.EFECTIVO, bd("50.00")),
                Map.of(MedioPago.TRANSFERENCIA, bd("999.00")), Map.of(), Map.of());

        assertThat(resumen.efectivoEsperado()).isEqualByComparingTo("150");
        assertThat(resumen.ventasPorMedio().get(MedioPago.TRANSFERENCIA)).isEqualByComparingTo("0");
        assertThat(resumen.totalVentas()).isEqualByComparingTo("50");
        assertThat(resumen.totalCobranzas()).isEqualByComparingTo("999");
    }

    // ---- CCT3B1-302 (CC-081, CC-082, CJ-R-012, CJ-R-013): las anulaciones, séptimo componente ----

    @Test
    @DisplayName("CJ-R-013: inicial 5.000, ventas ef. 1.800, cobranza ef. 700 anulada, ingreso 200, gasto 300, retiro 1.000, contado 5.700 → esperado 5.700, dif 0")
    void ejemploCompletoConAnulacion() {
        ResumenCaja resumen = ResumenCaja.de(bd("5000.00"), Map.of(MedioPago.EFECTIVO, bd("1800.00")),
                Map.of(MedioPago.EFECTIVO, bd("700.00")), Map.of(MedioPago.EFECTIVO, bd("700.00")),
                Map.of(TipoMovimientoCaja.INGRESO, bd("200.00"), TipoMovimientoCaja.GASTO, bd("300.00"),
                        TipoMovimientoCaja.RETIRO, bd("1000.00")));

        assertThat(resumen.efectivoEsperado()).isEqualByComparingTo("5700");
        assertThat(resumen.diferencia(bd("5700.00"))).isEqualByComparingTo("0");
        assertThat(resumen.anulacionesEnEfectivo()).isEqualByComparingTo("700");
        assertThat(resumen.totalAnulaciones()).isEqualByComparingTo("700");
        // las cobranzas siguen brutas
        assertThat(resumen.cobranzasEnEfectivo()).isEqualByComparingTo("700");
    }

    @Test
    @DisplayName("una anulación por transferencia 300 es informativa: el esperado no cambia y anulaciones.TRANSFERENCIA es 300")
    void anulacionPorTransferenciaEsInformativa() {
        ResumenCaja sin = ResumenCaja.de(bd("1000.00"), Map.of(), Map.of(MedioPago.TRANSFERENCIA, bd("300.00")), Map.of(), Map.of());
        ResumenCaja con = ResumenCaja.de(bd("1000.00"), Map.of(), Map.of(MedioPago.TRANSFERENCIA, bd("300.00")),
                Map.of(MedioPago.TRANSFERENCIA, bd("300.00")), Map.of());

        assertThat(con.efectivoEsperado()).isEqualByComparingTo(sin.efectivoEsperado());
        assertThat(con.efectivoEsperado()).isEqualByComparingTo("1000");
        assertThat(con.anulacionesPorMedio().get(MedioPago.TRANSFERENCIA)).isEqualByComparingTo("300");
        assertThat(con.anulacionesEnEfectivo()).isEqualByComparingTo("0");
        assertThat(con.totalAnulaciones()).isEqualByComparingTo("300");
    }

    @Test
    @DisplayName("mismo día: cobrar +T y anular −T deja el esperado de antes de cobrar; anular un cobro de otra caja resta T")
    void mismoDiaNeto0YOtroDiaResta() {
        ResumenCaja antes = ResumenCaja.de(bd("3000.00"), Map.of(), Map.of(), Map.of(), Map.of());
        ResumenCaja mismoDia = ResumenCaja.de(bd("3000.00"), Map.of(), Map.of(MedioPago.EFECTIVO, bd("700.00")),
                Map.of(MedioPago.EFECTIVO, bd("700.00")), Map.of());
        // el cobro era de ayer (esta caja no lo tiene en cobranzas), la anulación sí cae acá
        ResumenCaja deAyer = ResumenCaja.de(bd("3000.00"), Map.of(), Map.of(), Map.of(MedioPago.EFECTIVO, bd("700.00")), Map.of());

        assertThat(mismoDia.efectivoEsperado()).isEqualByComparingTo(antes.efectivoEsperado());
        assertThat(deAyer.efectivoEsperado()).isEqualByComparingTo("2300");
    }

    @Test
    @DisplayName("todos los MedioPago están presentes en anulaciones con 0, y las anulaciones no entran en ventasPorMedio ni totalVentas")
    void anulacionesCompletasYNoSonVentas() {
        ResumenCaja vacio = ResumenCaja.de(bd("300.00"), Map.of(), Map.of(), Map.of(), Map.of());
        ResumenCaja con = ResumenCaja.de(bd("300.00"), Map.of(MedioPago.EFECTIVO, bd("100.00")), Map.of(),
                Map.of(MedioPago.EFECTIVO, bd("40.00")), Map.of());

        assertThat(vacio.anulacionesPorMedio()).containsOnlyKeys(MedioPago.values());
        assertThat(vacio.anulacionesPorMedio().values()).allSatisfy(v -> assertThat(v).isEqualByComparingTo("0"));
        assertThat(vacio.totalAnulaciones()).isEqualByComparingTo("0");
        assertThat(con.totalVentas()).isEqualByComparingTo("100");
        assertThat(con.ventasPorMedio().get(MedioPago.EFECTIVO)).isEqualByComparingTo("100");
        assertThat(con.efectivoEsperado()).isEqualByComparingTo("360");
    }
}
