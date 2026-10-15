package com.ferreteria.stock.web;

import com.ferreteria.stock.model.MotivoAjuste;

import java.math.BigDecimal;

/**
 * Ajuste manual de stock (CU-09): el stock REAL contado (no la diferencia), el motivo y un detalle opcional
 * (obligatorio con OTRO). Las validaciones las hace el servicio, despues del 404, y salen todas juntas.
 */
public record AjusteStockRequest(BigDecimal stockReal, MotivoAjuste motivo, String detalle) {
}
