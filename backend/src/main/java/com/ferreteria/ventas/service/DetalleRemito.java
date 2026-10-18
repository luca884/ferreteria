package com.ferreteria.ventas.service;

import com.ferreteria.ventas.model.ItemRemito;
import com.ferreteria.ventas.model.Remito;

import java.util.List;

/** Un remito con sus renglones, en el orden del ticket. */
public record DetalleRemito(Remito remito, List<ItemRemito> items) {}
