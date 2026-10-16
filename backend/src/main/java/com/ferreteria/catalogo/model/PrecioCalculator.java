package com.ferreteria.catalogo.model;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Calculo del precio final de un articulo. Dominio puro: sin Spring ni acceso a datos.
 * Todos los porcentajes se expresan como porcentaje (35.00 significa 35 %).
 */
public final class PrecioCalculator {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    private PrecioCalculator() {
    }

    /**
     * Margen del articulo; si es null, el del rubro; si es null, el de la ferreteria.
     * Un margen de 0 no es null y se respeta.
     */
    public static BigDecimal margenEfectivo(BigDecimal margenArticulo, BigDecimal margenRubro, BigDecimal margenDefecto) {
        if (margenArticulo != null) {
            return margenArticulo;
        }
        if (margenRubro != null) {
            return margenRubro;
        }
        if (margenDefecto != null) {
            return margenDefecto;
        }
        throw new IllegalArgumentException("No hay ningún margen para calcular el precio.");
    }

    /**
     * costo x (1 + margen/100) x (1 + iva/100), redondeado hacia arriba al multiplo. Escala 2.
     * El costo va sin IVA. Un multiplo null o menor o igual a 0 significa "sin multiplo":
     * se redondea hacia arriba al centavo.
     */
    public static BigDecimal precioFinal(BigDecimal costo, BigDecimal margenPorcentaje, AlicuotaIva iva, BigDecimal multiplo) {
        return precioFinal(costo, margenPorcentaje, iva, multiplo, BigDecimal.ONE);
    }

    /**
     * Igual que la de cuatro parametros, para un costo en otra moneda: costo x cotizacion x (1 + margen/100) x
     * (1 + iva/100), redondeado hacia arriba al multiplo. La cotizacion (pesos por unidad de la moneda del
     * costo, por ejemplo 1000.5000) entra como un factor exacto mas, antes del unico redondeo. Para un costo
     * en pesos vale 1.
     */
    public static BigDecimal precioFinal(
            BigDecimal costo, BigDecimal margenPorcentaje, AlicuotaIva iva, BigDecimal multiplo, BigDecimal cotizacion) {
        if (cotizacion == null || cotizacion.signum() <= 0) {
            throw new IllegalArgumentException("La cotización debe ser un valor mayor a 0.");
        }
        if (costo == null || costo.signum() < 0) {
            throw new IllegalArgumentException("El costo debe ser un valor mayor o igual a 0.");
        }
        if (margenPorcentaje == null || margenPorcentaje.signum() < 0) {
            throw new IllegalArgumentException("El margen debe ser un valor mayor o igual a 0.");
        }
        if (iva == null) {
            throw new IllegalArgumentException("La alícuota de IVA es obligatoria.");
        }

        // Exacto: multiplicar nunca pierde precision, y no hay division hasta el redondeo final.
        BigDecimal bruto = costo
                .multiply(cotizacion)
                .multiply(CIEN.add(margenPorcentaje))
                .multiply(CIEN.add(iva.porcentaje()))
                .movePointLeft(4);

        if (multiplo == null || multiplo.signum() <= 0) {
            return bruto.setScale(2, RoundingMode.CEILING);
        }
        return bruto.divide(multiplo, 0, RoundingMode.CEILING)
                .multiply(multiplo)
                .setScale(2, RoundingMode.CEILING);
    }
}
