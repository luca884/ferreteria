package com.ferreteria.catalogo.model;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Cambio de costo y/o de precio final de un articulo. Los valores "anterior" son null cuando el
 * articulo se esta creando.
 */
public record CambioPrecio(
        BigDecimal costoAnterior,
        BigDecimal costoNuevo,
        BigDecimal precioAnterior,
        BigDecimal precioNuevo) {

    /**
     * Devuelve el cambio solo si el costo o el precio final son distintos. Se compara con
     * compareTo, no con equals: 170 y 170.00 son el mismo valor. Un valor anterior null cuenta
     * como cambio (asi el alta genera su entrada de historial).
     */
    public static Optional<CambioPrecio> entre(
            BigDecimal costoAnterior,
            BigDecimal costoNuevo,
            BigDecimal precioAnterior,
            BigDecimal precioNuevo) {
        if (distintos(costoAnterior, costoNuevo) || distintos(precioAnterior, precioNuevo)) {
            return Optional.of(new CambioPrecio(costoAnterior, costoNuevo, precioAnterior, precioNuevo));
        }
        return Optional.empty();
    }

    private static boolean distintos(BigDecimal anterior, BigDecimal nuevo) {
        if (anterior == null || nuevo == null) {
            return anterior != nuevo;
        }
        return anterior.compareTo(nuevo) != 0;
    }
}
