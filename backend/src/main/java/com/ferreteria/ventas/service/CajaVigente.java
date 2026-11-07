package com.ferreteria.ventas.service;

import com.ferreteria.ventas.model.Caja;
import com.ferreteria.ventas.model.EstadoCaja;

import java.math.BigDecimal;

/**
 * La caja abierta de la ferreteria (o ninguna: {@code caja} y {@code estado} null), quien la abrio y el efectivo que se
 * propone para la proxima apertura (el contado del ultimo cierre, o 0). {@code hayCierreAnterior} distingue «nunca se
 * cerro una caja» (propone 0 porque no hay nada que proponer) de «el ultimo cierre conto 0». No lleva totales: la ven
 * los dos roles.
 */
public record CajaVigente(Caja caja, EstadoCaja estado, String abiertaPor, BigDecimal efectivoPropuesto,
        boolean hayCierreAnterior) {}
