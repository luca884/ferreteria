package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.Proveedor;

public record ProveedorResponse(Long id, String nombre, String cuit, boolean activo) {

    public static ProveedorResponse from(Proveedor proveedor) {
        return new ProveedorResponse(proveedor.getId(), proveedor.getNombre(), proveedor.getCuit(), proveedor.isActivo());
    }
}
