package com.ferreteria.compras.service;

import com.ferreteria.compras.model.TipoCompra;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * El pedido de registrar una compra (CU-13), ya validado en su forma. {@code clave} es la de idempotencia; la vista
 * previa no la usa y puede venir null.
 */
public record RegistrarCompra(
        String clave,
        Long proveedorId,
        TipoCompra tipo,
        int puntoVenta,
        long numero,
        LocalDate fecha,
        List<RenglonCompra> renglones) {

    /** Un renglon: el articulo, la cantidad comprada y su costo unitario sin IVA, en la moneda del articulo. */
    public record RenglonCompra(Long articuloId, BigDecimal cantidad, BigDecimal costoUnitario) {
    }
}
