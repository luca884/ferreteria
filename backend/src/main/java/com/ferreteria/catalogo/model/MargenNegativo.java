package com.ferreteria.catalogo.model;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;

/**
 * Aviso de margen negativo de un articulo con precio manual (CU-14 punto 2): el costo en pesos supera el precio sin
 * IVA. Lo comparten las vistas previas de la importacion (CU-04), la actualizacion masiva (CU-05) y las compras (CU-13).
 * Es un aviso, no impide confirmar. Dominio puro, sin estado.
 */
public final class MargenNegativo {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    private MargenNegativo() {
    }

    /**
     * El mensaje si costo x cotizacion es mayor que el precio final sin IVA, comparado en forma exacta (costo en pesos x
     * (100 + iva) contra precio x 100); margen 0 no avisa. Quien lo llama decide si el articulo tiene precio manual.
     */
    public static Optional<String> mensaje(BigDecimal costo, BigDecimal cotizacion, AlicuotaIva iva,
                                           BigDecimal precioFinal) {
        BigDecimal costoEnPesos = costo.multiply(cotizacion);
        BigDecimal conIva = CIEN.add(iva.porcentaje());
        if (costoEnPesos.multiply(conIva).compareTo(precioFinal.multiply(CIEN)) <= 0) {
            return Optional.empty();
        }
        BigDecimal sinIva = precioFinal.multiply(CIEN).divide(conIva, 2, RoundingMode.HALF_UP);
        return Optional.of("Margen negativo: el costo (" + ImporteArgentino.formato(costoEnPesos, Moneda.ARS)
            + ") supera el precio sin IVA (" + ImporteArgentino.formato(sinIva, Moneda.ARS)
            + "). Revisá el precio manual.");
    }

    /** La advertencia de una fila: la que ya tenia (variacion fuerte) y despues la de margen negativo, o null. */
    public static String junto(Optional<String> advertencia, Optional<String> margenNegativo) {
        if (advertencia.isPresent() && margenNegativo.isPresent()) {
            return advertencia.get() + " " + margenNegativo.get();
        }
        return advertencia.or(() -> margenNegativo).orElse(null);
    }
}
