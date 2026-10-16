package com.ferreteria.catalogo.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/** Alicuotas de IVA que admite ARCA para un articulo. */
public enum AlicuotaIva {
    IVA_0("0.00"),
    IVA_2_5("2.50"),
    IVA_5("5.00"),
    IVA_10_5("10.50"),
    IVA_21("21.00"),
    IVA_27("27.00");

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);
    private static final BigDecimal MEDIO_PUNTO = new BigDecimal("0.5");

    private final BigDecimal porcentaje;

    AlicuotaIva(String porcentaje) {
        this.porcentaje = new BigDecimal(porcentaje);
    }

    /** Porcentaje: 21.00 significa 21 %. */
    public BigDecimal porcentaje() {
        return porcentaje;
    }

    /** La alicuota cuyo porcentaje es exactamente {@code valor} (10,5 y 10,50 son la misma), o vacio si no es legal. */
    public static Optional<AlicuotaIva> legal(BigDecimal valor) {
        for (AlicuotaIva alicuota : values()) {
            if (alicuota.porcentaje.compareTo(valor) == 0) {
                return Optional.of(alicuota);
            }
        }
        return Optional.empty();
    }

    /**
     * La alicuota elegida entre dos precios, el calculado (con IVA / sin IVA - 1, en %) y si hay que avisar.
     *
     * @param advertir true si el calculado se aleja de la alicuota elegida mas de 0,5 puntos
     */
    public record Aproximacion(AlicuotaIva alicuota, BigDecimal calculado, boolean advertir) {}

    /**
     * La alicuota legal mas cercana a {@code (conIva / sinIva - 1) x 100}. Se elige sin dividir, comparando
     * {@code |con x 100 - sin x (100 + a)|}, asi un cociente periodico no mueve el limite. En un empate exacto
     * gana la menor, y el empate siempre pasa el umbral, asi que siempre advierte (decision 36).
     *
     * @throws IllegalArgumentException si {@code sinIva} no es positivo (no hay con que dividir)
     */
    public static Aproximacion masCercana(BigDecimal sinIva, BigDecimal conIva) {
        if (sinIva.signum() <= 0) {
            throw new IllegalArgumentException("El precio sin IVA tiene que ser mayor que cero.");
        }
        BigDecimal conPorCien = conIva.multiply(CIEN);
        AlicuotaIva elegida = null;
        BigDecimal distancia = null;
        // values() va de la menor a la mayor: con "<" estricto, un empate se queda con la menor.
        for (AlicuotaIva candidata : values()) {
            BigDecimal distanciaCandidata = conPorCien.subtract(sinIva.multiply(CIEN.add(candidata.porcentaje))).abs();
            if (distancia == null || distanciaCandidata.compareTo(distancia) < 0) {
                elegida = candidata;
                distancia = distanciaCandidata;
            }
        }
        BigDecimal calculado = conPorCien.divide(sinIva, 6, RoundingMode.HALF_UP).subtract(CIEN);
        boolean advertir = distancia.compareTo(sinIva.multiply(MEDIO_PUNTO)) > 0;
        return new Aproximacion(elegida, calculado, advertir);
    }
}
