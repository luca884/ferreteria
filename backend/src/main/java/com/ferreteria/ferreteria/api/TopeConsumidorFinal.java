package com.ferreteria.ferreteria.api;

import java.math.BigDecimal;

/**
 * Puerto de {@code ferreteria} hacia {@code ventas}: el tope de venta a Consumidor Final sin identificar (GDS-32) de la
 * ferreteria del request ({@code TenantContext}).
 */
@FunctionalInterface
public interface TopeConsumidorFinal {

    BigDecimal tope();
}
