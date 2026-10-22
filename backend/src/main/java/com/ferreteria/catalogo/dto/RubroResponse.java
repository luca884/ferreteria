package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.model.Rubro;

import java.math.BigDecimal;
import java.util.List;

/**
 * @param avisos los articulos que conservaron su precio al cambiar el margen del rubro porque sus ajustes sobre el
 *               precio final lo habrian dejado en 0 o menos (S7); vacio fuera del PUT que cambia el margen
 */
public record RubroResponse(Long id, String nombre, BigDecimal margenPorcentaje, List<String> avisos)
        implements RubroLectura {

    public static RubroResponse from(Rubro rubro) {
        return conAvisos(rubro, List.of());
    }

    public static RubroResponse conAvisos(Rubro rubro, List<String> avisos) {
        return new RubroResponse(rubro.getId(), rubro.getNombre(), rubro.getMargenPorcentaje(), avisos);
    }
}
