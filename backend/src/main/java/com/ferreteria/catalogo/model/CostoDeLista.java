package com.ferreteria.catalogo.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Costo de un articulo a partir del precio de lista del proveedor: quita el IVA (si la lista lo
 * incluye), aplica las bonificaciones del proveedor en cascada y despues los descuentos o recargos del
 * articulo. Todo es multiplicacion exacta y hay UNA sola division final, redondeada a 2 decimales HALF_UP.
 */
public final class CostoDeLista {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);
    private static final BigDecimal CIEN_A_LA_SEXTA = BigDecimal.valueOf(1_000_000_000_000L);

    private CostoDeLista() {
    }

    public static BigDecimal costo(BigDecimal precioLista, AlicuotaIva alicuota, boolean incluyeIva,
                                   Bonificaciones bonificaciones) {
        return costo(precioLista, alicuota, incluyeIva, bonificaciones, Ajustes.NINGUNO);
    }

    public static BigDecimal costo(BigDecimal precioLista, AlicuotaIva alicuota, boolean incluyeIva,
                                   Bonificaciones bonificaciones, Ajustes ajustes) {
        Objects.requireNonNull(precioLista, "precioLista");
        Bonificaciones aplicadas = bonificaciones == null ? Bonificaciones.NINGUNA : bonificaciones;
        Ajustes sumados = ajustes == null ? Ajustes.NINGUNO : ajustes;
        // precio / (1 + iva/100) x prod(1 - bi/100) x prod(1 + aj/100)
        //   = precio x 100 x prod(100 - bi) x prod(100 + aj) / ((100 + iva) x 100^6)
        BigDecimal numerador = precioLista.multiply(CIEN).multiply(aplicadas.numerador()).multiply(sumados.numerador());
        BigDecimal base = incluyeIva ? CIEN.add(Objects.requireNonNull(alicuota, "alicuota").porcentaje()) : CIEN;
        return numerador.divide(base.multiply(CIEN_A_LA_SEXTA), 2, RoundingMode.HALF_UP);
    }
}
