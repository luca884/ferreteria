package com.ferreteria.stock.service;

import java.math.BigDecimal;

/** Resultado de un ajuste manual: el stock que habia, la diferencia aplicada (con signo) y el que quedo. */
public record AjusteRegistrado(Long articuloId, BigDecimal stockAnterior, BigDecimal diferencia, BigDecimal stockActual) {
}
