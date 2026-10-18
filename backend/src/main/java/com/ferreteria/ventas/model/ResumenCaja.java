package com.ferreteria.ventas.model;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Los totales de una caja (CU-10): las ventas por medio de pago (de los pagos de sus comprobantes), los ingresos,
 * gastos y retiros, y el efectivo que deberia haber. Dominio puro: las sumas las trae el servicio.
 */
public record ResumenCaja(BigDecimal efectivoInicial, Map<MedioPago, BigDecimal> ventasPorMedio, BigDecimal ingresos,
        BigDecimal gastos, BigDecimal retiros) {

    /** Completa con cero los medios y los tipos que no tuvieron movimientos. */
    public static ResumenCaja de(BigDecimal efectivoInicial, Map<MedioPago, BigDecimal> ventas,
            Map<TipoMovimientoCaja, BigDecimal> movimientos) {
        Map<MedioPago, BigDecimal> porMedio = new EnumMap<>(MedioPago.class);
        for (MedioPago medio : MedioPago.values()) {
            porMedio.put(medio, ventas.getOrDefault(medio, BigDecimal.ZERO));
        }
        return new ResumenCaja(efectivoInicial, Collections.unmodifiableMap(porMedio),
                movimientos.getOrDefault(TipoMovimientoCaja.INGRESO, BigDecimal.ZERO),
                movimientos.getOrDefault(TipoMovimientoCaja.GASTO, BigDecimal.ZERO),
                movimientos.getOrDefault(TipoMovimientoCaja.RETIRO, BigDecimal.ZERO));
    }

    public BigDecimal ventasEnEfectivo() {
        return ventasPorMedio.get(MedioPago.EFECTIVO);
    }

    public BigDecimal totalVentas() {
        return ventasPorMedio.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Inicial + ventas en efectivo + ingresos - gastos - retiros. Puede dar negativo si se cargo mal un movimiento. */
    public BigDecimal efectivoEsperado() {
        return efectivoInicial.add(ventasEnEfectivo()).add(ingresos).subtract(gastos).subtract(retiros);
    }

    /** Contado - esperado: negativo si falta plata, positivo si sobra. */
    public BigDecimal diferencia(BigDecimal efectivoContado) {
        return efectivoContado.subtract(efectivoEsperado());
    }
}
