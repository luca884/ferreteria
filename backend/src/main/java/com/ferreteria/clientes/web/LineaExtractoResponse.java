package com.ferreteria.clientes.web;

import com.ferreteria.clientes.model.TipoMovimientoCuenta;
import com.ferreteria.clientes.service.ExtractoCuenta.Linea;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Una línea del extracto (CC-078): el movimiento del libro y el saldo del cliente DESPUÉS de él. {@code debe} y {@code haber}
 * salen siempre con 2 decimales y uno de los dos es 0; {@code reciboId} solo en COBRO y ANULACION, {@code comprobanteId} en VENTA,
 * {@code motivo} en ANULACION, {@code excedioLimite} y {@code autorizadoPor} en VENTA; {@code anulado} es true en un COBRO cuyo
 * recibo tiene ANULACION. Los que no corresponden salen {@code null}.
 */
public record LineaExtractoResponse(Long id, LocalDateTime fecha, TipoMovimientoCuenta tipo, String referencia, BigDecimal debe,
        BigDecimal haber, BigDecimal saldo, String usuario, Long reciboId, Long comprobanteId, String motivo,
        boolean excedioLimite, String autorizadoPor, boolean anulado) {

    public static LineaExtractoResponse de(Linea linea) {
        return new LineaExtractoResponse(linea.id(), linea.fecha(), linea.tipo(), linea.referencia(), dos(linea.debe()),
                dos(linea.haber()), dos(linea.saldo()), linea.usuario(), linea.reciboId(), linea.comprobanteId(), linea.motivo(),
                linea.excedioLimite(), linea.autorizadoPor(), linea.anulado());
    }

    static BigDecimal dos(BigDecimal importe) {
        return importe.setScale(2);
    }
}
