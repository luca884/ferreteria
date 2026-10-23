package com.ferreteria.ventas.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ferreteria.ventas.model.EstadoComprobante;
import com.ferreteria.ventas.service.VentaGestion.VentaListada;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Una fila de la consulta de ventas (G60): lo justo para la grilla, sin renglones. {@code clienteNombre} solo viene si la
 * venta tiene cliente; {@code facturable} es false si quedo NO_FACTURABLE (NF-002).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VentaResumenResponse(
        Long ventaId, Long numero, LocalDateTime fecha, String clienteNombre, BigDecimal total, String usuario,
        boolean facturable) {

    public static VentaResumenResponse de(VentaListada fila) {
        return new VentaResumenResponse(fila.venta().getId(), fila.venta().getNumero(), fila.venta().getFecha(),
                fila.venta().getClienteNombre(), fila.venta().getTotal(), fila.usuario(),
                fila.venta().getEstado() != EstadoComprobante.NO_FACTURABLE);
    }
}
