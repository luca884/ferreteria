package com.ferreteria.catalogo.model;

import java.math.BigDecimal;
import java.util.List;

/**
 * Los ajustes sobre el precio final de un articulo (CU-14), sin limite. Todos se calculan sobre el mismo precio
 * calculado, no se encadenan: el orden no cambia el resultado (+10 % y +10 % es +20 %).
 */
public record AjustesDePrecio(List<AjustePrecio> lista) {

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    public static final AjustesDePrecio NINGUNO = new AjustesDePrecio(List.of());

    public AjustesDePrecio {
        lista = lista == null ? List.of() : List.copyOf(lista);
    }

    public boolean estaVacia() {
        return lista.isEmpty();
    }

    /**
     * Si los ajustes llevaron el precio a 0 o menos. Un precio calculado de 0 (costo 0) que los ajustes dejan en 0 no
     * cuenta: es el caso de siempre del costo 0, no algo que hicieron los ajustes.
     */
    public static boolean dejanElPrecioEnCeroOMenos(BigDecimal calculado, BigDecimal conAjustes) {
        return conAjustes.signum() <= 0 && conAjustes.compareTo(calculado) != 0;
    }

    /**
     * precio calculado x (100 + suma de los %) / 100 + suma de los montos, redondeado hacia arriba al multiplo como el
     * precio calculado ({@link PrecioCalculator#redondear}). Sin ajustes devuelve el precio tal cual. Puede dar 0 o
     * menos: lo rechaza quien lo usa.
     */
    public BigDecimal aplicar(BigDecimal precioCalculado, BigDecimal multiplo) {
        if (lista.isEmpty()) {
            return precioCalculado;
        }
        BigDecimal porcentajes = BigDecimal.ZERO;
        BigDecimal montos = BigDecimal.ZERO;
        for (AjustePrecio ajuste : lista) {
            if (ajuste.tipo() == TipoAjustePrecio.PORCENTAJE) {
                porcentajes = porcentajes.add(ajuste.valor());
            } else {
                montos = montos.add(ajuste.valor());
            }
        }
        BigDecimal bruto = precioCalculado.multiply(CIEN.add(porcentajes)).movePointLeft(2).add(montos);
        return PrecioCalculator.redondear(bruto, multiplo);
    }
}
