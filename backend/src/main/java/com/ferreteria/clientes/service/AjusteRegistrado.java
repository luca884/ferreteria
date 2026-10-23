package com.ferreteria.clientes.service;

import com.ferreteria.clientes.api.CuentaCorrienteParaCobranza.ImputacionAsentada;
import com.ferreteria.clientes.model.AjusteCuenta;
import java.math.BigDecimal;
import java.util.List;

/**
 * Un ajuste ya registrado (GDS-20): el ajuste, el saldo del cliente justo DESPUÉS de él, las imputaciones que pidió el
 * Descuento (con la referencia de cada deuda) y si es la respuesta de una clave repetida (200) o un ajuste nuevo (201).
 */
public record AjusteRegistrado(AjusteCuenta ajuste, BigDecimal saldo, List<ImputacionAsentada> imputaciones, boolean repetida) {}
