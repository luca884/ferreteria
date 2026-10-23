package com.ferreteria.auth.api;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Puerto de {@code auth} para {@code ventas} (CU-07): el tope de descuento del usuario que confirma. Solo tipos del JDK.
 * El usuario se busca en la ferreteria del JWT.
 */
public interface TopesDeDescuento {

    /** Vacio si el usuario no tiene tope (el Dueño nunca tiene) o no es de esta ferreteria. */
    Optional<BigDecimal> topeDe(long usuarioId);
}
