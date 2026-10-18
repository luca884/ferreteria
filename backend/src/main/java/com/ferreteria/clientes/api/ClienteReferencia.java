package com.ferreteria.clientes.api;

/** Lo único de un cliente que ventas necesita: quién es (para copiar su nombre al documento) y si se puede usar. */
public record ClienteReferencia(Long id, String razonSocial, boolean activo) {}
