package com.ferreteria.ventas.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Un renglon del ticket ya calculado (R-005). El precio es el final, con IVA incluido. El bruto es precio x cantidad a
 * 2 decimales. El total es el bruto con el descuento del renglon y despues con el descuento global (GDS-12), cada uno
 * redondeado a centavos: el global se prorratea aplicandolo a cada renglon, asi el total del comprobante es exactamente
 * la suma de sus renglones (GDS-37). El neto sale de ese total (total x 100 / (100 + %)) y el IVA es la diferencia, asi
 * que neto + IVA = total siempre. Todo HALF_UP, nunca HALF_EVEN.
 * El recargo de la forma de pago (GDS-11) llega despues, ya repartido ({@link RecargoVenta}): se suma al total y el
 * neto y el IVA se sacan del total nuevo con la alicuota del renglon.
 */
public record RenglonVenta(BigDecimal precioUnitario, BigDecimal cantidad, BigDecimal bruto,
        BigDecimal descuentoPorcentaje, BigDecimal descuentoGlobalPorcentaje, BigDecimal total, BigDecimal neto,
        BigDecimal iva, BigDecimal porcentajeIva, BigDecimal recargo) {

    private static final int ESCALA = 2;
    private static final BigDecimal CIEN = BigDecimal.valueOf(100);
    /** El descuento tiene que dejar algo por cobrar: 100 % no se admite. */
    public static final BigDecimal DESCUENTO_MAXIMO = new BigDecimal("99.99");

    public static RenglonVenta de(BigDecimal precioUnitario, BigDecimal cantidad, BigDecimal porcentajeIva) {
        return de(precioUnitario, cantidad, porcentajeIva, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    public static RenglonVenta de(BigDecimal precioUnitario, BigDecimal cantidad, BigDecimal porcentajeIva,
            BigDecimal descuentoPorcentaje, BigDecimal descuentoGlobalPorcentaje) {
        validar(descuentoPorcentaje);
        validar(descuentoGlobalPorcentaje);
        BigDecimal bruto = precioUnitario.multiply(cantidad).setScale(ESCALA, RoundingMode.HALF_UP);
        BigDecimal conDescuentoDelRenglon = descontar(bruto, descuentoPorcentaje);
        BigDecimal total = descontar(conDescuentoDelRenglon, descuentoGlobalPorcentaje);
        BigDecimal neto = netoDe(total, porcentajeIva);
        return new RenglonVenta(precioUnitario, cantidad, bruto, descuentoPorcentaje, descuentoGlobalPorcentaje,
                total, neto, total.subtract(neto), porcentajeIva, BigDecimal.ZERO.setScale(ESCALA));
    }

    /** El total sin el recargo: sobre este importe se reparte el recargo de la venta. */
    public BigDecimal totalSinRecargo() {
        return total.subtract(recargo);
    }

    /**
     * El mismo renglon con ese recargo (el que tenia se reemplaza, no se acumula) y neto e IVA del total nuevo. Negativo
     * es el descuento de la forma de pago: puede dejar el renglon en 0, nunca por debajo.
     */
    public RenglonVenta conRecargo(BigDecimal nuevoRecargo) {
        BigDecimal nuevoTotal = totalSinRecargo().add(nuevoRecargo);
        if (nuevoTotal.signum() < 0) {
            throw new IllegalArgumentException("El descuento de la forma de pago supera el renglon: " + nuevoRecargo);
        }
        BigDecimal nuevoNeto = netoDe(nuevoTotal, porcentajeIva);
        return new RenglonVenta(precioUnitario, cantidad, bruto, descuentoPorcentaje, descuentoGlobalPorcentaje,
                nuevoTotal, nuevoNeto, nuevoTotal.subtract(nuevoNeto), porcentajeIva, nuevoRecargo);
    }

    private static BigDecimal netoDe(BigDecimal total, BigDecimal porcentajeIva) {
        return total.multiply(CIEN).divide(CIEN.add(porcentajeIva), ESCALA, RoundingMode.HALF_UP);
    }

    private static BigDecimal descontar(BigDecimal importe, BigDecimal porcentaje) {
        return importe.multiply(CIEN.subtract(porcentaje)).divide(CIEN, ESCALA, RoundingMode.HALF_UP);
    }

    private static void validar(BigDecimal porcentaje) {
        if (porcentaje.signum() < 0 || porcentaje.compareTo(DESCUENTO_MAXIMO) > 0) {
            throw new IllegalArgumentException("El descuento va de 0 a 99,99 %: " + porcentaje);
        }
    }
}
