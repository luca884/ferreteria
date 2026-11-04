package com.ferreteria.compras.service;

import com.ferreteria.catalogo.api.RecargoDeCompra;
import com.ferreteria.compras.model.TipoCompra;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * El pedido de registrar una compra (CU-13), ya validado en su forma. {@code clave} es la de idempotencia; la vista
 * previa no la usa y puede venir null. {@code recargos} son los del costo que trae la factura (vacios si no tiene); catalogo
 * los valida.
 */
public record RegistrarCompra(
        String clave,
        Long proveedorId,
        TipoCompra tipo,
        int puntoVenta,
        long numero,
        LocalDate fecha,
        List<RenglonCompra> renglones,
        List<RecargoDeCompra> recargos) {

    public RegistrarCompra {
        recargos = recargos == null ? List.of() : List.copyOf(recargos);
    }

    /** Una compra sin recargos. */
    public RegistrarCompra(String clave, Long proveedorId, TipoCompra tipo, int puntoVenta, long numero,
                           LocalDate fecha, List<RenglonCompra> renglones) {
        this(clave, proveedorId, tipo, puntoVenta, numero, fecha, renglones, List.of());
    }

    /** Un renglon: el articulo, la cantidad comprada y su costo unitario sin IVA, en la moneda del articulo. */
    public record RenglonCompra(Long articuloId, BigDecimal cantidad, BigDecimal costoUnitario) {
    }
}
