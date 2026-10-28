package com.ferreteria.facturacion.simulador;

import java.util.Arrays;
import java.util.Optional;
import java.util.Set;

/**
 * La clase de cada {@code CbteTipo} (10007, manual 4.8 p. 40) y las condiciones frente al IVA del receptor que admite
 * (anexo, p. 202; es lo que devuelve {@code FEParamGetCondicionIvaReceptor}). El simulador no conoce la función de la letra
 * de {@code facturacion.model}: valida contra estas tablas del manual, como ARCA.
 */
enum ClaseWsfe {
    A(Set.of(1, 2, 3, 4, 5, 34, 39, 60, 63, 201, 202, 203), Set.of(1, 6, 13, 16)),
    A_CON_LEYENDA(Set.of(51, 52, 53, 54), Set.of(1, 6, 13, 16)),
    B(Set.of(6, 7, 8, 9, 10, 35, 40, 64, 61, 206, 207, 208), Set.of(4, 5, 7, 8, 9, 10, 15)),
    C(Set.of(11, 12, 13, 15, 211, 212, 213), Set.of(1, 4, 5, 6, 7, 8, 9, 10, 13, 15, 16)),
    BIENES_USADOS(Set.of(49), Set.of(5));

    /** Todas las condiciones del anexo: 1, 4, 5, 6, 7, 8, 9, 10, 13, 15 y 16. Las demás no existen (10242). */
    private static final Set<Integer> CONDICIONES = Set.of(1, 4, 5, 6, 7, 8, 9, 10, 13, 15, 16);

    private final Set<Integer> cbteTipos;
    private final Set<Integer> condiciones;

    ClaseWsfe(Set<Integer> cbteTipos, Set<Integer> condiciones) {
        this.cbteTipos = cbteTipos;
        this.condiciones = condiciones;
    }

    /** La clase del {@code CbteTipo}; vacío si no es de ninguna (10007). */
    static Optional<ClaseWsfe> de(int cbteTipo) {
        return Arrays.stream(values()).filter(c -> c.cbteTipos.contains(cbteTipo)).findFirst();
    }

    static boolean condicionExiste(int condicionIvaReceptorId) {
        return CONDICIONES.contains(condicionIvaReceptorId);
    }

    boolean admite(int condicionIvaReceptorId) {
        return condiciones.contains(condicionIvaReceptorId);
    }

    /** Clase A o «A con leyenda operación sujeta a retención». */
    boolean esA() {
        return this == A || this == A_CON_LEYENDA;
    }
}
