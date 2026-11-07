package com.ferreteria.catalogo.dto;

import com.ferreteria.catalogo.service.RecalculoPreciosService.PrecioQueCambia;

import java.math.BigDecimal;
import java.util.List;

/**
 * Vista previa del margen nuevo de un rubro (mejoras-rubros, S1): cuantos articulos cambiarian de precio y, de esos,
 * hasta {@code maximo} con el precio de hoy y el nuevo, por descripcion. {@code recortado} avisa que hay mas de los que
 * vienen en la lista. No guarda nada.
 */
public record VistaPreviaMargenRubroResponse(long cantidad, List<Fila> articulos, boolean recortado) {

    public record Fila(Long id, String codigo, String descripcion, BigDecimal precioActual, BigDecimal precioNuevo) {
    }

    public static VistaPreviaMargenRubroResponse de(List<PrecioQueCambia> cambian, int maximo) {
        List<Fila> filas = cambian.stream()
                .limit(maximo)
                .map(p -> new Fila(p.articuloId(), p.codigo(), p.descripcion(), p.precioActual(), p.precioNuevo()))
                .toList();
        return new VistaPreviaMargenRubroResponse(cambian.size(), filas, cambian.size() > maximo);
    }
}
