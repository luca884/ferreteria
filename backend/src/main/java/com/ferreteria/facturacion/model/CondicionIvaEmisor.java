package com.ferreteria.facturacion.model;

/**
 * La condición frente al IVA de quien emite el comprobante (la ferretería). Es propia de {@code facturacion}: quien
 * pide la factura traduce la suya a esta.
 */
public enum CondicionIvaEmisor {
    RESPONSABLE_INSCRIPTO,
    MONOTRIBUTISTA,
    EXENTO
}
