package com.ferreteria.facturacion.web;

/**
 * El .crt que ARCA emitió para el pedido pendiente (S4), como texto dentro del JSON (no multipart): PEM, o DER en Base64,
 * hasta 16 KB. Solo el certificado: la clave privada no se sube nunca (D8).
 */
public record CertificadoRequest(String certificado) {
}
