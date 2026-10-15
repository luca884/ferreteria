package com.ferreteria.stock.web;

import com.ferreteria.stock.service.AjusteRegistrado;

import java.math.BigDecimal;

public record AjusteStockResponse(Long articuloId, BigDecimal stockAnterior, BigDecimal diferencia, BigDecimal stockActual) {

    public static AjusteStockResponse de(AjusteRegistrado ajuste) {
        return new AjusteStockResponse(
                ajuste.articuloId(), ajuste.stockAnterior(), ajuste.diferencia(), ajuste.stockActual());
    }
}
