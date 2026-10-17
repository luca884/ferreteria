package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.AlicuotaIva;
import com.ferreteria.catalogo.model.Unidad;

import java.math.BigDecimal;

/**
 * Un articulo como lo ve el Vendedor (H07): precio, stock y ubicacion. Sin costo, margen, moneda del costo, precio
 * de lista, bonificaciones, ajustes ni version: nada que permita deducir el costo, y el Vendedor no edita.
 */
public record ArticuloVendedorResponse(
        Long id,
        String codigoInterno,
        String codigoBarras,
        String codigoProveedor,
        String descripcion,
        Long rubroId,
        Long proveedorId,
        AlicuotaIva alicuotaIva,
        BigDecimal precioFinal,
        Unidad unidad,
        BigDecimal stockActual,
        BigDecimal stockMinimo,
        String ubicacion,
        boolean activo) implements ArticuloLectura {

    public static ArticuloVendedorResponse from(ArticuloResponse completo) {
        return new ArticuloVendedorResponse(
                completo.id(),
                completo.codigoInterno(),
                completo.codigoBarras(),
                completo.codigoProveedor(),
                completo.descripcion(),
                completo.rubroId(),
                completo.proveedorId(),
                completo.alicuotaIva(),
                completo.precioFinal(),
                completo.unidad(),
                completo.stockActual(),
                completo.stockMinimo(),
                completo.ubicacion(),
                completo.activo());
    }
}
