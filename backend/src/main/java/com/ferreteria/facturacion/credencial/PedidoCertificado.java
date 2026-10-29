package com.ferreteria.facturacion.credencial;

/**
 * El pedido de certificado para bajar o copiar (C1): el CSR en PEM y el nombre del archivo
 * ({@code pedido-certificado-<cuit>.csr}). Es un dato público: la clave privada no sale nunca del servidor.
 */
public record PedidoCertificado(String csr, String archivo) {}
