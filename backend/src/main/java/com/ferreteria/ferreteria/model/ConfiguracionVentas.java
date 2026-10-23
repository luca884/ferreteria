package com.ferreteria.ferreteria.model;

import java.math.BigDecimal;

/** Configuracion de ventas de una ferreteria: desde que importe la venta a Consumidor Final necesita identificarlo. */
public record ConfiguracionVentas(BigDecimal topeConsumidorFinal) {
}
