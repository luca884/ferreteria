package com.ferreteria.ventas.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ferreteria.facturacion.api.FacturacionParaVenta.ResumenFiscal;
import com.ferreteria.ventas.model.EstadoComprobante;
import com.ferreteria.ventas.service.VentaGestion.VentaListada;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Una fila de la consulta de ventas (G60): lo justo para la grilla, sin renglones. {@code clienteNombre} solo viene si la
 * venta tiene cliente; {@code facturable} es false si quedo NO_FACTURABLE (NF-002). {@code fiscal} (CU-16,
 * cu-07-factura-mostrador C2) es el resumen de su factura, o null si la venta no tiene registro fiscal: se manda igual.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VentaResumenResponse(
        Long ventaId, Long numero, LocalDateTime fecha, String clienteNombre, BigDecimal total, String usuario,
        boolean facturable, @JsonInclude(JsonInclude.Include.ALWAYS) FiscalResumenResponse fiscal) {

    /**
     * La factura de la venta para la columna «Factura».
     *
     * @param letra la letra de la hoja: A, B o C si es válida como factura; X si no
     * @param numero null mientras no tiene número fiscal
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FiscalResumenResponse(String letra, int puntoVenta, Long numero, String estado, String ambiente,
            boolean validoComoFactura) {

        static FiscalResumenResponse de(ResumenFiscal r) {
            return r == null ? null : new FiscalResumenResponse(r.letra(), r.puntoVenta(), r.numero(), r.estado(),
                    r.ambiente(), r.validoComoFactura());
        }
    }

    public static VentaResumenResponse de(VentaListada fila) {
        return new VentaResumenResponse(fila.venta().getId(), fila.venta().getNumero(), fila.venta().getFecha(),
                fila.venta().getClienteNombre(), fila.venta().getTotal(), fila.usuario(),
                fila.venta().getEstado() != EstadoComprobante.NO_FACTURABLE, FiscalResumenResponse.de(fila.fiscal()));
    }
}
