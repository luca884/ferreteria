package com.ferreteria.stock.service;

import java.math.BigDecimal;

/** Un articulo que sale del stock y cuanto: cantidad positiva, la que se vendio. */
public record SalidaStock(Long articuloId, BigDecimal cantidad) {
}
