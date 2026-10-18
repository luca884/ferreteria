package com.ferreteria.ventas.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ferreteria.ventas.model.EstadoRemito;
import com.ferreteria.ventas.model.Remito;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** Una fila del listado de remitos (RM-005): lo justo para la grilla, sin renglones. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RemitoResumenResponse(
        Long remitoId, Long numero, EstadoRemito estado, LocalDateTime fecha, String clienteNombre, BigDecimal total) {

    public static RemitoResumenResponse de(Remito r) {
        return new RemitoResumenResponse(r.getId(), r.getNumero(), r.getEstado(), r.getFecha(), r.getClienteNombre(),
                r.getTotal());
    }
}
