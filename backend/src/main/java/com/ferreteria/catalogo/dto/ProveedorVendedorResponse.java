package com.ferreteria.catalogo.dto;

/**
 * Un proveedor como lo ve el Vendedor (H07): sin el mapeo ni las condiciones de la lista, de donde sale el costo. El
 * contacto si lo ve: es para comunicarse, no dice nada del costo.
 */
public record ProveedorVendedorResponse(Long id, String nombre, String cuit, boolean activo, String contacto,
                                        String telefono, String whatsapp, String email) implements ProveedorLectura {

    public static ProveedorVendedorResponse from(ProveedorResponse completo) {
        return new ProveedorVendedorResponse(completo.id(), completo.nombre(), completo.cuit(), completo.activo(),
                completo.contacto(), completo.telefono(), completo.whatsapp(), completo.email());
    }
}
