package com.ferreteria.catalogo.model;

import java.math.BigDecimal;

/**
 * Lo que el Dueño pide sobre el precio al dar de alta o editar un articulo (CU-14). {@code manual} true fija
 * {@code precio} a mano, false lo vuelve a calcular y null conserva la marca de hoy; {@code ajustes} null conserva los
 * ajustes de hoy. {@code precio} solo cuenta con {@code manual} true.
 */
public record PedidoDePrecio(Boolean manual, BigDecimal precio, AjustesDePrecio ajustes) {

    /** El pedido que no toca nada del precio: el de los clientes que no mandan estos campos. */
    public static final PedidoDePrecio CONSERVAR = new PedidoDePrecio(null, null, null);
}
