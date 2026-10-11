package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.Rubro;

import java.math.BigDecimal;

public record RubroResponse(Long id, String nombre, BigDecimal margenPorcentaje) {

    public static RubroResponse from(Rubro rubro) {
        return new RubroResponse(rubro.getId(), rubro.getNombre(), rubro.getMargenPorcentaje());
    }
}
