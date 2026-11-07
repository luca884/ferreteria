package com.ferreteria.catalogo.model;

import com.ferreteria.common.exception.DatosInvalidosException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

/**
 * Los recargos del costo de un articulo (CU-13, S4), hasta {@value #MAXIMO}. Se suman, no se encadenan: costo real =
 * costo x (1 + suma de los % / 100), a 2 decimales HALF_UP. El costo real reemplaza al costo en el calculo del precio,
 * en el aviso de margen negativo y en el costo que copia la venta. Sin recargos el costo queda tal cual. Dominio puro.
 */
public record RecargosDeCosto(List<RecargoCosto> lista) {

    public static final int MAXIMO = 5;

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    public static final RecargosDeCosto NINGUNO = new RecargosDeCosto(List.of());

    public RecargosDeCosto {
        lista = lista == null ? List.of() : List.copyOf(lista);
        if (lista.size() > MAXIMO) {
            throw invalido("Un artículo tiene hasta 5 recargos del costo.");
        }
        if (CIEN.add(suma(lista)).signum() <= 0) {
            throw invalido("Los recargos del costo no pueden sumar -100 % o menos.");
        }
    }

    public boolean estaVacia() {
        return lista.isEmpty();
    }

    /** La suma de los % de los recargos (4.44 es 4,44 %); 0 sin recargos. */
    public BigDecimal porcentajeTotal() {
        return suma(lista);
    }

    /** El costo real: sin recargos devuelve el costo tal cual (misma escala); con recargos, a 2 decimales HALF_UP. */
    public BigDecimal aplicar(BigDecimal costo) {
        if (lista.isEmpty()) {
            return costo;
        }
        return costo.multiply(CIEN.add(suma(lista))).movePointLeft(2).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal suma(List<RecargoCosto> recargos) {
        return recargos.stream().map(RecargoCosto::porcentaje).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static DatosInvalidosException invalido(String mensaje) {
        return new DatosInvalidosException(Map.of("recargosCosto", mensaje));
    }
}
