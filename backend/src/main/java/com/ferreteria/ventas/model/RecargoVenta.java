package com.ferreteria.ventas.model;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * El recargo de la forma de pago (GDS-11, RC-004). El de un pago es su monto por el % de la forma, a centavos HALF_UP.
 * El de la venta (la suma de los de sus pagos) se reparte entre los renglones en proporcion a su total ya descontado:
 * cada uno se queda con la parte entera en centavos y los centavos que sobran van de a uno a los restos mayores (a
 * igual resto, al primer renglon), asi ningun recargo es negativo y la suma da exacto. Dominio puro.
 */
public final class RecargoVenta {

    private static final int ESCALA = 2;
    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    private RecargoVenta() {}

    /** Lo que suma la forma de pago a la parte de la venta que cubre. */
    public static BigDecimal deCobro(BigDecimal monto, BigDecimal porcentaje) {
        return monto.multiply(porcentaje).divide(CIEN, ESCALA, RoundingMode.HALF_UP);
    }

    public static List<RenglonVenta> repartir(List<RenglonVenta> renglones, BigDecimal recargo) {
        if (recargo.signum() < 0) {
            throw new IllegalArgumentException("El recargo no puede ser negativo: " + recargo);
        }
        if (recargo.signum() == 0) {
            return renglones;
        }
        BigDecimal suma = renglones.stream().map(RenglonVenta::totalSinRecargo).reduce(BigDecimal.ZERO,
                BigDecimal::add);
        if (suma.signum() == 0) {
            throw new IllegalArgumentException("No hay un total sobre el que repartir el recargo.");
        }
        int n = renglones.size();
        BigInteger recargoCentavos = centavos(recargo);
        BigInteger sumaCentavos = centavos(suma);
        BigInteger[] partes = new BigInteger[n];
        BigInteger[] restos = new BigInteger[n];
        BigInteger repartido = BigInteger.ZERO;
        for (int i = 0; i < n; i++) {
            BigInteger[] cociente = recargoCentavos.multiply(centavos(renglones.get(i).totalSinRecargo()))
                    .divideAndRemainder(sumaCentavos);
            partes[i] = cociente[0];
            restos[i] = cociente[1];
            repartido = repartido.add(partes[i]);
        }
        int sobrantes = recargoCentavos.subtract(repartido).intValueExact();
        List<Integer> porResto = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            porResto.add(i);
        }
        porResto.sort(Comparator.comparing((Integer i) -> restos[i]).reversed()
                .thenComparing(Comparator.naturalOrder()));
        for (int k = 0; k < sobrantes; k++) {
            int i = porResto.get(k);
            partes[i] = partes[i].add(BigInteger.ONE);
        }
        List<RenglonVenta> resultado = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            resultado.add(renglones.get(i).conRecargo(new BigDecimal(partes[i], ESCALA)));
        }
        return resultado;
    }

    private static BigInteger centavos(BigDecimal importe) {
        return importe.setScale(ESCALA, RoundingMode.UNNECESSARY).unscaledValue();
    }
}
