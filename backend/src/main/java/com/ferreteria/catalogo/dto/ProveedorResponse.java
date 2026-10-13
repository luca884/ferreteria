package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.MapeoLista;
import com.ferreteria.catalogo.model.Proveedor;

import java.math.BigDecimal;

/** Los campos de la lista (mapeo, IVA, bonificaciones) son de solo lectura: los fija la importacion. */
public record ProveedorResponse(
        Long id,
        String nombre,
        String cuit,
        boolean activo,
        MapeoLista mapeoLista,
        boolean listaIncluyeIva,
        BigDecimal bonificacion1,
        BigDecimal bonificacion2,
        BigDecimal bonificacion3) {

    public static ProveedorResponse from(Proveedor proveedor) {
        return new ProveedorResponse(
                proveedor.getId(),
                proveedor.getNombre(),
                proveedor.getCuit(),
                proveedor.isActivo(),
                proveedor.getMapeoLista(),
                proveedor.isListaIncluyeIva(),
                proveedor.getBonificacion1(),
                proveedor.getBonificacion2(),
                proveedor.getBonificacion3());
    }
}
