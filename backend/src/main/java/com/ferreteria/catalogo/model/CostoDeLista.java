package com.ferreteria.catalogo.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Costo de un articulo a partir del precio de lista del proveedor: quita el IVA (si la lista lo
 * incluye) y aplica las bonificaciones en cascada. Todo es multiplicacion exacta y hay UNA sola
 * division final, redondeada a 2 decimales HALF_UP.
 */
public final class CostoDeLista {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);
    private static final BigDecimal CIEN_AL_CUBO = BigDecimal.valueOf(1_000_000);

    private CostoDeLista() {
    }

    public static BigDecimal costo(BigDecimal precioLista, AlicuotaIva alicuota, boolean incluyeIva,
                                   Bonificaciones bonificaciones) {
        Objects.requireNonNull(precioLista, "precioLista");
        Bonificaciones aplicadas = bonificaciones == null ? Bonificaciones.NINGUNA : bonificaciones;
        // precio / (1 + iva/100) x prod(1 - bi/100) = precio x 100 x prod(100 - bi) / ((100 + iva) x 100^3)
        BigDecimal numerador = precioLista.multiply(CIEN).multiply(aplicadas.numerador());
        BigDecimal base = incluyeIva ? CIEN.add(Objects.requireNonNull(alicuota, "alicuota").porcentaje()) : CIEN;
        return numerador.divide(base.multiply(CIEN_AL_CUBO), 2, RoundingMode.HALF_UP);
    }
}
