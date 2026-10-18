package com.ferreteria.clientes.model;

/**
 * La condición frente al IVA de un cliente. Distinta de la de la ferretería: un cliente puede ser Consumidor Final,
 * que para ARCA decide el comprobante (B) cuando llegue la fase 5.
 */
public enum CondicionIvaCliente {
    CONSUMIDOR_FINAL,
    RESPONSABLE_INSCRIPTO,
    MONOTRIBUTISTA,
    EXENTO
}
