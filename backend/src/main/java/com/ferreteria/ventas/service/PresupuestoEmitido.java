package com.ferreteria.ventas.service;

import com.ferreteria.ventas.model.ItemPresupuesto;
import com.ferreteria.ventas.model.Presupuesto;

import java.util.List;

/** Resultado de emitir: el presupuesto con sus renglones y si es el original de una clave repetida (200 y no 201). */
public record PresupuestoEmitido(Presupuesto presupuesto, List<ItemPresupuesto> items, boolean repetida) {}
