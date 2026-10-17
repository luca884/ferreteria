package com.ferreteria.catalogo.dto;

/** Un rubro como lo ve el Vendedor (H07): sin el margen. */
public record RubroVendedorResponse(Long id, String nombre) implements RubroLectura {

    public static RubroVendedorResponse from(RubroResponse completo) {
        return new RubroVendedorResponse(completo.id(), completo.nombre());
    }
}
