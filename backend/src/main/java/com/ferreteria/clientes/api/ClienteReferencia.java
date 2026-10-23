package com.ferreteria.clientes.api;

/**
 * Lo único de un cliente que ventas necesita: quién es (para copiar su nombre al documento), si se puede usar y si está
 * identificado (tiene DNI, CUIT o CUIL: lo pide la venta a Consumidor Final desde el tope, GDS-32).
 */
public record ClienteReferencia(Long id, String razonSocial, boolean activo, boolean identificado) {}
