package com.ferreteria.ventas.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ferreteria.common.HoraArgentina;
import com.ferreteria.ventas.model.EstadoPresupuesto;
import com.ferreteria.ventas.model.Presupuesto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/** Una fila del listado de presupuestos (PP-005): lo justo para la grilla, sin renglones. {@code vencido} se calcula al responder. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PresupuestoResumenResponse(
        Long presupuestoId, Long numero, EstadoPresupuesto estado, LocalDateTime fecha, LocalDate vence, boolean vencido,
        String clienteNombre, BigDecimal total) {

    public static PresupuestoResumenResponse de(Presupuesto p) {
        return new PresupuestoResumenResponse(p.getId(), p.getNumero(), p.getEstado(), p.getFecha(), p.getVence(),
                p.estaVencido(HoraArgentina.hoy()), p.getClienteNombre(), p.getTotal());
    }
}
