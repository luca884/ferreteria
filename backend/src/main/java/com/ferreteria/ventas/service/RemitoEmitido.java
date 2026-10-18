package com.ferreteria.ventas.service;

import com.ferreteria.ventas.model.ItemRemito;
import com.ferreteria.ventas.model.Remito;
import com.ferreteria.ventas.service.VentaRegistrada.ArticuloSinStock;

import java.util.List;

/**
 * Resultado de emitir: el remito con sus renglones, los articulos que quedaron con stock negativo (como la venta) y si es
 * el original de una clave repetida (200 y no 201; en ese caso no se avisa nada de stock).
 */
public record RemitoEmitido(Remito remito, List<ItemRemito> items, List<ArticuloSinStock> sinStock, boolean repetida) {}
