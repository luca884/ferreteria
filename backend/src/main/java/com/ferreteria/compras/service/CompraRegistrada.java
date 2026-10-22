package com.ferreteria.compras.service;

import com.ferreteria.compras.model.Compra;
import com.ferreteria.compras.model.ItemCompra;

import java.util.List;

/** La compra guardada y sus renglones. {@code repetida} es true si la clave ya estaba registrada con el mismo contenido. */
public record CompraRegistrada(Compra compra, List<ItemCompra> items, boolean repetida) {
}
