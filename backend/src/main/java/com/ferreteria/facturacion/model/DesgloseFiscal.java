package com.ferreteria.facturacion.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Los importes de un comprobante como los pide WSFEv1, desglosados por alícuota a nivel comprobante (S7). Todos con
 * escala 2.
 *
 * <p>Por cada alícuota se suma el total con IVA (T) de sus renglones; BaseImp = redondeo(T / (1 + a), 2) e
 * Importe = T - BaseImp. ImpTotal es la suma de T (lo cobrado), ImpNeto la de BaseImp e ImpIVA la de Importe; no hay
 * conceptos no gravados, exentos ni tributos, así que ImpTotConc, ImpOpEx e ImpTrib van en 0. Desglosar renglón por
 * renglón y sumar, como hace hoy el ticket, acumula un centavo de redondeo por renglón y ARCA lo rechaza (10051).
 *
 * <p>En la C no se discrimina IVA: ImpNeto es el total, ImpIVA es 0 y no hay array de alícuotas. Una alícuota cuyo
 * total suma 0 no se informa: no hay base que declarar.
 *
 * @param impTotal {@code ImpTotal}: lo cobrado
 * @param impTotConc {@code ImpTotConc}: siempre 0
 * @param impNeto {@code ImpNeto}: la suma de las bases imponibles (en la C, el total)
 * @param impOpEx {@code ImpOpEx}: siempre 0
 * @param impTrib {@code ImpTrib}: siempre 0
 * @param impIva {@code ImpIVA}: la suma del IVA de cada alícuota (en la C, 0)
 * @param alicuotas el array {@code AlicIva}, ordenado por {@code Id} (vacío en la C)
 */
public record DesgloseFiscal(BigDecimal impTotal, BigDecimal impTotConc, BigDecimal impNeto, BigDecimal impOpEx,
        BigDecimal impTrib, BigDecimal impIva, List<AlicuotaDesglosada> alicuotas) {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);
    private static final BigDecimal CERO = BigDecimal.ZERO.setScale(2);

    /**
     * HALF_EVEN, el mismo modo con que el simulador valida 10051, así el desglose y su validación redondean igual por
     * construcción. Con las alícuotas de ARCA el modo no cambia ningún resultado: 1 + a es 1, 121/100, 221/200,
     * 127/100, 21/20 o 41/40, siempre con numerador impar, y T / (1 + a) cae justo en medio centavo solo si
     * 2 x centavos x denominador = impar x numerador, que es par contra impar. Sin empates, HALF_UP daría lo mismo.
     */
    private static final RoundingMode REDONDEO = RoundingMode.HALF_EVEN;

    public DesgloseFiscal {
        alicuotas = List.copyOf(alicuotas);
    }

    /** El desglose del comprobante de letra {@code letra} con esos renglones. */
    public static DesgloseFiscal de(Letra letra, List<RenglonFiscal> renglones) {
        Objects.requireNonNull(letra, "Falta la letra del comprobante.");
        Objects.requireNonNull(renglones, "Faltan los renglones del comprobante.");

        Map<Alicuota, BigDecimal> totalPorAlicuota = new EnumMap<>(Alicuota.class);
        for (RenglonFiscal renglon : renglones) {
            totalPorAlicuota.merge(renglon.alicuota(), renglon.total(), BigDecimal::add);
        }
        BigDecimal impTotal = totalPorAlicuota.values().stream().reduce(CERO, BigDecimal::add);

        if (!letra.discriminaIva()) {
            return new DesgloseFiscal(impTotal, CERO, impTotal, CERO, CERO, CERO, List.of());
        }

        List<AlicuotaDesglosada> alicuotas = new ArrayList<>();
        BigDecimal impNeto = CERO;
        BigDecimal impIva = CERO;
        // EnumMap recorre en el orden de declaración de Alicuota, que es el de su Id.
        for (Map.Entry<Alicuota, BigDecimal> entrada : totalPorAlicuota.entrySet()) {
            BigDecimal total = entrada.getValue();
            if (total.signum() == 0) {
                continue;
            }
            BigDecimal baseImp = total.multiply(CIEN).divide(CIEN.add(entrada.getKey().porcentaje()), 2, REDONDEO);
            BigDecimal importe = total.subtract(baseImp);
            alicuotas.add(new AlicuotaDesglosada(entrada.getKey(), baseImp, importe));
            impNeto = impNeto.add(baseImp);
            impIva = impIva.add(importe);
        }
        return new DesgloseFiscal(impTotal, CERO, impNeto, CERO, CERO, impIva, alicuotas);
    }
}
