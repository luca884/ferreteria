package com.ferreteria.stock.web;

import com.ferreteria.stock.model.ModoAjuste;
import com.ferreteria.stock.model.MotivoAjuste;

import java.math.BigDecimal;

/**
 * Ajuste manual de stock (CU-09). Dos modos: STOCK_REAL (el default, retrocompatible) manda el total contado en
 * stockReal; CANTIDAD manda lo que entra (+) o sale (-) en cantidad y se suma al stock vigente al confirmar.
 * Las validaciones las hace el servicio, despues del 404, y salen todas juntas.
 */
public record AjusteStockRequest(
        ModoAjuste modo, BigDecimal stockReal, BigDecimal cantidad, MotivoAjuste motivo, String detalle) {
}
