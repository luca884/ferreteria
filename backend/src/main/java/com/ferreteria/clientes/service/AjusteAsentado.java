package com.ferreteria.clientes.service;

import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionAsentada;
import java.math.BigDecimal;
import java.util.List;

/**
 * Lo que dejó asentar un ajuste (GDS-20): el id de su renglón del libro, el saldo del cliente DESPUÉS del ajuste y las
 * imputaciones que pidió un Descuento (vacío en un Recargo), con la referencia de cada deuda.
 */
public record AjusteAsentado(Long movimientoId, BigDecimal saldo, List<ImputacionAsentada> imputaciones) {}
