package com.ferreteria.ventas.web;

import com.ferreteria.ventas.model.FormaPago;
import com.ferreteria.ventas.model.MedioPago;

import java.math.BigDecimal;

public record FormaPagoResponse(Long id, String nombre, MedioPago medio, BigDecimal recargoPorcentaje, boolean activa,
        int orden) {

    public static FormaPagoResponse de(FormaPago forma) {
        return new FormaPagoResponse(forma.getId(), forma.getNombre(), forma.getMedio(), forma.getRecargoPorcentaje(),
                forma.isActiva(), forma.getOrden());
    }
}
