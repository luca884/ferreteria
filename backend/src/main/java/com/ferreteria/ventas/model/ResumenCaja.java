package com.ferreteria.ventas.model;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Los totales de una caja (CU-10): las ventas por medio de pago (de los pagos de sus comprobantes), las cobranzas de cuenta
 * corriente por medio (de sus recibos, CC-053), las anulaciones de cobranzas por medio (de los recibos cuya CAJA DE ANULACIÓN es
 * esta, CC-081), los ingresos, gastos y retiros, y el efectivo que deberia haber. Las cobranzas NO son ventas: no entran en
 * {@code ventasPorMedio} ni en {@link #totalVentas()}; solo las cobradas en efectivo suman al efectivo esperado (la
 * transferencia es informativa). Las cobranzas son BRUTAS (no cambian al anular); las anulaciones en efectivo restan del
 * esperado y las de transferencia son informativas (CC-082). Dominio puro: las sumas las trae el servicio.
 */
public record ResumenCaja(BigDecimal efectivoInicial, Map<MedioPago, BigDecimal> ventasPorMedio,
        Map<MedioPago, BigDecimal> cobranzasPorMedio, Map<MedioPago, BigDecimal> anulacionesPorMedio, BigDecimal ingresos,
        BigDecimal gastos, BigDecimal retiros) {

    /** Completa con cero los medios y los tipos que no tuvieron movimientos. */
    public static ResumenCaja de(BigDecimal efectivoInicial, Map<MedioPago, BigDecimal> ventas,
            Map<MedioPago, BigDecimal> cobranzas, Map<MedioPago, BigDecimal> anulaciones,
            Map<TipoMovimientoCaja, BigDecimal> movimientos) {
        return new ResumenCaja(efectivoInicial, completo(ventas), completo(cobranzas), completo(anulaciones),
                movimientos.getOrDefault(TipoMovimientoCaja.INGRESO, BigDecimal.ZERO),
                movimientos.getOrDefault(TipoMovimientoCaja.GASTO, BigDecimal.ZERO),
                movimientos.getOrDefault(TipoMovimientoCaja.RETIRO, BigDecimal.ZERO));
    }

    private static Map<MedioPago, BigDecimal> completo(Map<MedioPago, BigDecimal> sumas) {
        Map<MedioPago, BigDecimal> porMedio = new EnumMap<>(MedioPago.class);
        for (MedioPago medio : MedioPago.values()) {
            porMedio.put(medio, sumas.getOrDefault(medio, BigDecimal.ZERO));
        }
        return Collections.unmodifiableMap(porMedio);
    }

    public BigDecimal ventasEnEfectivo() {
        return ventasPorMedio.get(MedioPago.EFECTIVO);
    }

    public BigDecimal totalVentas() {
        return ventasPorMedio.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Lo cobrado en efectivo en recibos de cuenta corriente (suma al efectivo esperado). */
    public BigDecimal cobranzasEnEfectivo() {
        return cobranzasPorMedio.get(MedioPago.EFECTIVO);
    }

    /** Todo lo cobrado en recibos, en cualquier medio (informativo: no es venta). */
    public BigDecimal totalCobranzas() {
        return cobranzasPorMedio.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Lo anulado en efectivo en esta caja (resta del efectivo esperado, aunque el cobro sea de otra caja). */
    public BigDecimal anulacionesEnEfectivo() {
        return anulacionesPorMedio.get(MedioPago.EFECTIVO);
    }

    /** Todo lo anulado en esta caja, en cualquier medio (informativo salvo el efectivo). */
    public BigDecimal totalAnulaciones() {
        return anulacionesPorMedio.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Inicial + ventas en efectivo + cobranzas en efectivo - anulaciones en efectivo + ingresos - gastos - retiros. Puede dar
     * negativo si se cargo mal un movimiento.
     */
    public BigDecimal efectivoEsperado() {
        return efectivoInicial.add(ventasEnEfectivo()).add(cobranzasEnEfectivo()).subtract(anulacionesEnEfectivo())
                .add(ingresos).subtract(gastos).subtract(retiros);
    }

    /** Contado - esperado: negativo si falta plata, positivo si sobra. */
    public BigDecimal diferencia(BigDecimal efectivoContado) {
        return efectivoContado.subtract(efectivoEsperado());
    }
}
