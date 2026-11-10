package com.ferreteria.ventas.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.ferreteria.facturacion.api.FacturacionParaVenta.ResumenFiscal;
import com.ferreteria.ventas.model.EstadoComprobante;
import com.ferreteria.ventas.service.VentaGestion.Anulacion;
import com.ferreteria.ventas.service.VentaGestion.VentaAnulada;
import com.ferreteria.ventas.service.VentaGestion.VentaListada;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Una fila de la consulta de ventas (G60): lo justo para la grilla, sin renglones. {@code clienteNombre} solo viene si la
 * venta tiene cliente; {@code facturable} es false si quedo NO_FACTURABLE (NF-002). {@code fiscal} (CU-16,
 * cu-07-factura-mostrador C2) es el resumen de su factura, o null si la venta no tiene registro fiscal: se manda igual.
 *
 * <p>CU-08 (C4, S14): la lista trae tambien las notas de credito, con {@code tipo} NOTA_CREDITO, el {@code total} en
 * positivo (como se guarda; la pantalla lo muestra en negativo) y la {@code ventaAnulada}, que solo viene en una nota. Cada
 * fila lleva {@code anulacion}: como se anulo la venta, o null (siempre null en una nota).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VentaResumenResponse(
        Long ventaId, Long numero, LocalDateTime fecha, String clienteNombre, BigDecimal total, String usuario,
        boolean facturable, @JsonInclude(JsonInclude.Include.ALWAYS) FiscalResumenResponse fiscal, String tipo,
        VentaAnuladaResponse ventaAnulada, @JsonInclude(JsonInclude.Include.ALWAYS) AnulacionResponse anulacion) {

    /** La venta que anula una nota de credito. */
    public record VentaAnuladaResponse(long id, long numero) {}

    /** Como se anulo la venta: {@code interna} es la anulacion interna (con {@code motivo}); si no, la NC con CAE. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AnulacionResponse(long notaId, long numero, boolean interna, String motivo, String usuario,
            LocalDateTime fecha) {}

    /**
     * La factura de la venta para la columna «Factura».
     *
     * @param letra la letra de la hoja: A, B o C si es válida como factura; X si no
     * @param numero null mientras no tiene número fiscal
     * @param reimprimir salió con ticket provisorio y ya está AUTORIZADO (cu-07-contingencias C1)
     * @param letraFiscal la letra del registro (A, B o C), también en la X: la lista la muestra entre paréntesis
     *     (bugs-relevamiento-celular, S9)
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record FiscalResumenResponse(String letra, int puntoVenta, Long numero, String estado, String ambiente,
            boolean validoComoFactura, boolean reimprimir, String letraFiscal) {

        static FiscalResumenResponse de(ResumenFiscal r) {
            return r == null ? null : new FiscalResumenResponse(r.letra(), r.puntoVenta(), r.numero(), r.estado(),
                    r.ambiente(), r.validoComoFactura(), r.reimprimir(), r.letraFiscal());
        }
    }

    public static VentaResumenResponse de(VentaListada fila) {
        Anulacion a = fila.anulacion();
        VentaAnulada anulada = fila.ventaAnulada();
        return new VentaResumenResponse(fila.venta().getId(), fila.venta().getNumero(), fila.venta().getFecha(),
                fila.venta().getClienteNombre(), fila.venta().getTotal(), fila.usuario(),
                fila.venta().getEstado() != EstadoComprobante.NO_FACTURABLE, FiscalResumenResponse.de(fila.fiscal()),
                fila.venta().getTipo().name(),
                anulada == null ? null : new VentaAnuladaResponse(anulada.id(), anulada.numero()),
                a == null ? null : new AnulacionResponse(a.notaId(), a.numero(), a.interna(), a.motivo(), a.usuario(),
                        a.fecha()));
    }
}
