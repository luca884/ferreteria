package com.ferreteria.ventas.model;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Los totales de una caja (CU-10): las ventas por medio de pago (de los pagos de sus comprobantes), las cobranzas de cuenta
 * corriente por medio (de sus recibos, CC-053), los ingresos, gastos y retiros, y el efectivo que deberia haber. Las cobranzas
 * NO son ventas: no entran en {@code ventasPorMedio} ni en {@link #totalVentas()}; solo las cobradas en efectivo suman al
 * efectivo esperado (la transferencia es informativa). Dominio puro: las sumas las trae el servicio.
 */
public record ResumenCaja(BigDecimal efectivoInicial, Map<MedioPago, BigDecimal> ventasPorMedio,
        Map<MedioPago, BigDecimal> cobranzasPorMedio, BigDecimal ingresos, BigDecimal gastos, BigDecimal retiros) {

    /** Completa con cero los medios y los tipos que no tuvieron movimientos. */
    public static ResumenCaja de(BigDecimal efectivoInicial, Map<MedioPago, BigDecimal> ventas,
            Map<MedioPago, BigDecimal> cobranzas, Map<TipoMovimientoCaja, BigDecimal> movimientos) {
        return new ResumenCaja(efectivoInicial, completo(ventas), completo(cobranzas),
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

    /**
     * Inicial + ventas en efectivo + cobranzas en efectivo + ingresos - gastos - retiros. Puede dar negativo si se cargo mal
     * un movimiento.
     */
    public BigDecimal efectivoEsperado() {
        return efectivoInicial.add(ventasEnEfectivo()).add(cobranzasEnEfectivo()).add(ingresos).subtract(gastos).subtract(retiros);
    }

    /** Contado - esperado: negativo si falta plata, positivo si sobra. */
    public BigDecimal diferencia(BigDecimal efectivoContado) {
        return efectivoContado.subtract(efectivoEsperado());
    }
}
