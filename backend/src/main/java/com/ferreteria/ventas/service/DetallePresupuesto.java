package com.ferreteria.ventas.service;

import com.ferreteria.ventas.model.ItemPresupuesto;
import com.ferreteria.ventas.model.Presupuesto;

import java.util.List;

/** Un presupuesto con sus renglones, en el orden del ticket. */
public record DetallePresupuesto(Presupuesto presupuesto, List<ItemPresupuesto> items) {}
