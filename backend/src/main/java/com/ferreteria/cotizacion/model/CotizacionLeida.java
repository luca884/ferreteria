package com.ferreteria.cotizacion.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Lo que una fuente informa de un tipo de cotizacion. {@code compra} o {@code venta} pueden faltar (hay tipos que
 * informan un solo lado); {@code actualizadaEn} es la fecha del mercado segun la fuente.
 */
public record CotizacionLeida(String moneda, String casa, String nombre, BigDecimal compra, BigDecimal venta,
                              Instant actualizadaEn) {}
