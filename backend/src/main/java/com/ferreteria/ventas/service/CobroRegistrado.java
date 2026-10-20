package com.ferreteria.ventas.service;

import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionAsentada;
import com.ferreteria.ventas.model.Recibo;

import java.math.BigDecimal;
import java.util.List;

/**
 * El resultado de una cobranza: el recibo, a qué comprobantes se imputó, quién cobró y si es la repetición de un cobro ya
 * registrado con la misma clave ({@code repetida}: 200 en vez de 201). No lleva el saldo ni «lo que resta»: no se guardan, y
 * la repetida tiene que devolver lo mismo que la original.
 */
public record CobroRegistrado(Recibo recibo, List<ImputacionAsentada> imputaciones, String cobradoPor, boolean repetida) {

    /** Lo que quedó «a cuenta» (saldo a favor): el total del recibo menos lo imputado. Derivado, no se guarda. */
    public BigDecimal aCuenta() {
        BigDecimal imputado = imputaciones.stream().map(ImputacionAsentada::importe).reduce(BigDecimal.ZERO, BigDecimal::add);
        return recibo.getTotal().subtract(imputado);
    }
}
