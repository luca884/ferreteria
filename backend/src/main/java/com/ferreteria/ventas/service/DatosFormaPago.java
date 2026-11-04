package com.ferreteria.ventas.service;

import com.ferreteria.ventas.model.MedioPago;

import java.math.BigDecimal;

/**
 * Lo que se guarda de una forma de pago. Al crear, lo que falta vale: recargo y descuento 0, activa y el orden siguiente
 * al ultimo; al actualizar, lo que falta se conserva.
 */
public record DatosFormaPago(String nombre, MedioPago medio, BigDecimal recargoPorcentaje,
        BigDecimal descuentoPorcentaje, Boolean activa, Integer orden) {}
