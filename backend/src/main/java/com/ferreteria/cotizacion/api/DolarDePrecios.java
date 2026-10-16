package com.ferreteria.cotizacion.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Puerto de {@code cotizacion} hacia {@code catalogo} (lo vigila ModulosArchTest), igual que {@code ventas} usa
 * {@code catalogo.api}: solo tipos del JDK. Todo se resuelve con la ferreteria del request ({@code TenantContext}).
 */
public interface DolarDePrecios {

    /** True si la cotizacion de la ferreteria esta activada (decision 17). */
    boolean activa();

    /**
     * El dolar de los precios en USD: el vigente (el que fijo el ultimo recalculo) o, si la ferreteria nunca fijo uno,
     * el ultimo guardado de su tipo y lado. Vacio si no hay ninguno. Nunca cae en otro tipo de dolar.
     */
    Optional<BigDecimal> vigente();

    /**
     * «Recalcular ahora»: fija el ultimo valor guardado como vigente y lo devuelve.
     *
     * @throws com.ferreteria.common.exception.ConflictException (409) si la cotizacion esta apagada o no hay ningun valor
     */
    BigDecimal fijarUltima();

    /**
     * Para el job diario: reclama el recalculo del dia con un UPDATE condicional (idempotente) y, si le toca, fija el
     * ultimo valor guardado como vigente y lo devuelve. Vacio si todavia no es la hora, ya se hizo hoy, la cotizacion
     * esta apagada o no hay ningun valor (en ese caso no marca el dia).
     *
     * @param ahoraBuenosAires la fecha y hora actuales en Buenos Aires
     */
    Optional<BigDecimal> reclamarRecalculoDelDia(LocalDateTime ahoraBuenosAires);
}
