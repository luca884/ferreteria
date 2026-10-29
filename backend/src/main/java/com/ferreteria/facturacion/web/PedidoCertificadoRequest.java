package com.ferreteria.facturacion.web;

/**
 * El pedido de certificado (S3, S14): {@code cuit} null usa el de la ferretería; otro (la persona física de homologación),
 * con o sin guiones, solo si el ambiente no es PRODUCCION.
 */
public record PedidoCertificadoRequest(String cuit) {
}
