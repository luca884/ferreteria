package com.ferreteria.ventas.service;

import com.ferreteria.ventas.model.Comprobante;
import com.ferreteria.ventas.model.ItemComprobante;
import com.ferreteria.ventas.model.Pago;

import java.math.BigDecimal;
import java.util.List;

/**
 * Resultado de cobrar: la venta guardada con sus renglones y su pago, los articulos que quedaron con stock
 * negativo (R-016) y si es la venta original de una clave repetida (R-011: el endpoint responde 200 y no 201).
 */
public record VentaRegistrada(Comprobante comprobante, List<ItemComprobante> items, Pago pago,
        List<ArticuloSinStock> sinStock, boolean repetida) {

    /** Un articulo cuyo stock resultante quedo menor que 0. */
    public record ArticuloSinStock(Long articuloId, String descripcion, BigDecimal stockActual) {}
}
