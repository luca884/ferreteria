package com.ferreteria.compras.service;

import com.ferreteria.compras.model.TipoCompra;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Lo que se leyo de la factura del proveedor en PDF (CU-13), resuelto contra la ferreteria del request: es un borrador
 * para que el Dueno lo revise, no se guarda nada. {@code proveedorId} es null si ningun CUIT de la factura es el de un
 * proveedor activo (y entonces ningun renglon trae articulo). {@code tipo}, {@code puntoVenta}, {@code numero} y
 * {@code fecha} son null si la factura no los trae. {@code cantidadNoEntera} avisa que algun articulo por unidad viene con
 * cantidad decimal (la compra la rechazaria).
 */
public record BorradorDeFactura(
        Long proveedorId,
        String proveedorNombre,
        List<String> cuits,
        TipoCompra tipo,
        Integer puntoVenta,
        Long numero,
        LocalDate fecha,
        List<Recargo> recargos,
        List<Renglon> renglones,
        BigDecimal sumaNetos,
        Control control,
        boolean cantidadNoEntera) {

    public enum Control { CUADRA, SIN_CONTROL }

    public record Recargo(String nombre, BigDecimal porcentaje, BigDecimal importe) {
    }

    /**
     * {@code codigo} es el del proveedor. {@code articuloId}, {@code articuloDescripcion}, {@code articuloCodigoInterno}
     * y {@code articuloMoneda} son null si el codigo del proveedor no tiene articulo.
     */
    public record Renglon(
            String codigo,
            String descripcion,
            BigDecimal cantidad,
            BigDecimal costoUnitario,
            BigDecimal neto,
            Long articuloId,
            String articuloDescripcion,
            String articuloCodigoInterno,
            String articuloMoneda,
            boolean cantidadNoEntera) {
    }
}
