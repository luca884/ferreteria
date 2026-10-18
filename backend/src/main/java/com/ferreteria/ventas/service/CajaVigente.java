package com.ferreteria.ventas.service;

import com.ferreteria.ventas.model.Caja;
import com.ferreteria.ventas.model.EstadoCaja;

import java.math.BigDecimal;

/**
 * La caja abierta de la ferreteria (o ninguna: {@code caja} y {@code estado} null), quien la abrio y el efectivo que se
 * propone para la proxima apertura (el contado del ultimo cierre, o 0). No lleva totales: la ven los dos roles.
 */
public record CajaVigente(Caja caja, EstadoCaja estado, String abiertaPor, BigDecimal efectivoPropuesto) {}
